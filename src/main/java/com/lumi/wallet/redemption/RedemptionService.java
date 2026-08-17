package com.lumi.wallet.redemption;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lumi.wallet.account.WalletAccount;
import com.lumi.wallet.account.WalletAccountService;
import com.lumi.wallet.account.WalletBalance;
import com.lumi.wallet.common.Amounts;
import com.lumi.wallet.common.ErrorCode;
import com.lumi.wallet.common.WalletException;
import com.lumi.wallet.config.WalletProperties;
import com.lumi.wallet.event.EventEnvelope;
import com.lumi.wallet.event.outbound.WalletEventPublisher;
import com.lumi.wallet.event.outbound.WalletEventType;
import com.lumi.wallet.ledger.Direction;
import com.lumi.wallet.ledger.LedgerReferenceType;
import com.lumi.wallet.ledger.LedgerService;
import com.lumi.wallet.ledger.LedgerTransactionType;
import com.lumi.wallet.reward.RewardProgram;
import com.lumi.wallet.reward.RewardProgramRepository;
import com.lumi.wallet.reward.RewardService;
import com.lumi.wallet.reward.RewardTransaction;
import com.lumi.wallet.reward.RewardTransactionRepository;
import com.lumi.wallet.support.WalletClock;

/**
 * Quote, reserve, commit and release (HELP.md sections 7 to 11).
 *
 * <p>The wallet's part of a payment is only ever the reward side: it reserves value and later
 * confirms or returns it. The external payment is the Payment Service's business and this class
 * never talks to it (HELP.md sections 4, 61).
 *
 * <h2>Why the sequence is quote, then reserve, then commit</h2>
 *
 * <p>A quote moves nothing. If opening a checkout page deducted points, a customer who closed the
 * browser would simply lose them (section 8). A reservation moves AVAILABLE to LOCKED, which is
 * reversible. Only a commit consumes points for good, and only after the money has actually been
 * taken.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Every mutating method takes the customer's balance row lock before reading the balance it is
 * about to check (sections 41, 42). That is what makes {@code available >= requested} atomic: of two
 * simultaneous requests to redeem 8,000 of 10,000 points, the second one waits at the lock and then
 * fails its own availability check against the 2,000 that are left.
 */
@Service
public class RedemptionService {

    private static final Logger log = LoggerFactory.getLogger(RedemptionService.class);

    private final RedemptionRepository redemptions;
    private final RedemptionItemRepository items;
    private final RedemptionQuoteRepository quotes;
    private final RewardProgramRepository programs;
    private final RewardTransactionRepository rewardTransactions;
    private final RewardService rewards;
    private final WalletAccountService accounts;
    private final LedgerService ledger;
    private final WalletEventPublisher events;
    private final WalletProperties properties;
    private final WalletClock clock;

