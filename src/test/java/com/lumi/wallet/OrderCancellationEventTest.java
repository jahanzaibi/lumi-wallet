package com.lumi.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.lumi.wallet.event.EventEnvelope;
import com.lumi.wallet.event.inbound.WalletEventProcessor;
import com.lumi.wallet.redemption.Redemption;
import com.lumi.wallet.redemption.RedemptionStatus;
import com.lumi.wallet.reward.RewardTransaction;
import com.lumi.wallet.reward.RewardTransactionStatus;
import com.lumi.wallet.reward.RewardTransactionType;

/**
 * Cancellation and reversal (HELP.md sections 20, 21, 22, 51), including the second scenario
 * section 62 asks for:
 *
 * <pre>
 * Order earns 1,000 points
 * ORDER_PAYMENT_SUCCESS
 * ORDER_CANCELLED
 * ORDER_CANCELLED
 * ORDER_CANCELLED
 *
 * Expected: only one reward reversal, final reward = 0
 * </pre>
 */
class OrderCancellationEventTest extends AbstractWalletTest {

    @Test
    @DisplayName("three ORDER_CANCELLED deliveries produce exactly one reversal")
    void repeatedCancellationReversesOnce() {
        String customerId = newCustomerId();
        String orderId = newOrderId();

        process(event("PAYMENT_SUCCEEDED", payload(
                "orderId", orderId, "customerId", customerId,
                "orderAmount", new BigDecimal("1000"), "currency", SAR, "orderType", RETAIL)));
        process(event("ORDER_DELIVERED", payload("orderId", orderId, "customerId", customerId)));

        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("1000"));

        // Three separate events, each with its own id: processed_event cannot help here, so the
        // domain guards are what must hold. This is the harder and more realistic case — an upstream
        // service retrying a publish generates a fresh event id every time.
        for (int delivery = 0; delivery < 3; delivery++) {
            process(event("ORDER_CANCELLED", payload(
                    "orderId", orderId, "customerId", customerId,
                    "reason", "CUSTOMER_CANCELLED")));
        }

        assertThat(reversalsFor(orderId)).as("exactly one reversal, however often cancelled")
                .hasSize(1);
        assertThat(reversalsFor(orderId).get(0).getPoints())
                .isEqualByComparingTo(new BigDecimal("1000"));

        assertThat(availablePoints(customerId)).as("final reward").isEqualByComparingTo(
                BigDecimal.ZERO);
        assertThat(rewardDebt(customerId)).as("nothing was spent, so no debt")
                .isEqualByComparingTo(BigDecimal.ZERO);

        RewardTransaction earning = rewardTransactions.findEarningsForOrder(orderId).get(0);
        assertThat(earning.getStatus()).isEqualTo(RewardTransactionStatus.REVERSED);
        assertThat(earning.getPoints())
                .as("the original earning is never edited (HELP.md section 21)")
                .isEqualByComparingTo(new BigDecimal("1000"));

