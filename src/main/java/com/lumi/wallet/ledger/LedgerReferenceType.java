package com.lumi.wallet.ledger;

/** What a ledger transaction was caused by, for audit traversal in either direction. */
public enum LedgerReferenceType {

    REDEMPTION,
    REWARD_TRANSACTION,
    REWARD_LOT
}
