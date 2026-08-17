package com.lumi.wallet.reward;

/**
 * How much reward a refund takes back (HELP.md section 52).
 *
 * <p>The spec is emphatic that a refund does not automatically mean a 100% reward reversal, and
 * that the rule belongs to {@code reward_rule} rather than being hard-coded in refund handling.
 */
public enum RefundReversalMode {

    /** Reverse in proportion to the refunded amount: 400 of 1000 refunded reverses 40%. */
    PROPORTIONAL,

    /** Any refund reverses the whole reward. */
    FULL,

    /** Refunds never claw back rewards. */
    NONE
}
