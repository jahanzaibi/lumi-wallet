package com.lumi.wallet.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.lumi.wallet.common.Amounts;
import com.lumi.wallet.redemption.RedemptionQuote;

/**
 * The quote response from HELP.md section 7.
 *
 * <p>A quote is a calculation, not a redemption: nothing has been deducted and nothing is held. The
 * {@code expiresAt} is the point past which the wallet will refuse to honour these terms
 * (section 43).
 */
public record QuoteResponse(
        String quoteId,
        String customerId,
        String orderId,
        String currency,
        BigDecimal orderAmount,
        BigDecimal walletAmount,
        BigDecimal remainingAmount,
        BigDecimal pointsRequired,
        BigDecimal pointsAvailable,
        Instant expiresAt) {

    public static QuoteResponse of(RedemptionQuote quote) {
        return new QuoteResponse(
                quote.getId(),
                quote.getCustomerId(),
                quote.getOrderId(),
                quote.getCurrency(),
                Amounts.money(quote.getOrderAmount()),
                Amounts.money(quote.getWalletAmount()),
                Amounts.money(quote.getRemainingAmount()),
                Amounts.points(quote.getPointsRequired()),
                Amounts.points(quote.getPointsAvailable()),
                quote.getExpiresAt());
    }
}