    public RedemptionService(RedemptionRepository redemptions, RedemptionItemRepository items,
            RedemptionQuoteRepository quotes, RewardProgramRepository programs,
            RewardTransactionRepository rewardTransactions, RewardService rewards,
            WalletAccountService accounts, LedgerService ledger, WalletEventPublisher events,
            WalletProperties properties, WalletClock clock) {
        this.redemptions = redemptions;
        this.items = items;
        this.quotes = quotes;
        this.programs = programs;
        this.rewardTransactions = rewardTransactions;
        this.rewards = rewards;
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    // =============================================================================================
    // Quote (HELP.md sections 7, 8, 47)
    // =============================================================================================

    /**
     * Calculates what the wallet could contribute to an order, changing nothing (HELP.md section 7).
     *
     * <p>The wallet contribution is the smallest of three limits: what the caller asked for, what the
     * programme allows of one order, and what the customer's points are actually worth. Leaving any
     * of them out produces a quote the subsequent reservation would have to reject.
     */
    @Transactional
    public RedemptionQuote quote(QuoteRequest request) {
        Instant now = clock.now();
        accounts.requireMonetaryAsset(request.currency());
        RewardProgram program = activeProgram();

        BigDecimal orderAmount = requirePositiveMoney(request.orderAmount(), "orderAmount");
        BigDecimal pointsAvailable = rewards.availablePoints(request.customerId());

        BigDecimal programCap = Amounts.money(orderAmount
                .multiply(program.getMaxRedemptionPercent())
                .divide(BigDecimal.valueOf(100), Amounts.MONEY_SCALE, RoundingMode.FLOOR));
        BigDecimal pointsCap = Amounts.moneyFor(pointsAvailable,
                program.getPointsPerCurrencyUnit());
        BigDecimal maxWalletAmount = Amounts.min(programCap, pointsCap);

        BigDecimal walletAmount = request.requestedWalletAmount() == null
                // Section 7: with no requested amount, the wallet returns the maximum redeemable.
                ? maxWalletAmount
                : Amounts.min(Amounts.money(request.requestedWalletAmount()), maxWalletAmount);
        if (Amounts.isNegative(walletAmount)) {
            walletAmount = Amounts.ZERO_MONEY;
        }

        BigDecimal pointsRequired = Amounts.isPositive(walletAmount)
                ? Amounts.pointsFor(walletAmount, program.getPointsPerCurrencyUnit())
                : Amounts.ZERO_POINTS;

        RedemptionQuote quote = RedemptionQuote.of(request.customerId(), request.orderId(), program,
                request.currency(), orderAmount, walletAmount, orderAmount.subtract(walletAmount),
                pointsRequired, pointsAvailable, now.plus(properties.quoteTtl()), now);
        quotes.save(quote);

        log.debug("Quoted {} of order {} ({} {}) as {} points for customer {}", walletAmount,
                request.orderId(), orderAmount, request.currency(), pointsRequired,
                request.customerId());
        return quote;
    }

    // =============================================================================================
    // Reserve (HELP.md sections 9, 43, 44)
    // =============================================================================================

    /**
     * Moves points from AVAILABLE to LOCKED for a checkout (HELP.md section 9).
     *
     * <p>Everything is revalidated here, and the quote is not trusted (section 43). A quote issued
     * when the customer had 10,000 points still says 10,000 after another device has spent 8,000, so
     * the only figure that decides the outcome is the balance read under the lock a few lines below.
     *
     * <p>The points the client sends are likewise not trusted (section 44): they are recomputed from
     * the wallet amount at the programme's rate, and a client whose number disagrees is told so
     * rather than being quietly overridden — its view of the order is stale and the caller needs to
     * know.
     */
    @Transactional
    public Redemption reserve(ReserveRequest request) {
        Instant now = clock.now();
        accounts.requireMonetaryAsset(request.currency());
        RewardProgram program = activeProgram();

        BigDecimal walletAmount = requirePositiveMoney(request.walletAmount(), "walletAmount");
        BigDecimal pointsRequired = Amounts.pointsFor(walletAmount,
                program.getPointsPerCurrencyUnit());

        if (request.points() != null && !Amounts.eq(request.points(), pointsRequired)) {
            throw WalletException.of(ErrorCode.QUOTE_MISMATCH,
                    "%s points were submitted but %s of %s costs %s points", request.points(),
                    walletAmount, request.currency(), pointsRequired);
        }

        WalletAccount account = accounts.rewardAccountFor(request.customerId());
        accounts.requireActive(account);

        // The lock. Taken before the balance is read, and held for the rest of the transaction.
        WalletBalance balance = accounts.lockBalance(account);

        validateQuote(request, walletAmount, now);
        requireNoLiveRedemption(request.orderId());

        Redemption redemption = Redemption.create(request.customerId(), request.orderId(),
                redemptions.maxSequenceForOrder(request.orderId()) + 1, request.currency(),
                walletAmount, pointsRequired, request.quoteId(),
                now.plus(properties.reservationTtl()), now);
        redemptions.saveAndFlush(redemption);

        // Authoritative check (section 42): throws INSUFFICIENT_REWARD_BALANCE when the points are
        // not there, whatever the quote said.
        balance.reserve(pointsRequired, now);

        // Which lots paid, earliest expiring first, recorded so a release can put the points back
        // exactly where they came from (sections 19, 36).
        rewards.allocateFefo(request.customerId(), pointsRequired, now)
                .forEach(allocation -> items.save(
                        new RedemptionItem(redemption, allocation.lot(), allocation.points())));

        redemption.reserve();

        events.publish(WalletEventType.REDEMPTION_RESERVED, EventEnvelope.newPayload()
                .with("redemptionId", redemption.getId())
                .with("customerId", redemption.getCustomerId())
                .with("orderId", redemption.getOrderId())
                .with("currency", redemption.getCurrency())
                .with("walletAmount", redemption.getWalletAmount())
                .with("points", redemption.getPoints())
                .with("expiresAt", redemption.getExpiresAt())
                .build());

        log.info("Reserved {} points ({} {}) for order {} as redemption {}", pointsRequired,
                walletAmount, request.currency(), request.orderId(), redemption.getId());
        return redemption;
    }

    /**
     * Checks the quote is one the wallet issued, for this customer and order, and still fresh
     * (HELP.md section 43). The figures in it are informational; only its <em>terms</em> are binding.
     */
    private void validateQuote(ReserveRequest request, BigDecimal walletAmount, Instant now) {
        if (request.quoteId() == null || request.quoteId().isBlank()) {
            // Reserving without a quote is allowed: the balance check below is the real guard. The
            // programme's per-order cap cannot be revalidated without an order amount, though, which
            // is the reason a caller should normally quote first.
            return;
        }
        RedemptionQuote quote = quotes.findById(request.quoteId())
                .orElseThrow(() -> WalletException.of(ErrorCode.QUOTE_NOT_FOUND,
                        "quote %s does not exist", request.quoteId()));

        if (!quote.getCustomerId().equals(request.customerId())
                || !quote.getOrderId().equals(request.orderId())
                || !quote.getCurrency().equals(request.currency())) {
            throw WalletException.of(ErrorCode.QUOTE_MISMATCH,
                    "quote %s was issued for a different customer, order or currency",
                    quote.getId());
        }
        if (quote.hasExpiredAt(now)) {
            throw WalletException.of(ErrorCode.QUOTE_EXPIRED, "quote %s expired at %s",
                    quote.getId(), quote.getExpiresAt());
        }
        if (Amounts.gt(walletAmount, quote.getWalletAmount())) {
            throw WalletException.of(ErrorCode.QUOTE_MISMATCH,
                    "quote %s allows at most %s but %s was requested", quote.getId(),
                    quote.getWalletAmount(), walletAmount);
        }
    }

    /**
     * One live redemption per order (HELP.md section 45, {@code DUPLICATE_ORDER_REDEMPTION}).
     *
     * <p>Checked under the balance lock rather than by a unique constraint on the order id, because a
     * released reservation must leave the order free to try again — a card that failed once may
     * succeed on the second attempt (section 35).
     */
    private void requireNoLiveRedemption(String orderId) {
        List<Redemption> live = redemptions.findLiveForOrder(orderId);
        if (!live.isEmpty()) {
            Redemption existing = live.get(0);
            throw WalletException.of(ErrorCode.DUPLICATE_ORDER_REDEMPTION,
                    "order %s already has redemption %s in state %s", orderId, existing.getId(),
                    existing.getStatus());
        }
    }

    // =============================================================================================
    // Commit (HELP.md section 10)
    // =============================================================================================

    /**
     * LOCKED -> REDEEMED (HELP.md section 10): the points are permanently consumed, and this is the
     * first and only point at which the redemption reaches the ledger.
     *
     * <p>A second commit returns the completed redemption unchanged. That is not a state transition —
     * section 12 forbids COMPLETED -> COMPLETED — it is the replay section 40 asks for, and it
     * produces no second ledger posting.
     */
    @Transactional
    public Redemption commit(String redemptionId) {
        Instant now = clock.now();
        Redemption redemption = require(redemptionId);

        if (redemption.isCompleted()) {
            log.info("Redemption {} is already committed; returning it unchanged", redemptionId);
            return redemption;
        }
        if (!redemption.isReserved()) {
            throw WalletException.of(ErrorCode.INVALID_REDEMPTION_STATE,
                    "redemption %s is %s and cannot be committed", redemptionId,
                    redemption.getStatus());
        }
        if (redemption.hasExpiredAt(now)) {
            // The reservation timed out (section 13). The points may already have been released and
            // spent elsewhere, so committing now would be spending them twice.
            throw WalletException.of(ErrorCode.REDEMPTION_EXPIRED,
                    "redemption %s expired at %s", redemptionId, redemption.getExpiresAt());
        }

        WalletAccount account = accounts.rewardAccountFor(redemption.getCustomerId());
        WalletBalance balance = accounts.lockBalance(account);

        balance.consumeLocked(redemption.getPoints(), now);
        redemption.complete(now);

        RewardTransaction consumed = RewardTransaction.redemption(redemption.getCustomerId(),
                redemption.getOrderId(), redemption.getId(), redemption.getPoints(),
                redemption.getCurrency(), now);
        rewardTransactions.save(consumed);

        // uk_ledger_transaction_reference makes this posting unrepeatable even if two threads get
        // here (sections 40, 60.8).
        ledger.postAgainstLiability(LedgerReferenceType.REDEMPTION, redemption.getId(),
                LedgerTransactionType.REDEMPTION_COMMIT, account, Direction.DEBIT,
                redemption.getPoints());

        events.publish(WalletEventType.REDEMPTION_COMPLETED, EventEnvelope.newPayload()
                .with("redemptionId", redemption.getId())
                .with("customerId", redemption.getCustomerId())
                .with("orderId", redemption.getOrderId())
                .with("currency", redemption.getCurrency())
                .with("walletAmount", redemption.getWalletAmount())
                .with("points", redemption.getPoints())
                .build());

        log.info("Committed redemption {}: {} points consumed for order {}", redemption.getId(),
                redemption.getPoints(), redemption.getOrderId());
        return redemption;
    }

    // =============================================================================================
    // Release (HELP.md sections 11, 13, 50)
    // =============================================================================================

    /**
     * LOCKED -> AVAILABLE (HELP.md section 11).
     *
     * <p>A second release returns the released redemption unchanged, so the points are never credited
     * twice (section 40). Releasing a committed redemption is refused outright: section 12 lists
     * COMPLETED -> RELEASED as invalid, and honouring it would hand back points that were spent.
     *
     * <p>Nothing is posted to the ledger, because nothing left the account: the reservation moved
     * points between two columns of the same balance, and the ledger records movements of value, not
     * of intent.
     */
    @Transactional
    public Redemption release(String redemptionId, String reason) {
        Instant now = clock.now();
        Redemption redemption = require(redemptionId);

        if (redemption.isReleased()) {
            log.info("Redemption {} is already released; returning it unchanged", redemptionId);
            return redemption;
        }
        if (!redemption.isReserved()) {
            throw WalletException.of(ErrorCode.INVALID_REDEMPTION_STATE,
                    "redemption %s is %s and cannot be released", redemptionId,
                    redemption.getStatus());
        }

        WalletAccount account = accounts.rewardAccountFor(redemption.getCustomerId());
        WalletBalance balance = accounts.lockBalance(account);

        balance.releaseLocked(redemption.getPoints(), now);
        rewards.restoreLots(items.findByRedemptionId(redemption.getId()), now);
        redemption.release(now);

        events.publish(WalletEventType.REDEMPTION_RELEASED, EventEnvelope.newPayload()
                .with("redemptionId", redemption.getId())
                .with("customerId", redemption.getCustomerId())
                .with("orderId", redemption.getOrderId())
                .with("points", redemption.getPoints())
                .with("reason", reason)
                .build());

        log.info("Released redemption {}: {} points returned to customer {} ({})",
                redemption.getId(), redemption.getPoints(), redemption.getCustomerId(), reason);
        return redemption;
    }

    /** Reservations whose TTL elapsed with no commit or release (HELP.md sections 13, 50). */
    @Transactional(readOnly = true)
    public List<String> findExpiredReservationIds(int batchSize) {
        return redemptions.findExpiredReservations(clock.now(), Limit.of(batchSize)).stream()
                .map(Redemption::getId)
                .toList();
    }

    /**
     * Releases one timed-out reservation.
     *
     * <p>Goes through {@link #release} rather than crediting the balance directly, because section 13
     * requires the expiry process to use the same state transition rules and to "never blindly add
     * points". A reservation that was committed in the meantime is therefore refused here, not
     * silently paid out.
     */
    @Transactional
    public boolean releaseExpired(String redemptionId) {
        Redemption redemption = redemptions.findById(redemptionId).orElse(null);
        if (redemption == null || !redemption.isReserved()) {
            return false;
        }
        release(redemptionId, "RESERVATION_EXPIRED");
        return true;
    }

    // =============================================================================================
    // Order lifecycle reactions (HELP.md section 51)
    // =============================================================================================

    /**
     * Commits whatever this order was holding, driven by a payment success event
     * (HELP.md sections 10, 48).
     *
     * <p>Duplicated deliberately with the API's commit endpoint: a checkout that crashes after the
     * card was charged still gets its redemption committed, and whichever path arrives second finds
     * the redemption already COMPLETED and does nothing.
     */
    @Transactional
    public int commitReservedForOrder(String orderId) {
        int committed = 0;
        for (Redemption redemption : redemptions.findReservedForOrder(orderId)) {
            commit(redemption.getId());
            committed++;
        }
        return committed;
    }

    /** Releases whatever this order was holding, e.g. after a payment failure or a cancellation. */
    @Transactional
    public int releaseReservedForOrder(String orderId, String reason) {
        int released = 0;
        for (Redemption redemption : redemptions.findReservedForOrder(orderId)) {
            release(redemption.getId(), reason);
            released++;
        }
        return released;
    }

    /**
     * Gives back the points a committed redemption consumed, because the order it paid for was
     * cancelled (HELP.md section 51).
     *
     * <p>The redemption itself is left COMPLETED and is not deleted; the points come back as a fresh
     * reward transaction and lot, which is what section 51 means by creating a REWARD_REVERSE.
     */
    @Transactional
    public int reverseCompletedForOrder(String orderId) {
        Instant now = clock.now();
        int reversed = 0;
        for (Redemption redemption : redemptions.findCompletedForOrder(orderId)) {
            if (rewards.hasRedemptionReversal(redemption.getId())) {
                log.debug("Redemption {} was already credited back", redemption.getId());
                continue;
            }
            WalletAccount account = accounts.rewardAccountFor(redemption.getCustomerId());
            WalletBalance balance = accounts.lockBalance(account);
            rewards.creditBackRedeemedPoints(redemption.getCustomerId(), orderId,
                    redemption.getId(), redemption.getPoints(), account, balance, now);
            log.info("Credited {} points back to customer {} after redemption {} was reversed",
                    redemption.getPoints(), redemption.getCustomerId(), redemption.getId());
            reversed++;
        }
        return reversed;
    }

    // =============================================================================================
    // Queries
    // =============================================================================================

    @Transactional(readOnly = true)
    public Redemption require(String redemptionId) {
        return redemptions.findById(redemptionId)
                .orElseThrow(() -> WalletException.of(ErrorCode.REDEMPTION_NOT_FOUND,
                        "redemption %s does not exist", redemptionId));
    }

    @Transactional(readOnly = true)
    public Page<Redemption> historyOf(String customerId, Pageable pageable) {
        return redemptions.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
    }

    @Transactional(readOnly = true)
    public List<RedemptionItem> itemsOf(String redemptionId) {
        return items.findByRedemptionId(redemptionId);
    }

    private RewardProgram activeProgram() {
        return programs.findActiveProgram()
                .orElseThrow(() -> new WalletException(ErrorCode.INTERNAL_ERROR,
                        "no active reward program is configured"));
    }

    private static BigDecimal requirePositiveMoney(BigDecimal value, String field) {
        if (value == null || !Amounts.isPositive(value)) {
            throw WalletException.of(ErrorCode.INVALID_AMOUNT, "%s must be greater than zero",
                    field);
        }
        return Amounts.money(value);
    }

    // =============================================================================================

    /**
     * @param requestedWalletAmount optional; when omitted the wallet returns the maximum redeemable
     *                              amount (HELP.md section 7)
     */
    public record QuoteRequest(
            String customerId,
            String orderId,
            String currency,
            BigDecimal orderAmount,
            BigDecimal requestedWalletAmount) {
    }

    /**
     * @param points the caller's view of the cost, validated against the wallet's own calculation
     *               rather than believed (HELP.md section 44)
     */
    public record ReserveRequest(
            String quoteId,
            String customerId,
            String orderId,
            String currency,
            BigDecimal walletAmount,
            BigDecimal points) {
    }
}
