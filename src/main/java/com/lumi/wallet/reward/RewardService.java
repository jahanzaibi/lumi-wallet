package com.lumi.wallet.reward;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.lumi.wallet.account.WalletAccount;
import com.lumi.wallet.account.WalletAccountService;
import com.lumi.wallet.account.WalletBalance;
import com.lumi.wallet.common.Amounts;
import com.lumi.wallet.common.ErrorCode;
import com.lumi.wallet.common.WalletException;
import com.lumi.wallet.event.EventEnvelope;
import com.lumi.wallet.event.outbound.WalletEventPublisher;
import com.lumi.wallet.event.outbound.WalletEventType;
import com.lumi.wallet.ledger.Direction;
import com.lumi.wallet.ledger.LedgerReferenceType;
import com.lumi.wallet.ledger.LedgerService;
import com.lumi.wallet.ledger.LedgerTransactionType;
import com.lumi.wallet.redemption.RedemptionItem;
import com.lumi.wallet.redemption.RedemptionItemRepository;
import com.lumi.wallet.redemption.RedemptionStatus;
import com.lumi.wallet.support.WalletClock;

/**
 * Reward earning, availability, consumption, reversal and expiry (HELP.md sections 14 to 22).
 *
 * <h2>The invariant this class exists to protect</h2>
 *
 * <pre>
 * ledger position of the customer's reward account == available_amount + locked_amount
 * SUM(remaining_points of AVAILABLE lots)           == available_amount
 * </pre>
 *
 * <p>Every method that moves points therefore touches three things together: the lot (which points,
 * expiring when), the balance (how many are spendable) and the ledger (the immutable record). A
 * change to one without the others is a bug, so they are never done in separate methods.
 *
 * <p>Reward debt sits deliberately <em>outside</em> that first identity. When a reversal targets
 * points the customer already spent, the wallet cannot take them back; it records an obligation
 * (HELP.md section 22) and posts nothing, because no points moved. The ledger entry appears later,
 * when a future reward actually pays the debt down. Accounting for the debt at reversal time instead
 * would mean posting a movement that did not happen.
 *
 * <p>Callers must hold the customer's balance row lock before any mutation, which every public
 * method here takes for itself (HELP.md sections 41, 42).
 */
@Service
public class RewardService {

    private static final Logger log = LoggerFactory.getLogger(RewardService.class);

    private final RewardTransactionRepository transactions;
    private final RewardTransactionItemRepository transactionItems;
    private final RewardLotRepository lots;
    private final RewardRuleRepository rules;
    private final RedemptionItemRepository redemptionItems;
    private final WalletAccountService accounts;
    private final LedgerService ledger;
    private final WalletEventPublisher events;
    private final WalletClock clock;

    public RewardService(RewardTransactionRepository transactions,
            RewardTransactionItemRepository transactionItems, RewardLotRepository lots,
            RewardRuleRepository rules, RedemptionItemRepository redemptionItems,
            WalletAccountService accounts, LedgerService ledger, WalletEventPublisher events,
            WalletClock clock) {
        this.transactions = transactions;
        this.transactionItems = transactionItems;
        this.lots = lots;
        this.rules = rules;
        this.redemptionItems = redemptionItems;
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
        this.clock = clock;
    }

    // =============================================================================================
    // Earning (HELP.md section 14)
    // =============================================================================================

