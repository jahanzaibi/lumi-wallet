package com.lumi.wallet.event.inbound;

public enum ProcessedEventStatus {

    /** Handled and committed. */
    PROCESSED,

    /** Recognised but irrelevant, e.g. an order with no reward to act on. */
    SKIPPED,

    /** Handling threw. Recorded for visibility; the message itself is retried or dead-lettered. */
    FAILED
}
