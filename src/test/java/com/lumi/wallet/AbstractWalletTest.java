package com.lumi.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.lumi.wallet.account.WalletAccount;
import com.lumi.wallet.account.WalletAccountService;
import com.lumi.wallet.account.WalletBalance;
import com.lumi.wallet.event.EventEnvelope;
import com.lumi.wallet.event.inbound.WalletEventProcessor;
import com.lumi.wallet.event.outbound.OutboxEventRepository;
import com.lumi.wallet.ledger.LedgerEntry;
import com.lumi.wallet.ledger.LedgerEntryRepository;
import com.lumi.wallet.ledger.LedgerTransaction;
import com.lumi.wallet.ledger.LedgerTransactionRepository;
import com.lumi.wallet.redemption.RedemptionRepository;
import com.lumi.wallet.redemption.RedemptionService;
import com.lumi.wallet.reward.EligibilityType;
import com.lumi.wallet.reward.RewardLotRepository;
import com.lumi.wallet.reward.RewardService;
import com.lumi.wallet.reward.RewardTransaction;
import com.lumi.wallet.reward.RewardTransactionRepository;
import com.lumi.wallet.support.MutableClock;
import com.lumi.wallet.support.WalletTestConfig;

/**
 * Shared setup for the integration tests.
 *
 * <h2>Isolation without rollback</h2>
 *
 * <p>These tests deliberately do <em>not</em> run in a rolled-back transaction. Almost everything
 * worth testing here spans several transactions — a reservation committed by a later request, an
 * event handler whose {@code processed_event} row must survive, a pessimistic lock that only means
 * anything between two real transactions — and a test-managed transaction would either hide those
 * boundaries or deadlock against them.
 *
 * <p>Isolation comes from unique customer and order ids instead, so the shared in-memory database is
 * harmless: every assertion is scoped to ids this test invented.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(WalletTestConfig.class)
public abstract class AbstractWalletTest {

    /**
     * The default RETAIL rule earns 1% of the order as reward value at 100 points per currency unit,
     * so an order of N currency units earns exactly N points. That identity keeps the arithmetic in
     * the tests readable: to grant 10,000 points, earn on a 10,000 order.
     */
    protected static final String RETAIL = "RETAIL";
    protected static final String SAR = "SAR";

    /** Points per currency unit in the seeded programme: 3,000 points settle 30.00 SAR. */
    protected static final BigDecimal POINTS_PER_UNIT = new BigDecimal("100");

    @Autowired
    protected RewardService rewards;

    @Autowired
    protected RedemptionService redemptions;

    @Autowired
    protected WalletAccountService accounts;

    @Autowired
    protected WalletEventProcessor eventProcessor;

    @Autowired
    protected RewardTransactionRepository rewardTransactions;

    @Autowired
    protected RewardLotRepository rewardLots;

    @Autowired
    protected RedemptionRepository redemptionRepository;

    @Autowired
    protected LedgerTransactionRepository ledgerTransactions;

    @Autowired
    protected LedgerEntryRepository ledgerEntries;

    @Autowired
    protected OutboxEventRepository outboxEvents;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    // =============================================================================================
    // Fixtures
    // =============================================================================================

    protected String newCustomerId() {
        return "CUS-" + UUID.randomUUID();
    }

    protected String newOrderId() {
        return "ORD-" + UUID.randomUUID();
    }

    /** Earns a reward that is still PENDING, as a payment success would (HELP.md section 14). */
    protected RewardTransaction earnPending(String customerId, String orderId, BigDecimal points) {
        return rewards.earn(RewardService.EarnRequest.of(customerId, orderId, RETAIL, points, SAR))
                .orElseThrow(() -> new IllegalStateException("order " + orderId + " earned nothing"));
    }

    /**
     * Gives the customer spendable points, through the real earn-then-become-eligible path rather
     * than by writing a balance row. A test that sets up its state by bypassing the domain proves
     * less than one that uses it.
     */
    protected RewardTransaction grantAvailablePoints(String customerId, String orderId,
            BigDecimal points) {
        RewardTransaction earning = earnPending(customerId, orderId, points);
        rewards.applyEligibilityEvent(orderId, EligibilityType.ORDER_DELIVERED);
        assertThat(availablePoints(customerId)).isEqualByComparingTo(points);
        return earning;
    }

    /** Money value of a number of points, at the seeded programme's rate. */
    protected static BigDecimal moneyForPoints(BigDecimal points) {
        return points.divide(POINTS_PER_UNIT, 2, java.math.RoundingMode.FLOOR);
    }

    // =============================================================================================
    // State inspection
    // =============================================================================================

    protected BigDecimal availablePoints(String customerId) {
        return rewards.availablePoints(customerId);
    }

    protected WalletBalance rewardBalance(String customerId) {
        return accounts.findRewardBalance(customerId)
                .orElseThrow(() -> new IllegalStateException(
                        "customer " + customerId + " has no reward balance"));
    }

    protected BigDecimal lockedPoints(String customerId) {
        return rewardBalance(customerId).getLockedAmount();
    }

    protected BigDecimal rewardDebt(String customerId) {
        return rewardBalance(customerId).getDebtAmount();
    }

    // =============================================================================================
    // Invariants (HELP.md sections 38, 60)
    // =============================================================================================

    /**
     * Asserts the two identities the whole design rests on:
     *
     * <pre>
     * every ledger transaction balances, per asset      (HELP.md section 38)
     * the account's ledger position == available + locked
     * </pre>
     *
     * <p>Reward debt is deliberately absent from the second: a debt is an obligation, not a movement,
     * so nothing is posted for it until a future reward actually pays it down.
     */
    protected void assertLedgerConsistent(String customerId) {
        WalletAccount account = accounts.rewardAccountFor(customerId);
        WalletBalance balance = rewardBalance(customerId);

        for (LedgerEntry entry : ledgerEntries.findByWalletAccountId(account.getId())) {
            assertBalancedPosting(entry.getLedgerTransactionId());
        }

        BigDecimal position = ledgerEntries.netPosition(account.getId());
        assertThat(position)
                .as("ledger position of %s must equal available (%s) + locked (%s)",
                        customerId, balance.getAvailableAmount(), balance.getLockedAmount())
                .isEqualByComparingTo(
                        balance.getAvailableAmount().add(balance.getLockedAmount()));
    }

    /** HELP.md section 38: SUM(DEBIT) == SUM(CREDIT), for each asset. */
    protected void assertBalancedPosting(String ledgerTransactionId) {
        Map<String, BigDecimal> netByAsset = new HashMap<>();
        for (LedgerEntry entry : ledgerEntries.findByLedgerTransactionId(ledgerTransactionId)) {
            netByAsset.merge(entry.getAssetId(), entry.signedAmount(), BigDecimal::add);
        }
        assertThat(netByAsset).isNotEmpty();
        netByAsset.forEach((assetId, net) -> assertThat(net)
                .as("ledger transaction %s must balance for asset %s", ledgerTransactionId, assetId)
                .isEqualByComparingTo(BigDecimal.ZERO));
    }

    /**
     * Asserts the sum of the customer's available lots equals their available balance. The lots are
     * the record of which points are spendable and when they expire; a balance that disagrees with
     * them means one of the two is lying (HELP.md sections 18, 19).
     */
    protected void assertLotsMatchBalance(String customerId) {
        BigDecimal fromLots = rewardLots.sumAvailablePoints(customerId, clock.instant());
        assertThat(fromLots)
                .as("available lots of %s must sum to the available balance", customerId)
                .isEqualByComparingTo(rewardBalance(customerId).getAvailableAmount());
    }

    protected long ledgerPostingCount(com.lumi.wallet.ledger.LedgerReferenceType referenceType,
            String referenceId) {
        return ledgerTransactions.findByReferenceTypeAndReferenceId(referenceType, referenceId)
                .size();
    }

    // =============================================================================================
    // Events (HELP.md section 25)
    // =============================================================================================

    protected EventEnvelope event(String eventType, Map<String, Object> payload) {
        return event("EVT-" + UUID.randomUUID(), eventType, payload);
    }

    /** An event with a chosen id, for asserting that a redelivery changes nothing (section 26). */
    protected EventEnvelope event(String eventId, String eventType, Map<String, Object> payload) {
        return EventEnvelope.of(eventId, eventType, "order-service",
                "CORR-" + UUID.randomUUID(), Instant.now(clock), payload);
    }

    protected static Map<String, Object> payload(Object... keysAndValues) {
        Map<String, Object> payload = new HashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            payload.put(keysAndValues[index].toString(), keysAndValues[index + 1]);
        }
        return payload;
    }

    protected WalletEventProcessor.Outcome process(EventEnvelope envelope) {
        return eventProcessor.process(envelope, "test");
    }

    /** Convenience for the reserve request the API would build. */
    protected RedemptionService.ReserveRequest reserveRequest(String quoteId, String customerId,
            String orderId, BigDecimal walletAmount) {
        return new RedemptionService.ReserveRequest(quoteId, customerId, orderId, SAR, walletAmount,
                null);
    }
}