    /**
     * Records a reward earning as PENDING (HELP.md section 14). The customer cannot use the points
     * yet, so no balance moves and nothing is posted to the ledger.
     *
     * <p>Idempotent through {@code uk_reward_transaction_dedupe}: one earning per order, however
     * many times a payment success is delivered (HELP.md section 60, rule 12).
     *
     * @return the earning, or empty when the order earns nothing under its rule
     */
    @Transactional
    public Optional<RewardTransaction> earn(EarnRequest request) {
        Instant now = clock.now();
        String dedupeKey = DedupeKeys.earn(request.orderId());

        Optional<RewardTransaction> existing = transactions.findByDedupeKey(dedupeKey);
        if (existing.isPresent()) {
            log.debug("Order {} already earned reward {}", request.orderId(),
                    existing.get().getId());
            return existing;
        }

        RewardRule rule = resolveRule(request.orderType());
        BigDecimal points = rule.pointsFor(request.orderAmount());
        if (!Amounts.isPositive(points)) {
            log.info("Order {} ({} {}) earns no points under rule {}", request.orderId(),
                    request.orderAmount(), request.currency(), rule.getId());
            return Optional.empty();
        }

        // Ensures the account and its balance row exist now, so that the later availability step
        // has something to lock.
        accounts.rewardAccountFor(request.customerId());

        Instant availableAt = timeBasedAvailability(rule, now);
        Instant expiresAt = expiryFor(rule, now);

        RewardTransaction earning = RewardTransaction.earn(request.customerId(), request.orderId(),
                rule, points, request.orderAmount(), request.currency(), availableAt, expiresAt,
                now);
        // A concurrent delivery of the same payment success trips uk_reward_transaction_dedupe. The
        // violation is left to propagate: a failed flush marks the transaction rollback-only, so
        // catching it and returning the winner's row would fail at commit regardless. Rolling back
        // and letting the message be redelivered takes the "already earned" path above instead.
        transactions.saveAndFlush(earning);

        lots.save(RewardLot.pending(earning, now));
        recordItemAttribution(earning, request.items(), points);

        events.publish(WalletEventType.REWARD_PENDING, EventEnvelope.newPayload()
                .with("customerId", earning.getCustomerId())
                .with("orderId", earning.getOrderId())
                .with("rewardTransactionId", earning.getId())
                .with("points", earning.getPoints())
                .with("availableAt", availableAt)
                .with("expiresAt", expiresAt)
                .build());

        log.info("Order {} earned {} pending points for customer {} (rule {})", request.orderId(),
                points, request.customerId(), rule.getId());
        return Optional.of(earning);
    }

    /**
     * Splits an earning across the order's items (HELP.md section 53), so that refunding one line
     * later reverses that line's points rather than a guess based on the order total.
     *
     * <p>The last item absorbs the rounding remainder, so the parts always add up to the whole.
     */
    private void recordItemAttribution(RewardTransaction earning, List<OrderItem> items,
            BigDecimal totalPoints) {
        if (items == null || items.isEmpty()) {
            return;
        }
        BigDecimal totalAmount = items.stream()
                .map(OrderItem::amount)
                .filter(Amounts::isPositive)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!Amounts.isPositive(totalAmount)) {
            return;
        }

