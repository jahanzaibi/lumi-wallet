package com.lumi.wallet.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/wallet/redemptions/quote} (HELP.md section 7).
 *
 * @param requestedWalletAmount optional. Omit it to ask for the maximum the wallet can contribute.
 */
public record QuoteRequest(

        @NotBlank @Size(max = 64) String customerId,

        @NotBlank @Size(max = 100) String orderId,

        @NotBlank @Size(max = 20) String currency,

        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 15, fraction = 4)
        BigDecimal orderAmount,

        @DecimalMin(value = "0.00") @Digits(integer = 15, fraction = 4)
        BigDecimal requestedWalletAmount) {
}
