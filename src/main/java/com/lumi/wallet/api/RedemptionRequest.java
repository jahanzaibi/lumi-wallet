package com.lumi.wallet.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/wallet/redemptions} (HELP.md section 9).
 *
 * @param quoteId optional, but recommended: with a quote the wallet can also revalidate the
 *                programme's per-order cap, which it cannot do from a bare wallet amount
 * @param points  optional, and never believed. The wallet recomputes the cost from
 *                {@code walletAmount} and rejects a request whose points disagree, because a client
 *                that has the number wrong has a stale view of the order (HELP.md section 44).
 */
public record RedemptionRequest(

        @Size(max = 64) String quoteId,

        @NotBlank @Size(max = 64) String customerId,

        @NotBlank @Size(max = 100) String orderId,

        @NotBlank @Size(max = 20) String currency,

        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 15, fraction = 4)
        BigDecimal walletAmount,

        @DecimalMin(value = "0") @Digits(integer = 15, fraction = 4) BigDecimal points) {
}