        BigDecimal allocated = BigDecimal.ZERO;
        List<OrderItem> billable = items.stream().filter(i -> Amounts.isPositive(i.amount()))
                .toList();
        for (int index = 0; index < billable.size(); index++) {
            OrderItem item = billable.get(index);
            boolean last = index == billable.size() - 1;
            BigDecimal itemPoints = last
                    ? totalPoints.subtract(allocated)
                    : Amounts.points(totalPoints.multiply(item.amount())
                            .divide(totalAmount, 6, RoundingMode.HALF_UP));
            allocated = allocated.add(itemPoints);
            transactionItems.save(new RewardTransactionItem(earning, item.orderItemId(),
                    item.amount(), itemPoints));
        }
    }

    // =============================================================================================
    // Availability (HELP.md sections 15, 16)
    // =============================================================================================

    /**
     * Applies an eligibility event to an order's pending rewards (HELP.md section 16).
     *
     * <p>A business event is the preferred trigger; the rule decides whether this particular event
     * satisfies it, so an ORDER_DELIVERED does not release a reward that is waiting for
     * STAY_COMPLETED.
     *
     * @return how many earnings became available
     */
    @Transactional
    public int applyEligibilityEvent(String orderId, EligibilityType trigger) {
        int released = 0;
        for (RewardTransaction earning : transactions.findEarningsForOrder(orderId)) {
            if (!earning.isPending()) {
                continue;
            }
            RewardRule rule = ruleOf(earning);
            if (rule != null && !rule.isSatisfiedBy(trigger)) {
                log.debug("Earning {} waits for {}, not {}", earning.getId(),
                        rule.getEligibilityType(), trigger);
                continue;
            }
            if (makeAvailable(earning)) {
                released++;
            }
        }
        return released;
    }

    /** Pending lots whose time-based delay has elapsed (HELP.md section 16). */
    @Transactional(readOnly = true)
    public List<String> findEarningsDueForAvailability(int batchSize) {
        return lots.findDueForAvailability(clock.now(), Limit.of(batchSize)).stream()
                .map(RewardLot::getRewardTransactionId)
                .distinct()
                .toList();
    }

    /**
     * Makes one earning available. Runs in its own transaction so that the availability sweep
     * cannot be blocked by a single problematic customer.
     */
    @Transactional
    public boolean makeAvailable(String rewardTransactionId) {
        return transactions.findById(rewardTransactionId)
                .filter(RewardTransaction::isPending)
                .map(this::makeAvailable)
                .orElse(false);
    }

    /**
     * PENDING -> AVAILABLE (HELP.md section 15): the points enter the spendable balance, are posted
     * to the ledger, and pay down any outstanding reward debt first (section 22).
     */
    private boolean makeAvailable(RewardTransaction earning) {
        Instant now = clock.now();
        WalletAccount account = accounts.rewardAccountFor(earning.getCustomerId());
        WalletBalance balance = accounts.lockBalance(account);

        List<RewardLot> earningLots = lots.findByRewardTransactionId(earning.getId());
        BigDecimal credited = BigDecimal.ZERO;
        for (RewardLot lot : earningLots) {
            if (!lot.isPending()) {
                continue;
            }
            lot.markAvailable(now);
            credited = credited.add(lot.getRemainingPoints());
        }

        earning.markAvailable(now);
        if (!Amounts.isPositive(credited)) {
            return true;
        }

        balance.credit(credited, now);
        ledger.postAgainstLiability(LedgerReferenceType.REWARD_TRANSACTION, earning.getId(),
                LedgerTransactionType.REWARD_AVAILABLE, account, Direction.CREDIT, credited);

        BigDecimal settled = settleDebt(account, balance, earningLots, earning.getId(), credited,
                now);

        events.publish(WalletEventType.REWARD_AVAILABLE, EventEnvelope.newPayload()
                .with("customerId", earning.getCustomerId())
                .with("orderId", earning.getOrderId())
                .with("rewardTransactionId", earning.getId())
                .with("points", credited)
                .with("appliedToDebt", Amounts.isPositive(settled) ? settled : null)
                .with("availablePoints", balance.getAvailableAmount())
                .build());

        log.info("Reward {} became available: {} points for customer {}{}", earning.getId(),
                credited, earning.getCustomerId(),
                Amounts.isPositive(settled) ? " (" + settled + " applied to debt)" : "");
        return true;
    }

    /**
     * Applies newly available points to outstanding reward debt (HELP.md section 22).
     *
     * <p>The worked example: a 700 point reward against 400 of debt leaves 300 available and no
     * debt. The points are taken out of the lots as well as the balance, because the lots are the
     * record of what is actually spendable and the two must agree.
     *
     * @return how much debt was paid
     */
    private BigDecimal settleDebt(WalletAccount account, WalletBalance balance,
            List<RewardLot> candidateLots, String triggeringTransactionId, BigDecimal credited,
            Instant now) {

        if (!balance.hasDebt()) {
            return Amounts.ZERO_POINTS;
        }
        BigDecimal applied = balance.settleDebtFrom(credited, now);
        if (!Amounts.isPositive(applied)) {
            return Amounts.ZERO_POINTS;
        }

        takeFromLots(candidateLots, applied, now);

        RewardTransaction settlement = RewardTransaction.debtSettlement(account.getCustomerId(),
                triggeringTransactionId, applied, now);
        transactions.save(settlement);
        ledger.postAgainstLiability(LedgerReferenceType.REWARD_TRANSACTION, settlement.getId(),
                LedgerTransactionType.REWARD_DEBT_SETTLEMENT, account, Direction.DEBIT, applied);
        return applied;
    }

    // =============================================================================================
    // FEFO consumption, used by redemption (HELP.md section 19)
    // =============================================================================================

    /**
     * Takes {@code points} out of the customer's lots, earliest expiring first (HELP.md section 19).
     *
     * <p>FEFO rather than FIFO: spending the lot that expires in January before the one that expires
     * in June is what stops points being lost that the customer could have used. The allocation is
     * recorded by the caller as {@code redemption_item} rows so that a release can put the points
     * back into the very lots they came from (section 36).
     *
     * <p>Requires the caller to already hold the customer's balance lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LotAllocation> allocateFefo(String customerId, BigDecimal points, Instant now) {
        List<LotAllocation> allocations = new ArrayList<>();
        BigDecimal outstanding = points;

        for (RewardLot lot : lots.findConsumableFefo(customerId, now)) {
            if (!Amounts.isPositive(outstanding)) {
                break;
            }
            BigDecimal taken = lot.take(Amounts.min(lot.getRemainingPoints(), outstanding), now);
            if (Amounts.isPositive(taken)) {
                allocations.add(new LotAllocation(lot, taken));
                outstanding = outstanding.subtract(taken);
            }
        }

        if (Amounts.isPositive(outstanding)) {
            // The balance said there were enough points but the lots disagree. Refusing is the only
            // safe answer: the alternative is spending points that no lot backs.
            throw WalletException.of(ErrorCode.INSUFFICIENT_REWARD_BALANCE,
                    "customer %s is short %s points of the %s requested", customerId, outstanding,
                    points);
        }
        return allocations;
    }

    /** Puts released points back into the exact lots they came from (HELP.md sections 11, 36). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restoreLots(List<RedemptionItem> items, Instant now) {
        for (RedemptionItem item : items) {
            lots.findById(item.getRewardLotId())
                    .orElseThrow(() -> WalletException.of(ErrorCode.INTERNAL_ERROR,
                            "redemption item %s references missing lot %s", item.getId(),
                            item.getRewardLotId()))
                    .restore(item.getPoints(), now);
        }
    }

    /**
     * Credits points back after a committed redemption was reversed (HELP.md section 51).
     *
     * <p>A fresh AVAILABLE lot rather than an edit of the consumed one: the original consumption
     * really happened and stays on the record, and a reversal is always a new transaction
     * (section 60, rule 5).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RewardTransaction creditBackRedeemedPoints(String customerId, String orderId,
            String redemptionId, BigDecimal points, WalletAccount account, WalletBalance balance,
            Instant now) {

        RewardTransaction reversal = RewardTransaction.redemptionReversal(customerId, orderId,
                redemptionId, points, expiryOfOrderEarnings(orderId), now);
        transactions.saveAndFlush(reversal);

        RewardLot lot = lots.save(RewardLot.available(reversal, now));
        balance.credit(points, now);
        ledger.postAgainstLiability(LedgerReferenceType.REDEMPTION, redemptionId,
                LedgerTransactionType.REDEMPTION_REVERSAL, account, Direction.CREDIT, points);

        settleDebt(account, balance, List.of(lot), reversal.getId(), points, now);
        return reversal;
    }

    // =============================================================================================
    // Reversal (HELP.md sections 20, 21, 22, 51, 52, 53)
    // =============================================================================================

    /**
     * Reverses an order's rewards because the order was cancelled (HELP.md section 51).
     *
     * <p>Pending rewards are simply voided; available ones are reversed with a new transaction and,
     * where the points were already spent, reward debt.
     *
     * <p>Idempotent by status: a second ORDER_CANCELLED finds the earning already VOIDED or
     * REVERSED and does nothing, which is what makes three deliveries produce one reversal
     * (HELP.md section 62).
     *
     * @return points reversed across the order
     */
    @Transactional
    public BigDecimal reverseForCancellation(String orderId) {
        BigDecimal total = BigDecimal.ZERO;
        for (RewardTransaction earning : transactions.findEarningsForOrder(orderId)) {
            total = total.add(reverse(earning, earning.getPoints(),
                    DedupeKeys.cancellationReversal(orderId), "ORDER_CANCELLED"));
        }
        return total;
    }

    /**
     * Reverses part of an order's reward because money was refunded (HELP.md sections 52, 53).
     *
     * <p>How much is reversed is the rule's decision, not this method's: a refund does not
     * automatically mean a 100% reward reversal.
     *
     * @return points reversed across the order
     */
    @Transactional
    public BigDecimal reverseForRefund(RefundRequest request) {
        BigDecimal total = BigDecimal.ZERO;
        for (RewardTransaction earning : transactions.findEarningsForOrder(request.orderId())) {
            BigDecimal points = refundReversalPoints(earning, request);
            if (!Amounts.isPositive(points)) {
                continue;
            }
            total = total.add(reverse(earning, points,
                    DedupeKeys.refundReversal(request.orderId(), request.refundId()),
                    "ORDER_REFUNDED"));
        }
        return total;
    }

    /**
     * How many points a refund claws back, per {@code reward_rule.refund_reversal_mode}
     * (HELP.md section 52).
     */
    private BigDecimal refundReversalPoints(RewardTransaction earning, RefundRequest request) {
        RewardRule rule = ruleOf(earning);
        RefundReversalMode mode = rule == null ? RefundReversalMode.PROPORTIONAL
                : rule.getRefundReversalMode();

        return switch (mode) {
            case NONE -> Amounts.ZERO_POINTS;
            case FULL -> earning.getPoints();
            case PROPORTIONAL -> proportionalRefundPoints(earning, request);
        };
    }

    private BigDecimal proportionalRefundPoints(RewardTransaction earning, RefundRequest request) {
        // Item-level attribution is more accurate than a share of the order total, and is the whole
        // reason reward_transaction_item exists (HELP.md section 53).
        if (!request.refundedOrderItemIds().isEmpty()) {
            List<RewardTransactionItem> items =
                    transactionItems.findByRewardTransactionId(earning.getId());
            if (!items.isEmpty()) {
                return items.stream()
                        .filter(item -> request.refundedOrderItemIds()
                                .contains(item.getOrderItemId()))
                        .map(RewardTransactionItem::getPoints)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }
            log.debug("No item attribution for earning {}; falling back to amount ratio",
                    earning.getId());
        }

        BigDecimal orderAmount = earning.getOrderAmount();
        BigDecimal refundAmount = request.refundAmount();
        if (refundAmount == null || orderAmount == null || !Amounts.isPositive(orderAmount)) {
            // Nothing to prorate against: treat it as a full refund rather than silently doing
            // nothing, because "money went back and reward did not" is the worse failure.
            return earning.getPoints();
        }

        BigDecimal ratio = Amounts.min(refundAmount, orderAmount)
                .divide(orderAmount, 8, RoundingMode.HALF_UP);
        return Amounts.points(earning.getPoints().multiply(ratio));
    }

    /**
     * The one place points are taken back (HELP.md sections 20, 21, 22).
     *
     * <p>Splits the reversal three ways, because the three cases are genuinely different:
     *
     * <pre>
     * still in the lot   -> take it back and post it to the ledger
     * already redeemed   -> reward debt; nothing to post, the points are gone
     * already expired    -> nothing at all; the wallet wrote these off already
     * </pre>
     *
     * <p>Counting expired points as debt would charge the customer twice for points they never got
     * to use, so the redemption items are consulted to tell "spent" from "expired" apart. Both leave
     * the lot with the same remaining balance, which is why the distinction cannot be inferred from
     * the lot alone.
     *
     * @return the points reversed, which is zero when there is nothing left to reverse
     */
    private BigDecimal reverse(RewardTransaction earning, BigDecimal requested, String dedupeKey,
            String reason) {

        Instant now = clock.now();

        // This exact reversal is already on record. The common case for a repeated cancellation is
        // caught by the status checks below, but a refund is keyed on the refund rather than the
        // order, so a redelivered refund lands here.
        if (transactions.existsByDedupeKey(dedupeKey)) {
            log.debug("Reversal {} already recorded for reward {}", dedupeKey, earning.getId());
            return Amounts.ZERO_POINTS;
        }

        BigDecimal alreadyReversed = transactions.sumReversedPoints(earning.getId());
        BigDecimal reversible = earning.getPoints().subtract(alreadyReversed);
        BigDecimal toReverse = Amounts.min(requested, reversible);
        if (!Amounts.isPositive(toReverse)) {
            log.debug("Reward {} has nothing left to reverse ({} of {} already reversed)",
                    earning.getId(), alreadyReversed, earning.getPoints());
            return Amounts.ZERO_POINTS;
        }

        boolean whole = !Amounts.gt(reversible, toReverse);
        List<RewardLot> earningLots = lots.findByRewardTransactionId(earning.getId());

        // Reversed before ever becoming available: no balance change is required, and nothing was
        // ever posted to the ledger to unwind (HELP.md section 20).
        if (earning.isPending()) {
            if (whole) {
                // Section 20's case exactly: PENDING -> VOIDED. No reversal row, because the
                // earning itself carries the outcome and a REVERSE row beside a VOIDED earning
                // would double-count in any report that sums both.
                earningLots.stream().filter(RewardLot::isPending)
                        .forEach(lot -> lot.markVoided(now));
                earning.markVoided(now);
            } else {
                // A partial refund of a not-yet-available reward. The pending lot shrinks, so
                // whatever becomes available later is already net of the refund. This needs a
                // reversal row: there is no other place to record that part of it went away.
                takeFromLots(earningLots, toReverse, now);
                saveReversal(earning, toReverse, dedupeKey);
            }
            log.info("Reversed {} points of pending reward {} after {} ({})", toReverse,
                    earning.getId(), reason, whole ? "voided" : "partial");
            publishReversal(earning, toReverse, Amounts.ZERO_POINTS, reason, whole);
            return toReverse;
        }

        WalletAccount account = accounts.rewardAccountFor(earning.getCustomerId());
        WalletBalance balance = accounts.lockBalance(account);

        BigDecimal reclaimed = takeFromLots(earningLots, toReverse, now);
        BigDecimal shortfall = toReverse.subtract(reclaimed);
        BigDecimal debt = Amounts.min(shortfall, spentFromLots(earningLots));

        RewardTransaction reversal = saveReversal(earning, toReverse, dedupeKey);

        if (Amounts.isPositive(reclaimed)) {
            balance.debit(reclaimed, now);
            ledger.postAgainstLiability(LedgerReferenceType.REWARD_TRANSACTION, reversal.getId(),
                    LedgerTransactionType.REWARD_REVERSAL, account, Direction.DEBIT, reclaimed);
        }
        if (Amounts.isPositive(debt)) {
            balance.addDebt(debt, now);
        }
        if (whole) {
            earning.markReversed(now);
        }

        log.info("Reversed {} points of reward {} after {}: {} reclaimed, {} became debt",
                toReverse, earning.getId(), reason, reclaimed, debt);
        publishReversal(earning, toReverse, debt, reason, false);
        return toReverse;
    }

    private RewardTransaction saveReversal(RewardTransaction earning, BigDecimal points,
            String dedupeKey) {
        try {
            return transactions.saveAndFlush(
                    RewardTransaction.reversalOf(earning, points, dedupeKey, clock.now()));
        } catch (DataIntegrityViolationException e) {
            // uk_reward_transaction_dedupe. Lost a race with a concurrent delivery of the same
            // fact. Rolling back is the only safe response: the failed insert has already poisoned
            // the persistence context, and the retry will take the existsByDedupeKey path above.
            throw new DuplicateReversalException(dedupeKey, e);
        }
    }

    private void publishReversal(RewardTransaction earning, BigDecimal points, BigDecimal debt,
            String reason, boolean voided) {
        events.publish(WalletEventType.REWARD_REVERSED, EventEnvelope.newPayload()
                .with("customerId", earning.getCustomerId())
                .with("orderId", earning.getOrderId())
                .with("rewardTransactionId", earning.getId())
                .with("points", points)
                .with("rewardDebt", Amounts.isPositive(debt) ? debt : null)
                .with("voided", voided)
                .with("reason", reason)
                .build());
    }

    /**
     * Takes up to {@code requested} points out of the given lots, oldest first.
     *
     * @return the total actually taken, which is less than requested when the lots are short
     */
    private BigDecimal takeFromLots(List<RewardLot> candidates, BigDecimal requested, Instant now) {
        BigDecimal taken = BigDecimal.ZERO;
        for (RewardLot lot : candidates) {
            BigDecimal outstanding = requested.subtract(taken);
            if (!Amounts.isPositive(outstanding)) {
                break;
            }
            taken = taken.add(lot.take(Amounts.min(lot.getRemainingPoints(), outstanding), now));
        }
        return taken;
    }

    /**
     * Points from these lots that a redemption is holding or has consumed. Reserved points count as
     * spent: the reservation may yet commit, and treating them as reclaimable would let the same
     * points back two ways.
     */
    private BigDecimal spentFromLots(List<RewardLot> candidates) {
        BigDecimal spent = BigDecimal.ZERO;
        for (RewardLot lot : candidates) {
            spent = spent
                    .add(redemptionItems.sumPointsForLotInState(lot.getId(),
                            RedemptionStatus.COMPLETED))
                    .add(redemptionItems.sumPointsForLotInState(lot.getId(),
                            RedemptionStatus.RESERVED));
        }
        return spent;
    }

    // =============================================================================================
    // Expiry (HELP.md section 18)
    // =============================================================================================

    @Transactional(readOnly = true)
    public List<String> findLotsDueForExpiry(int batchSize) {
        return lots.findExpired(clock.now(), Limit.of(batchSize)).stream()
                .map(RewardLot::getId)
                .toList();
    }

    /**
     * Writes off one expired lot. Its own transaction, so a single bad row cannot stall the sweep.
     */
    @Transactional
    public boolean expireLot(String lotId) {
        Instant now = clock.now();
        RewardLot lot = lots.findById(lotId).orElse(null);
        if (lot == null || !lot.isAvailable() || !lot.isExpiredAt(now) || !lot.hasRemaining()) {
            return false;
        }

        WalletAccount account = accounts.rewardAccountFor(lot.getCustomerId());
        WalletBalance balance = accounts.lockBalance(account);

        BigDecimal lost = lot.expire(now);
        if (!Amounts.isPositive(lost)) {
            return false;
        }

        balance.debit(lost, now);
        RewardTransaction expiry = RewardTransaction.expiry(lot.getCustomerId(), lot.getId(), lost,
                now);
        transactions.save(expiry);
        ledger.postAgainstLiability(LedgerReferenceType.REWARD_LOT, lot.getId(),
                LedgerTransactionType.REWARD_EXPIRY, account, Direction.DEBIT, lost);

        // One lot per earning, so the earning is done once its lot is.
        transactions.findById(lot.getRewardTransactionId())
                .filter(RewardTransaction::isAvailable)
                .ifPresent(earning -> earning.markExpired(now));

        events.publish(WalletEventType.REWARD_EXPIRED, EventEnvelope.newPayload()
                .with("customerId", lot.getCustomerId())
                .with("rewardLotId", lot.getId())
                .with("points", lost)
                .with("expiresAt", lot.getExpiresAt())
                .build());

        log.info("Lot {} expired, writing off {} points for customer {}", lot.getId(), lost,
                lot.getCustomerId());
        return true;
    }

    // =============================================================================================
    // Queries
    // =============================================================================================

    @Transactional(readOnly = true)
    public BigDecimal availablePoints(String customerId) {
        return lots.sumAvailablePoints(customerId, clock.now());
    }

    @Transactional(readOnly = true)
    public BigDecimal pendingPoints(String customerId) {
        return lots.sumPendingPoints(customerId);
    }

    @Transactional(readOnly = true)
    public List<RewardLot> lotsOf(String customerId) {
        return lots.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    /** The customer's reward history (HELP.md section 46, {@code GET /wallet/rewards/history}). */
    @Transactional(readOnly = true)
    public Page<RewardTransaction> historyOf(String customerId, Pageable pageable) {
        return transactions.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
    }

    /** Whether a committed redemption has already been credited back (HELP.md section 51). */
    @Transactional(readOnly = true)
    public boolean hasRedemptionReversal(String redemptionId) {
        return transactions.existsByDedupeKey(DedupeKeys.redemptionReversal(redemptionId));
    }

    /**
     * The rule an earning was created under, so a later reversal uses the same terms the reward was
     * granted on rather than today's rule.
     */
    private RewardRule ruleOf(RewardTransaction earning) {
        return earning.getRuleId() == null ? null : rules.findById(earning.getRuleId()).orElse(null);
    }

    public RewardRule resolveRule(String orderType) {
        String type = orderType == null || orderType.isBlank()
                ? RewardRule.DEFAULT_ORDER_TYPE
                : orderType;
        return rules.findActiveByOrderType(type)
                .or(() -> rules.findActiveByOrderType(RewardRule.DEFAULT_ORDER_TYPE))
                .orElseThrow(() -> WalletException.of(ErrorCode.ORDER_NOT_ELIGIBLE,
                        "no active reward rule for order type '%s'", type));
    }

    private Instant timeBasedAvailability(RewardRule rule, Instant now) {
        if (!rule.getEligibilityType().isTimeBased() || rule.getEligibilityDays() == null) {
            // Waiting on a business event instead, which may arrive at any time (section 16).
            return null;
        }
        return now.plus(Duration.ofDays(rule.getEligibilityDays()));
    }

    private Instant expiryFor(RewardRule rule, Instant now) {
        return rule.getExpirationDays() == null ? null
                : now.plus(Duration.ofDays(rule.getExpirationDays()));
    }

    /** The expiry the order's own rewards carry, reused when points are credited back. */
    private Instant expiryOfOrderEarnings(String orderId) {
        return transactions.findEarningsForOrder(orderId).stream()
                .map(RewardTransaction::getExpiresAt)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    // =============================================================================================

    /**
     * @param items optional item breakdown, enabling accurate partial refunds later (section 53)
     */
    public record EarnRequest(
            String customerId,
            String orderId,
            String orderType,
            BigDecimal orderAmount,
            String currency,
            List<OrderItem> items) {

        public static EarnRequest of(String customerId, String orderId, String orderType,
                BigDecimal orderAmount, String currency) {
            return new EarnRequest(customerId, orderId, orderType, orderAmount, currency, List.of());
        }
    }

    public record OrderItem(String orderItemId, BigDecimal amount) {
    }

    /**
     * @param refundedOrderItemIds when present, the reversal is attributed item by item rather than
     *                             prorated across the order (HELP.md section 53)
     */
    public record RefundRequest(
            String orderId,
            String refundId,
            BigDecimal refundAmount,
            List<String> refundedOrderItemIds) {

        public RefundRequest {
            refundedOrderItemIds = refundedOrderItemIds == null ? List.of()
                    : List.copyOf(refundedOrderItemIds);
        }
    }

    /** One lot's contribution to a redemption. */
    public record LotAllocation(RewardLot lot, BigDecimal points) {
    }

    /**
     * Signals that this exact reversal is already on record. Raised instead of returning quietly so
     * that the caller's transaction rolls back rather than committing a half-applied reversal: the
     * failed insert has already poisoned the persistence context.
     */
    public static class DuplicateReversalException extends RuntimeException {

        public DuplicateReversalException(String dedupeKey, Throwable cause) {
            super("reversal " + dedupeKey + " already exists", cause);
        }
    }
}
