package com.lumi.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lumi.wallet.reward.RewardService;
import com.lumi.wallet.reward.RewardTransaction;
import com.lumi.wallet.reward.RewardTransactionType;

/**
 * Refunds (HELP.md sections 52, 53).
 *
 * <p>The point the spec is emphatic about: a refund does not automatically mean a 100% reward
 * reversal, and how much is clawed back belongs to {@code reward_rule}, not to refund handling.
 */
class RefundReversalTest extends AbstractWalletTest {

    /** Order types backed by test-only rules, one per refund mode. */
    private static final String FULL_REVERSAL_TYPE = "TEST_FULL";
    private static final String NO_REVERSAL_TYPE = "TEST_NONE";

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The seeded rules are all PROPORTIONAL, so FULL and NONE need rules of their own. They are
     * inserted here rather than added to the production seed migration: they exist to prove the mode
     * is honoured, and shipping them as reference data would put test fixtures in every environment.
     */
    @BeforeEach
    void ensureRefundModeRules() {
        insertRuleIfMissing("rule-test-full", FULL_REVERSAL_TYPE, "FULL");
        insertRuleIfMissing("rule-test-none", NO_REVERSAL_TYPE, "NONE");
    }

    private void insertRuleIfMissing(String id, String orderType, String mode) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reward_rule WHERE id = ?", Integer.class, id);
        if (existing != null && existing > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO reward_rule (
                    id, program_id, order_type, earn_rate, eligibility_type, eligibility_days,
                    minimum_order_amount, maximum_points, expiration_days, refund_reversal_mode,
                    active, created_at)
                VALUES (?, 'program-default', ?, 0.010000, 'ORDER_DELIVERED', NULL,
                    0.0000, 100000.0000, 365, ?, TRUE, CURRENT_TIMESTAMP)
                """, id, orderType, mode);
    }

    /**
     * The proportional example from HELP.md section 53: a 300 refund of a 1000 order reverses 30% of
     * the reward.
     */
    @Test
    @DisplayName("a partial refund reverses a proportional share of the reward")
    void partialRefundReversesProportionally() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, orderId, new BigDecimal("1000"));

        process(event("ORDER_REFUNDED", payload(
                "orderId", orderId, "customerId", customerId,
                "refundId", "REF-1", "refundAmount", new BigDecimal("300"))));

        assertThat(availablePoints(customerId)).as("30% of 1,000 points is reversed")
                .isEqualByComparingTo(new BigDecimal("700"));
        assertThat(reversalPoints(orderId)).isEqualByComparingTo(new BigDecimal("300"));
        assertLedgerConsistent(customerId);
        assertLotsMatchBalance(customerId);
    }

    /** Several partial refunds of one order each reverse their own share, and no more. */
    @Test
    void successivePartialRefundsCannotExceedTheReward() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, orderId, new BigDecimal("1000"));

        process(event("ORDER_REFUNDED", payload("orderId", orderId, "customerId", customerId,
                "refundId", "REF-1", "refundAmount", new BigDecimal("300"))));
        process(event("ORDER_REFUNDED", payload("orderId", orderId, "customerId", customerId,
                "refundId", "REF-2", "refundAmount", new BigDecimal("700"))));
        // A third refund for more than the order was worth cannot claw back points that were never
        // earned.
        process(event("ORDER_REFUNDED", payload("orderId", orderId, "customerId", customerId,
                "refundId", "REF-3", "refundAmount", new BigDecimal("500"))));

        assertThat(availablePoints(customerId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(reversalPoints(orderId)).as("never more than the 1,000 earned")
                .isEqualByComparingTo(new BigDecimal("1000"));
        assertLedgerConsistent(customerId);
    }

    /** A redelivered refund with the same refund id reverses once (HELP.md section 26). */
    @Test
    void repeatedRefundOfTheSameRefundIdReversesOnce() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, orderId, new BigDecimal("1000"));

        for (int delivery = 0; delivery < 3; delivery++) {
            process(event("ORDER_REFUNDED", payload("orderId", orderId, "customerId", customerId,
                    "refundId", "REF-SAME", "refundAmount", new BigDecimal("400"))));
        }

        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("600"));
        assertThat(reversalsFor(orderId)).hasSize(1);
    }

    /**
     * Item-level attribution (HELP.md section 53): refunding the 300 item of a 300 + 700 order
     * reverses that item's points rather than a share of the order total. Here the two happen to
     * agree in value, so the test proves the item path is taken by refunding <em>only</em> the item,
     * with no refund amount for the fallback to use.
     */
    @Test
    @DisplayName("an item-level refund reverses that item's own points")
    void itemLevelRefundUsesItemAttribution() {
        String customerId = newCustomerId();
        String orderId = newOrderId();

        rewards.earn(new RewardService.EarnRequest(customerId, orderId, RETAIL,
                new BigDecimal("1000"), SAR, List.of(
                        new RewardService.OrderItem("ITEM-A", new BigDecimal("300")),
                        new RewardService.OrderItem("ITEM-B", new BigDecimal("700")))));
        rewards.applyEligibilityEvent(orderId, com.lumi.wallet.reward.EligibilityType.ORDER_DELIVERED);
        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("1000"));

        process(event("ORDER_REFUNDED", payload(
                "orderId", orderId, "customerId", customerId, "refundId", "REF-ITEM-A",
                "refundedOrderItemIds", List.of("ITEM-A"))));

        assertThat(availablePoints(customerId)).as("only item A's 300 points are reversed")
                .isEqualByComparingTo(new BigDecimal("700"));
        assertThat(reversalPoints(orderId)).isEqualByComparingTo(new BigDecimal("300"));
        assertLedgerConsistent(customerId);
    }

    /** {@code refund_reversal_mode = FULL}: any refund takes the whole reward back. */
    @Test
    void fullModeReversesEverythingOnAPartialRefund() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        rewards.earn(new RewardService.EarnRequest(customerId, orderId, FULL_REVERSAL_TYPE,
                new BigDecimal("1000"), SAR, List.of()));
        rewards.applyEligibilityEvent(orderId, com.lumi.wallet.reward.EligibilityType.ORDER_DELIVERED);

        process(event("ORDER_REFUNDED", payload("orderId", orderId, "customerId", customerId,
                "refundId", "REF-1", "refundAmount", new BigDecimal("100"))));

        assertThat(availablePoints(customerId)).as("a 10% refund reverses all of it under FULL")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** {@code refund_reversal_mode = NONE}: refunds never claw back rewards. */
    @Test
    void noneModeKeepsTheReward() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        rewards.earn(new RewardService.EarnRequest(customerId, orderId, NO_REVERSAL_TYPE,
                new BigDecimal("1000"), SAR, List.of()));
        rewards.applyEligibilityEvent(orderId, com.lumi.wallet.reward.EligibilityType.ORDER_DELIVERED);

        process(event("ORDER_REFUNDED", payload("orderId", orderId, "customerId", customerId,
                "refundId", "REF-1", "refundAmount", new BigDecimal("1000"))));

        assertThat(availablePoints(customerId)).as("a full refund reverses nothing under NONE")
                .isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(reversalsFor(orderId)).isEmpty();
    }

    /** A payment refund is treated exactly like an order refund. */
    @Test
    void paymentRefundedIsHandledLikeAnOrderRefund() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, orderId, new BigDecimal("1000"));

        process(event("PAYMENT_REFUNDED", payload("orderId", orderId, "customerId", customerId,
                "refundId", "REF-PAY", "refundAmount", new BigDecimal("250"))));

        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("750"));
    }

    /**
     * A refund with no amount and no items has nothing to prorate against. Reversing the whole
     * reward is the safer reading: money went back, so reward should not silently stay.
     */
    @Test
    void refundWithoutAnAmountReversesEverything() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, orderId, new BigDecimal("800"));

        process(event("ORDER_REFUNDED", payload(
                "orderId", orderId, "customerId", customerId, "refundId", "REF-NO-AMOUNT")));

        assertThat(availablePoints(customerId)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private List<RewardTransaction> reversalsFor(String orderId) {
        return rewardTransactions.findByOrderId(orderId).stream()
                .filter(transaction -> transaction.getType() == RewardTransactionType.REVERSE)
                .toList();
    }

    private BigDecimal reversalPoints(String orderId) {
        return reversalsFor(orderId).stream()
                .map(RewardTransaction::getPoints)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
