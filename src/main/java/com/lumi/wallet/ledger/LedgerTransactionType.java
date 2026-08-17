package com.lumi.wallet.ledger;

/**
 * Why a posting exists. Together with the reference type and id this is unique, which is how a
 * duplicate commit is prevented from producing a second posting (HELP.md sections 40, 60.8).
 */
public enum LedgerTransactionType {

    /** A pending reward became available and entered the customer's balance. */
    REWARD_AVAILABLE,

    /** Newly available reward points were applied to outstanding reward debt (section 22). */
    REWARD_DEBT_SETTLEMENT,

    /** A committed redemption permanently consumed points (section 10). */
    REDEMPTION_COMMIT,

    /** A committed redemption was reversed because the order was cancelled (section 51). */
    REDEMPTION_REVERSAL,

    /** Available reward points were reversed (section 21). */
    REWARD_REVERSAL,

    /** A lot reached its expiry date (section 18). */
    REWARD_EXPIRY
}
