package com.lumi.wallet.reward;

/**
 * The reward lifecycle from HELP.md section 15:
 *
 * <pre>
 * PAYMENT_SUCCESS -> PENDING -> (eligibility event) -> AVAILABLE -> REDEEMED
 *                        \
 *                         -> (cancelled) -> VOIDED
 * </pre>
 */
public enum RewardTransactionStatus {

    /** Earned but not yet usable (HELP.md section 14). */
    PENDING,

    /** Eligible and spendable. */
    AVAILABLE,

    /** Cancelled before it ever became available; no balance change was needed (section 20). */
    VOIDED,

    /** Reversed after becoming available (section 21). */
    REVERSED,

    /** Fully consumed by redemptions. */
    REDEEMED,

    /** Expired unused (section 18). */
    EXPIRED,

    /** Terminal state for transactions that are an event rather than a balance: REDEEM, EXPIRE. */
    COMPLETED
}
