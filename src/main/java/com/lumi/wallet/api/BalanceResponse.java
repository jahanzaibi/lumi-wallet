package com.lumi.wallet.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code GET /api/v1/wallet/balance} (HELP.md section 46).
 *
 * <p>One entry per asset the customer holds, never a single total. A customer with 500 SAR, 100 USD
 * and 10,000 points has three independent positions, and adding them up would be meaningless
 * (HELP.md section 2).
 */
public record BalanceResponse(
        String customerId,
        List<AssetBalance> balances) {

    /**
     * @param debt reward debt: points owed back after an already-redeemed reward was reversed
     *             (HELP.md section 22). Always zero for monetary assets.
     */
    public record AssetBalance(
            String asset,
            String assetType,
            String status,
            BigDecimal available,
            BigDecimal locked,
            BigDecimal debt) {
    }
}
