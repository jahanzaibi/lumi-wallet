package com.lumi.wallet.account;

public enum AccountType {

    /** A wallet owned by a customer. */
    CUSTOMER,

    /**
     * The counterparty side of every posting. A reward credited to a customer is debited from the
     * liability account, which is what keeps SUM(DEBIT) equal to SUM(CREDIT) per asset
     * (HELP.md section 38).
     */
    LIABILITY
}
