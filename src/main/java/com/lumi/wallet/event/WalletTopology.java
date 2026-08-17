package com.lumi.wallet.event;

/**
 * The exchange, queue and routing-key vocabulary from HELP.md sections 23, 24 and 29.
 *
 * <p>Names live here rather than in the AMQP configuration so that the outbox writer, the consumers
 * and the broker declarations cannot drift apart. A publisher that writes to {@code wallet.events}
 * while the binding says {@code wallet-events} fails silently — the message is accepted and dropped
 * — so these strings are worth stating exactly once.
 */
public final class WalletTopology {

    // Exchanges (HELP.md section 23). Topic exchanges, as recommended.
    public static final String WALLET_EXCHANGE = "wallet.events";
    public static final String ORDER_EXCHANGE = "order.events";
    public static final String PAYMENT_EXCHANGE = "payment.events";

    /**
     * Where retried and dead messages are routed. Not named in HELP.md, which describes the retry
     * chain (section 29) without naming the exchange that drives it.
     */
    public static final String RETRY_EXCHANGE = "wallet.retry";
    public static final String DLQ_EXCHANGE = "wallet.dlx";

    // Queues (HELP.md section 24).
    public static final String ORDER_QUEUE = "wallet.order-events";
    public static final String PAYMENT_QUEUE = "wallet.payment-events";

    /** Suffixes for the staged retry queues and the terminal dead-letter queue. */
    public static final String RETRY_SUFFIX = ".retry-";
    public static final String DLQ_SUFFIX = ".dlq";

    // Inbound routing keys the wallet binds to (HELP.md section 24).
    public static final String ORDER_CONFIRMED_KEY = "order.confirmed";
    public static final String ORDER_DELIVERED_KEY = "order.delivered";
    public static final String ORDER_CANCELLED_KEY = "order.cancelled";
    public static final String ORDER_COMPLETED_KEY = "order.completed";
    public static final String ORDER_REFUNDED_KEY = "order.refunded";

    public static final String PAYMENT_SUCCEEDED_KEY = "payment.succeeded";
    public static final String PAYMENT_FAILED_KEY = "payment.failed";
    public static final String PAYMENT_REFUNDED_KEY = "payment.refunded";

    /** The wallet's own source name, as it appears in the envelope (HELP.md section 25). */
    public static final String SOURCE = "wallet-service";

    private WalletTopology() {
    }

    public static String retryQueue(String queue, int stage) {
        return queue + RETRY_SUFFIX + stage;
    }

    public static String deadLetterQueue(String queue) {
        return queue + DLQ_SUFFIX;
    }
}
