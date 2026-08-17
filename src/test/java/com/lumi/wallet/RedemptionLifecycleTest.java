package com.lumi.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.lumi.wallet.common.ErrorCode;
import com.lumi.wallet.common.WalletException;
import com.lumi.wallet.redemption.Redemption;
import com.lumi.wallet.redemption.RedemptionQuote;
import com.lumi.wallet.redemption.RedemptionService;
import com.lumi.wallet.redemption.RedemptionStatus;

/**
 * The quote, reserve, commit and release lifecycle (HELP.md sections 7 to 13, 41 to 44).
 */
class RedemptionLifecycleTest extends AbstractWalletTest {

    private static final BigDecimal GRANTED = new BigDecimal("10000");

    // =============================================================================================
    // Quote (HELP.md sections 7, 8)
    // =============================================================================================

    @Test
    @DisplayName("a quote reproduces the worked example in HELP.md section 7")
    void quoteMatchesTheSpecExample() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);

        RedemptionQuote quote = redemptions.quote(new RedemptionService.QuoteRequest(customerId,
                "ORD-100", SAR, new BigDecimal("100.00"), new BigDecimal("30.00")));

        assertThat(quote.getWalletAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(quote.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("70.00"));
        assertThat(quote.getPointsRequired()).isEqualByComparingTo(new BigDecimal("3000"));
        assertThat(quote.getPointsAvailable()).isEqualByComparingTo(GRANTED);
        assertThat(quote.getExpiresAt()).isAfter(clock.instant());
    }

    /** A quote must not move anything (HELP.md section 8). */
    @Test
    void quoteDoesNotTouchTheBalance() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);

        redemptions.quote(new RedemptionService.QuoteRequest(customerId, newOrderId(), SAR,
                new BigDecimal("100.00"), new BigDecimal("30.00")));

        assertThat(availablePoints(customerId)).isEqualByComparingTo(GRANTED);
        assertThat(lockedPoints(customerId)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** With no requested amount, the wallet offers the most it can (HELP.md section 7). */
    @Test
    void quoteWithoutARequestedAmountOffersTheMaximum() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("2500"));

        RedemptionQuote quote = redemptions.quote(new RedemptionService.QuoteRequest(customerId,
                newOrderId(), SAR, new BigDecimal("100.00"), null));

        assertThat(quote.getWalletAmount()).as("2,500 points are worth 25.00")
                .isEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(quote.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("75.00"));
    }

    /** The wallet cannot contribute more than the order is worth. */
    @Test
    void quoteIsCappedByTheOrderAmount() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);

        RedemptionQuote quote = redemptions.quote(new RedemptionService.QuoteRequest(customerId,
                newOrderId(), SAR, new BigDecimal("40.00"), new BigDecimal("100.00")));

        assertThat(quote.getWalletAmount()).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(quote.getRemainingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** A customer with no points gets a zero quote rather than an error. */
    @Test
    void quoteWithNoPointsOffersNothing() {
        RedemptionQuote quote = redemptions.quote(new RedemptionService.QuoteRequest(
                newCustomerId(), newOrderId(), SAR, new BigDecimal("100.00"), null));

        assertThat(quote.getWalletAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(quote.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(quote.getPointsRequired()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void quoteRejectsARewardAssetAsTheOrderCurrency() {
        // 100 SAR is not 100 POINTS (HELP.md section 2), so POINT is not a currency an order can be
        // priced in.
        assertThatThrownBy(() -> redemptions.quote(new RedemptionService.QuoteRequest(
                newCustomerId(), newOrderId(), "POINT", new BigDecimal("100.00"), null)))
                .isInstanceOf(WalletException.class)
                .extracting(e -> ((WalletException) e).code())
                .isEqualTo(ErrorCode.INVALID_CURRENCY);
    }

    // =============================================================================================
    // Reserve, commit, release (HELP.md sections 9, 10, 11)
    // =============================================================================================

    @Test
    @DisplayName("the full checkout sequence from HELP.md section 48")
    void quoteReserveCommit() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);

        RedemptionQuote quote = redemptions.quote(new RedemptionService.QuoteRequest(customerId,
                orderId, SAR, new BigDecimal("100.00"), new BigDecimal("30.00")));

        Redemption reserved = redemptions.reserve(new RedemptionService.ReserveRequest(
                quote.getId(), customerId, orderId, SAR, new BigDecimal("30.00"),
                new BigDecimal("3000")));

        assertThat(reserved.getStatus()).isEqualTo(RedemptionStatus.RESERVED);
        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("7000"));
        assertThat(lockedPoints(customerId)).isEqualByComparingTo(new BigDecimal("3000"));
        assertThat(reserved.getExpiresAt()).as("reservations carry a TTL (HELP.md section 13)")
                .isEqualTo(clock.instant().plusSeconds(300));

        Redemption committed = redemptions.commit(reserved.getId());

        assertThat(committed.getStatus()).isEqualTo(RedemptionStatus.COMPLETED);
        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("7000"));
        assertThat(lockedPoints(customerId)).as("locked points are consumed, not returned")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertLedgerConsistent(customerId);
        assertLotsMatchBalance(customerId);
    }

    @Test
    void releaseReturnsThePointsToTheirOriginalLots() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);

        Redemption reserved = redemptions.reserve(reserveRequest(null, customerId, orderId,
                new BigDecimal("30.00")));
        String lotId = redemptions.itemsOf(reserved.getId()).get(0).getRewardLotId();

        redemptions.release(reserved.getId(), "TEST");

        assertThat(availablePoints(customerId)).isEqualByComparingTo(GRANTED);
        assertThat(rewardLots.findById(lotId).orElseThrow().getRemainingPoints())
                .as("the points go back to the lot they came from (HELP.md section 36)")
                .isEqualByComparingTo(GRANTED);
        assertLotsMatchBalance(customerId);
    }

    /** Never trust points supplied by the client (HELP.md section 44). */
    @Test
    void reserveRejectsClientSuppliedPointsThatDisagree() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);

        assertThatThrownBy(() -> redemptions.reserve(new RedemptionService.ReserveRequest(null,
                customerId, newOrderId(), SAR, new BigDecimal("30.00"), new BigDecimal("1"))))
                .isInstanceOf(WalletException.class)
                .extracting(e -> ((WalletException) e).code())
                .isEqualTo(ErrorCode.QUOTE_MISMATCH);

        assertThat(availablePoints(customerId)).isEqualByComparingTo(GRANTED);
    }

    /** A quote cannot be trusted after expiration (HELP.md sections 43, 60.15). */
    @Test
    void reserveRejectsAnExpiredQuote() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);

        RedemptionQuote quote = redemptions.quote(new RedemptionService.QuoteRequest(customerId,
                orderId, SAR, new BigDecimal("100.00"), new BigDecimal("30.00")));

        clock.advanceMinutes(16);

        assertThatThrownBy(() -> redemptions.reserve(new RedemptionService.ReserveRequest(
                quote.getId(), customerId, orderId, SAR, new BigDecimal("30.00"), null)))
                .isInstanceOf(WalletException.class)
                .extracting(e -> ((WalletException) e).code())
                .isEqualTo(ErrorCode.QUOTE_EXPIRED);
    }

    @Test
    void reserveRejectsAQuoteIssuedForAnotherOrder() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);

        RedemptionQuote quote = redemptions.quote(new RedemptionService.QuoteRequest(customerId,
                newOrderId(), SAR, new BigDecimal("100.00"), new BigDecimal("30.00")));

        assertThatThrownBy(() -> redemptions.reserve(new RedemptionService.ReserveRequest(
                quote.getId(), customerId, newOrderId(), SAR, new BigDecimal("30.00"), null)))
                .isInstanceOf(WalletException.class)
                .extracting(e -> ((WalletException) e).code())
                .isEqualTo(ErrorCode.QUOTE_MISMATCH);
    }

    /**
     * A stale quote does not authorise spending points that have since gone
     * (HELP.md section 43: "never trust the quote").
     */
    @Test
    void reserveRevalidatesTheBalanceRatherThanTrustingTheQuote() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);

        RedemptionQuote quote = redemptions.quote(new RedemptionService.QuoteRequest(customerId,
                newOrderId(), SAR, new BigDecimal("100.00"), new BigDecimal("100.00")));
        assertThat(quote.getPointsRequired()).isEqualByComparingTo(GRANTED);

        // Another device spends most of the points after the quote was issued.
        redemptions.reserve(reserveRequest(null, customerId, newOrderId(), new BigDecimal("95.00")));

        assertThatThrownBy(() -> redemptions.reserve(new RedemptionService.ReserveRequest(
                quote.getId(), customerId, quote.getOrderId(), SAR, new BigDecimal("100.00"), null)))
                .isInstanceOf(WalletException.class)
                .extracting(e -> ((WalletException) e).code())
                .isEqualTo(ErrorCode.INSUFFICIENT_REWARD_BALANCE);
    }

    @Test
    void oneOrderCannotHoldTwoLiveRedemptions() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);

        redemptions.reserve(reserveRequest(null, customerId, orderId, new BigDecimal("10.00")));

        assertThatThrownBy(() -> redemptions.reserve(
                reserveRequest(null, customerId, orderId, new BigDecimal("10.00"))))
                .isInstanceOf(WalletException.class)
                .extracting(e -> ((WalletException) e).code())
                .isEqualTo(ErrorCode.DUPLICATE_ORDER_REDEMPTION);
    }

    /**
     * A released reservation leaves the order free to try again, which is why the redemption key is
     * order plus sequence rather than order alone (HELP.md section 35).
     */
    @Test
    void anOrderMayRetryAfterARelease() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);

        Redemption first = redemptions.reserve(
                reserveRequest(null, customerId, orderId, new BigDecimal("10.00")));
        redemptions.release(first.getId(), "PAYMENT_FAILED");

        Redemption second = redemptions.reserve(
                reserveRequest(null, customerId, orderId, new BigDecimal("10.00")));

        assertThat(second.getRedemptionSequence()).isEqualTo(2);
        assertThat(second.getStatus()).isEqualTo(RedemptionStatus.RESERVED);
    }

    // =============================================================================================
    // State machine (HELP.md section 12)
    // =============================================================================================

    @Test
    @DisplayName("COMPLETED -> RELEASED is refused (HELP.md sections 12, 60.10)")
    void aCommittedRedemptionCannotBeReleased() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);
        Redemption redemption = redemptions.reserve(
                reserveRequest(null, customerId, newOrderId(), new BigDecimal("30.00")));
        redemptions.commit(redemption.getId());

        assertThatThrownBy(() -> redemptions.release(redemption.getId(), "TEST"))
                .isInstanceOf(WalletException.class)
                .extracting(e -> ((WalletException) e).code())
                .isEqualTo(ErrorCode.INVALID_REDEMPTION_STATE);

        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("7000"));
    }

    @Test
    @DisplayName("RELEASED -> COMPLETED is refused (HELP.md section 12)")
    void aReleasedRedemptionCannotBeCommitted() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);
        Redemption redemption = redemptions.reserve(
                reserveRequest(null, customerId, newOrderId(), new BigDecimal("30.00")));
        redemptions.release(redemption.getId(), "TEST");

        assertThatThrownBy(() -> redemptions.commit(redemption.getId()))
                .isInstanceOf(WalletException.class)
                .extracting(e -> ((WalletException) e).code())
                .isEqualTo(ErrorCode.INVALID_REDEMPTION_STATE);
    }

    /** A duplicate commit or release replays the outcome (HELP.md section 40). */
    @Test
    void duplicateCommitAndReleaseAreNoOps() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);

        Redemption committed = redemptions.reserve(
                reserveRequest(null, customerId, newOrderId(), new BigDecimal("30.00")));
        redemptions.commit(committed.getId());
        redemptions.commit(committed.getId());
        redemptions.commit(committed.getId());

        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("7000"));
        assertThat(ledgerPostingCount(com.lumi.wallet.ledger.LedgerReferenceType.REDEMPTION,
                committed.getId())).isEqualTo(1);

        Redemption released = redemptions.reserve(
                reserveRequest(null, customerId, newOrderId(), new BigDecimal("30.00")));
        redemptions.release(released.getId(), "TEST");
        redemptions.release(released.getId(), "TEST");

        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("7000"));
        assertLotsMatchBalance(customerId);
    }

    @Test
    void unknownRedemptionIsReported() {
        assertThatThrownBy(() -> redemptions.require("RED-does-not-exist"))
                .isInstanceOf(WalletException.class)
                .extracting(e -> ((WalletException) e).code())
                .isEqualTo(ErrorCode.REDEMPTION_NOT_FOUND);
    }

    // =============================================================================================
    // Expiry (HELP.md sections 13, 50)
    // =============================================================================================

    @Test
    @DisplayName("a reservation nobody committed is released by the sweep (HELP.md section 50)")
    void expiredReservationsAreReleased() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);
        Redemption redemption = redemptions.reserve(
                reserveRequest(null, customerId, newOrderId(), new BigDecimal("30.00")));

        assertThat(redemptions.findExpiredReservationIds(10)).as("not expired yet").isEmpty();

        // 12:00 reservation, 12:05 expiry (HELP.md section 13).
        clock.advanceMinutes(6);

        List<String> expired = redemptions.findExpiredReservationIds(10);
        assertThat(expired).contains(redemption.getId());
        assertThat(redemptions.releaseExpired(redemption.getId())).isTrue();

        assertThat(redemptions.require(redemption.getId()).getStatus())
                .isEqualTo(RedemptionStatus.RELEASED);
        assertThat(availablePoints(customerId)).isEqualByComparingTo(GRANTED);

        // The sweep is idempotent: a second pass finds nothing to do (HELP.md section 50).
        assertThat(redemptions.releaseExpired(redemption.getId())).isFalse();
        assertThat(availablePoints(customerId)).isEqualByComparingTo(GRANTED);
    }

    /**
     * Committing after the TTL elapsed is refused: the points may already have been released and
     * spent elsewhere (HELP.md section 13).
     */
    @Test
    void anExpiredReservationCannotBeCommitted() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);
        Redemption redemption = redemptions.reserve(
                reserveRequest(null, customerId, newOrderId(), new BigDecimal("30.00")));

        clock.advanceMinutes(6);

        assertThatThrownBy(() -> redemptions.commit(redemption.getId()))
                .isInstanceOf(WalletException.class)
                .extracting(e -> ((WalletException) e).code())
                .isEqualTo(ErrorCode.REDEMPTION_EXPIRED);
    }

    /**
     * The expiry sweep must never blindly add points (HELP.md section 13): a reservation that was
     * committed in the meantime is left alone.
     */
    @Test
    void theSweepDoesNotTouchACommittedRedemption() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);
        Redemption redemption = redemptions.reserve(
                reserveRequest(null, customerId, newOrderId(), new BigDecimal("30.00")));
        redemptions.commit(redemption.getId());

        clock.advanceMinutes(6);

        assertThat(redemptions.findExpiredReservationIds(10)).doesNotContain(redemption.getId());
        assertThat(redemptions.releaseExpired(redemption.getId())).isFalse();
        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("7000"));
    }
}
