package com.lumi.wallet.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables for the wallet service. Everything here is deliberately configuration rather than a
 * constant in code, because HELP.md calls out several of these values as "recommended initial"
 * settings that a business would expect to change.
 *
 * @param rewardAssetCode the reward asset code; reward points never share a balance with a
 *                        monetary asset (HELP.md section 2)
 * @param reservationTtl  how long a redemption reservation survives without a commit or release
 *                        (HELP.md section 13, recommended 5 minutes)
 * @param quoteTtl        how long a quote stays usable; a quote is informational and must not be
 *                        trusted once stale (HELP.md section 43)
 */
@ConfigurationProperties(prefix = "wallet")
public record WalletProperties(

        @DefaultValue("POINT") String rewardAssetCode,
        @DefaultValue("PT5M") Duration reservationTtl,
        @DefaultValue("PT15M") Duration quoteTtl,
        @DefaultValue Rabbit rabbit,
        @DefaultValue Scheduler scheduler) {

    /**
     * @param enabled     whether to attach the AMQP adapters. The inbound event logic and the
     *                    outbox are broker independent, so the service runs fully without a
     *                    broker; this only wires the listeners and the publisher.
     * @param maxAttempts delivery attempts before a message is dead-lettered (HELP.md section 28)
     */
    public record Rabbit(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("5") int maxAttempts) {
    }

    public record Scheduler(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0 * * * * *") String redemptionExpiryCron,
            @DefaultValue("15 * * * * *") String rewardAvailabilityCron,
            @DefaultValue("30 2 * * * *") String rewardExpiryCron,
            @DefaultValue("*/5 * * * * *") String outboxPublishCron,
            @DefaultValue("200") int batchSize) {
    }
}
