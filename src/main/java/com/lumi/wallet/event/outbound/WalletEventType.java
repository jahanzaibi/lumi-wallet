package com.lumi.wallet.event.outbound;

/**
 * The events the wallet publishes, with the routing key each one carries (HELP.md sections 23, 55).
 *
 * <p>These exist so that other services can show reward status without polling the wallet.
 */
public enum WalletEventType {

    /** A reward was earned but is not yet usable (HELP.md section 14). */
    REWARD_PENDING("wallet.reward.pending"),

    /** A pending reward satisfied its eligibility rule and entered the balance (section 16). */
    REWARD_AVAILABLE("wallet.reward.available"),

    /** Reward points were taken back after a cancellation or refund (sections 21, 52). */
    REWARD_REVERSED("wallet.reward.reversed"),

    /** Points expired unused (section 18). Not listed in section 55, but other services need it for
     * the same reason they need the rest: to show a balance without asking. */
    REWARD_EXPIRED("wallet.reward.expired"),

    /** Points moved from available to locked for a checkout (section 9). */
    REDEMPTION_RESERVED("wallet.redemption.reserved"),

    /** A redemption was committed and the points are permanently consumed (section 10). */
    REDEMPTION_COMPLETED("wallet.redemption.completed"),

    /** A reservation was released and the points went back to available (section 11). */
    REDEMPTION_RELEASED("wallet.redemption.released");

    private final String routingKey;

    WalletEventType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
