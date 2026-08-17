package com.lumi.wallet.redemption;

/**
 * The redemption state machine from HELP.md section 12.
 *
 * <pre>
 * CREATED -> RESERVED -> COMPLETED
 *                     -> RELEASED
 * </pre>
 *
 * Everything else is invalid, in particular COMPLETED -> RELEASED, COMPLETED -> COMPLETED,
 * RELEASED -> COMPLETED and RELEASED -> RELEASED. Those four are what stop a double commit or a
 * double release from moving points twice.
 */
public enum RedemptionStatus {

    CREATED,
    RESERVED,
    COMPLETED,
    RELEASED;

    public boolean isTerminal() {
        return this == COMPLETED || this == RELEASED;
    }
}
