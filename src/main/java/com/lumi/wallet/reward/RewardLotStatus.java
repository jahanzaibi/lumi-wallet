package com.lumi.wallet.reward;

public enum RewardLotStatus {

    /** Created by an earning that is not yet eligible. */
    PENDING,

    /** Eligible; consumable by redemptions in FEFO order. */
    AVAILABLE,

    /** Nothing left to spend. */
    CONSUMED,

    /** Passed its expiry date with points remaining (HELP.md section 18). */
    EXPIRED,

    /** Cancelled while still pending (HELP.md section 20). */
    VOIDED
}
