package com.lumi.wallet.reward;

/**
 * Natural keys for {@code reward_transaction.dedupe_key}.
 *
 * <p>These are what make "a duplicate event cannot create another reward" (HELP.md section 60.12)
 * an database guarantee rather than a hope. {@code processed_event} already stops a redelivered
 * message, but two <em>distinct</em> events describing the same business fact (an order cancelled
 * twice by two different upstream retries, each with its own event id) would otherwise slip
 * through; the unique constraint on this column stops them.
 *
 * <p>Derby permits only one NULL per unique index, so genuinely repeatable transactions cannot
 * simply leave the column empty. They use their own id instead, which is unique by construction.
 */
public final class DedupeKeys {

    private DedupeKeys() {
    }

    /** One earning per order. */
    public static String earn(String orderId) {
        return "EARN:" + orderId;
    }

    /** One cancellation reversal per order, however many times the cancellation is published. */
    public static String cancellationReversal(String orderId) {
        return "REVERSE:ORDER_CANCELLED:" + orderId;
    }

    /**
     * One reversal per refund. Keyed on the refund rather than the order, because an order can be
     * refunded in several parts and each part legitimately reverses its own share
     * (HELP.md section 53).
     */
    public static String refundReversal(String orderId, String refundId) {
        return "REVERSE:REFUND:" + orderId + ":" + refundId;
    }

    /** One reversal of a committed redemption per order. */
    public static String redemptionReversal(String redemptionId) {
        return "REVERSE:REDEMPTION:" + redemptionId;
    }

    /** One redemption record per redemption. */
    public static String redeem(String redemptionId) {
        return "REDEEM:" + redemptionId;
    }

    /** One expiry per lot. */
    public static String expiry(String lotId) {
        return "EXPIRE:" + lotId;
    }

    /** One debt settlement per triggering reward transaction. */
    public static String debtSettlement(String rewardTransactionId) {
        return "DEBT:" + rewardTransactionId;
    }
}
