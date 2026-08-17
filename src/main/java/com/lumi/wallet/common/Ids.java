package com.lumi.wallet.common;

import java.util.UUID;

/**
 * Identifier generation. Ids are prefixed so that a value appearing in a log line or an event
 * payload is self describing, which matters when tracing a redemption across three services.
 */
public final class Ids {

    private Ids() {
    }

    public static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    public static String redemptionId() {
        return newId("RED");
    }

    public static String quoteId() {
        return newId("QUOTE");
    }

    public static String rewardTransactionId() {
        return newId("RTX");
    }

    public static String rewardLotId() {
        return newId("LOT");
    }

    public static String ledgerTransactionId() {
        return newId("LTX");
    }

    public static String ledgerEntryId() {
        return newId("LEN");
    }

    public static String eventId() {
        return newId("EVT");
    }

    public static String plain() {
        return UUID.randomUUID().toString();
    }
}
