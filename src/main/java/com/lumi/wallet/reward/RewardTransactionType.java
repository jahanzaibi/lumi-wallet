package com.lumi.wallet.reward;

public enum RewardTransactionType {

    /** Points granted for an order (HELP.md section 14). */
    EARN,

    /** Points taken back; never an edit of the EARN row (HELP.md sections 21, 60.5). */
    REVERSE,

    /** Points consumed by a committed redemption (HELP.md section 10). */
    REDEEM,

    /** Points lost to lot expiry (HELP.md section 18). */
    EXPIRE,

    /** Newly available points applied to outstanding reward debt (HELP.md section 22). */
    DEBT_SETTLEMENT
}