        assertLedgerConsistent(customerId);
        assertLotsMatchBalance(customerId);
    }

    /**
     * A redelivery of the <em>same</em> event id is dropped before any work happens
     * (HELP.md section 26). The unique constraint on {@code processed_event.event_id} is what makes
     * this a guarantee rather than a race.
     */
    @Test
    void duplicateEventIdIsIgnored() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        EventEnvelope paymentSucceeded = event("EVT-fixed-" + orderId, "PAYMENT_SUCCEEDED", payload(
                "orderId", orderId, "customerId", customerId,
                "orderAmount", new BigDecimal("250"), "currency", SAR, "orderType", RETAIL));

        assertThat(process(paymentSucceeded)).isEqualTo(WalletEventProcessor.Outcome.PROCESSED);
        assertThat(process(paymentSucceeded)).isEqualTo(WalletEventProcessor.Outcome.DUPLICATE);
        assertThat(process(paymentSucceeded)).isEqualTo(WalletEventProcessor.Outcome.DUPLICATE);

        assertThat(rewardTransactions.findEarningsForOrder(orderId))
                .as("a duplicate event cannot create another reward (HELP.md section 60, rule 12)")
                .hasSize(1);
    }

    /**
     * Cancelled while the reward was still pending: it is simply voided, and no balance moves
     * (HELP.md section 20).
     */
    @Test
    void cancellingBeforeAvailabilityVoidsTheReward() {
        String customerId = newCustomerId();
        String orderId = newOrderId();

        earnPending(customerId, orderId, new BigDecimal("500"));
        assertThat(availablePoints(customerId)).isEqualByComparingTo(BigDecimal.ZERO);

        process(event("ORDER_CANCELLED", payload("orderId", orderId, "customerId", customerId)));

        RewardTransaction earning = rewardTransactions.findEarningsForOrder(orderId).get(0);
        assertThat(earning.getStatus()).isEqualTo(RewardTransactionStatus.VOIDED);
        assertThat(availablePoints(customerId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rewardDebt(customerId)).isEqualByComparingTo(BigDecimal.ZERO);

        // A voided pending reward can never become available (HELP.md section 60, rule 11).
        process(event("ORDER_DELIVERED", payload("orderId", orderId, "customerId", customerId)));
        assertThat(availablePoints(customerId)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * The reward debt scenario, exactly as HELP.md section 22 sets it out:
     *
     * <pre>
     * Earned = 500, Redeemed = 400, Remaining = 100
     * order cancelled -> available = 0, rewardDebt = 400
     * new reward 700  -> rewardDebt = 0, available = 300
     * </pre>
     */
    @Test
    @DisplayName("reversing already-redeemed points creates debt that a later reward pays off")
    void reversalOfSpentPointsBecomesDebt() {
        String customerId = newCustomerId();
        String earningOrder = newOrderId();
        String spendingOrder = newOrderId();

        grantAvailablePoints(customerId, earningOrder, new BigDecimal("500"));

        // Spend 400 of them on a different order, and let that redemption complete.
        Redemption redemption = redemptions.reserve(reserveRequest(null, customerId, spendingOrder,
                moneyForPoints(new BigDecimal("400"))));
        redemptions.commit(redemption.getId());
        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("100"));

        // Now cancel the order that granted the points.
        process(event("ORDER_CANCELLED", payload(
                "orderId", earningOrder, "customerId", customerId)));

        assertThat(availablePoints(customerId)).as("the 100 still in the lot is taken back")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rewardDebt(customerId)).as("the 400 already spent becomes debt")
                .isEqualByComparingTo(new BigDecimal("400"));
        assertLedgerConsistent(customerId);

        // A future reward pays the debt down first (HELP.md section 22).
        grantAvailablePointsIgnoringDebt(customerId, newOrderId(), new BigDecimal("700"));

        assertThat(rewardDebt(customerId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(availablePoints(customerId)).as("700 earned less 400 of debt")
                .isEqualByComparingTo(new BigDecimal("300"));
        assertLedgerConsistent(customerId);
        assertLotsMatchBalance(customerId);
    }

    /**
     * Cancelling an order whose redemption already committed gives the points back as a fresh grant,
     * and leaves the redemption itself on the record (HELP.md section 51).
     */
    @Test
    void cancellingAnOrderPaidWithPointsCreditsThemBack() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("5000"));

        Redemption redemption = redemptions.reserve(reserveRequest(null, customerId, orderId,
                moneyForPoints(new BigDecimal("3000"))));
        redemptions.commit(redemption.getId());
        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("2000"));

        process(event("ORDER_CANCELLED", payload("orderId", orderId, "customerId", customerId)));

        assertThat(availablePoints(customerId)).as("the 3,000 spent on the cancelled order return")
                .isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(redemptions.require(redemption.getId()).getStatus())
                .as("the redemption is history and is not deleted (HELP.md section 51)")
                .isEqualTo(RedemptionStatus.COMPLETED);
        assertLedgerConsistent(customerId);
        assertLotsMatchBalance(customerId);

        // And a second cancellation must not credit the points twice.
        process(event("ORDER_CANCELLED", payload("orderId", orderId, "customerId", customerId)));
        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("5000"));
    }

    private List<RewardTransaction> reversalsFor(String orderId) {
        return rewardTransactions.findByOrderId(orderId).stream()
                .filter(transaction -> transaction.getType() == RewardTransactionType.REVERSE)
                .toList();
    }

    /**
     * Like {@code grantAvailablePoints} but without asserting the resulting balance, because part of
     * the grant is expected to disappear into outstanding debt.
     */
    private void grantAvailablePointsIgnoringDebt(String customerId, String orderId,
            BigDecimal points) {
        earnPending(customerId, orderId, points);
        rewards.applyEligibilityEvent(orderId, com.lumi.wallet.reward.EligibilityType.ORDER_DELIVERED);
    }
}
