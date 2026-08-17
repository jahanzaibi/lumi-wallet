package com.lumi.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.lumi.wallet.ledger.LedgerReferenceType;
import com.lumi.wallet.redemption.Redemption;
import com.lumi.wallet.redemption.RedemptionStatus;
import com.lumi.wallet.reward.RewardTransactionStatus;

/**
 * Payment events (HELP.md sections 14, 48, 49), including the third scenario section 62 asks for:
 *
 * <pre>
 * REDEMPTION = 3,000 points
 * PAYMENT_SUCCESS
 * PAYMENT_SUCCESS
 * PAYMENT_SUCCESS
 *
 * Expected: one redemption, one ledger posting, 3,000 points consumed exactly once
 * </pre>
 */
class PaymentEventTest extends AbstractWalletTest {

    private static final BigDecimal GRANTED = new BigDecimal("10000");
    private static final BigDecimal REDEEMED = new BigDecimal("3000");

    @Test
    @DisplayName("three PAYMENT_SUCCESS deliveries consume 3,000 points exactly once")
    void repeatedPaymentSuccessCommitsOnce() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);

        Redemption redemption = redemptions.reserve(
                reserveRequest(null, customerId, orderId, moneyForPoints(REDEEMED)));
        assertThat(redemption.getStatus()).isEqualTo(RedemptionStatus.RESERVED);
        assertThat(lockedPoints(customerId)).isEqualByComparingTo(REDEEMED);

        // Three distinct event ids, so the deduplication has to come from the redemption state
        // machine rather than from processed_event.
        for (int delivery = 0; delivery < 3; delivery++) {
            process(event("PAYMENT_SUCCEEDED", payload(
                    "orderId", orderId, "customerId", customerId,
                    "orderAmount", new BigDecimal("100"), "currency", SAR, "orderType", RETAIL)));
        }

        assertThat(redemptions.require(redemption.getId()).getStatus())
                .isEqualTo(RedemptionStatus.COMPLETED);
        assertThat(redemptionRepository.findByOrderIdAndRedemptionSequence(orderId, 1))
                .as("one redemption for the order").isPresent();
        assertThat(redemptionRepository.findLiveForOrder(orderId)).hasSize(1);

        assertThat(ledgerPostingCount(LedgerReferenceType.REDEMPTION, redemption.getId()))
                .as("one ledger posting, no matter how many payment successes arrive")
                .isEqualTo(1);

        assertThat(availablePoints(customerId))
                .as("3,000 consumed exactly once out of 10,000")
                .isEqualByComparingTo(new BigDecimal("7000"));
        assertThat(lockedPoints(customerId)).isEqualByComparingTo(BigDecimal.ZERO);

        // The same event also earns the order's own reward, once (HELP.md section 14).
        assertThat(rewardTransactions.findEarningsForOrder(orderId)).hasSize(1);
        assertThat(rewardTransactions.findEarningsForOrder(orderId).get(0).getStatus())
                .as("a new earning starts PENDING and is not yet spendable")
                .isEqualTo(RewardTransactionStatus.PENDING);

        assertLedgerConsistent(customerId);
        assertLotsMatchBalance(customerId);
    }

    /** A failed payment returns the locked points (HELP.md sections 11, 49). */
    @Test
    void paymentFailureReleasesTheReservation() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);

        Redemption redemption = redemptions.reserve(
                reserveRequest(null, customerId, orderId, moneyForPoints(REDEEMED)));

        process(event("PAYMENT_FAILED", payload(
                "orderId", orderId, "customerId", customerId, "reason", "CARD_DECLINED")));

        assertThat(redemptions.require(redemption.getId()).getStatus())
                .isEqualTo(RedemptionStatus.RELEASED);
        assertThat(availablePoints(customerId)).isEqualByComparingTo(GRANTED);
        assertThat(lockedPoints(customerId)).isEqualByComparingTo(BigDecimal.ZERO);

        // No ledger posting at all: the points never left the account (HELP.md section 11).
        assertThat(ledgerPostingCount(LedgerReferenceType.REDEMPTION, redemption.getId()))
                .isZero();
        assertLedgerConsistent(customerId);
        assertLotsMatchBalance(customerId);
    }

    /**
     * Repeated payment failures must not credit the points more than once
     * (HELP.md section 40, "no double credit").
     */
    @Test
    void repeatedPaymentFailureDoesNotCreditTwice() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);
        redemptions.reserve(reserveRequest(null, customerId, orderId, moneyForPoints(REDEEMED)));

        for (int delivery = 0; delivery < 3; delivery++) {
            process(event("PAYMENT_FAILED", payload("orderId", orderId, "customerId", customerId)));
        }

        assertThat(availablePoints(customerId)).isEqualByComparingTo(GRANTED);
        assertLotsMatchBalance(customerId);
    }

    /**
     * The order earns its reward on payment success even when no points were redeemed, which is the
     * ordinary case: reward earning is independent of redemption (HELP.md section 14).
     */
    @Test
    void paymentSuccessEarnsRewardWithoutARedemption() {
        String customerId = newCustomerId();
        String orderId = newOrderId();

        process(event("PAYMENT_SUCCEEDED", payload(
                "orderId", orderId, "customerId", customerId,
                "orderAmount", new BigDecimal("500"), "currency", SAR, "orderType", RETAIL)));

        assertThat(rewardTransactions.findEarningsForOrder(orderId)).hasSize(1);
        assertThat(rewardTransactions.findEarningsForOrder(orderId).get(0).getPoints())
                .as("500 SAR at 1% and 100 points per unit earns 500 points (HELP.md section 14)")
                .isEqualByComparingTo(new BigDecimal("500"));
        assertThat(availablePoints(customerId)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
