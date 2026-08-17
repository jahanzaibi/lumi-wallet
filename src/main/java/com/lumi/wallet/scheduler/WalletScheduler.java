package com.lumi.wallet.scheduler;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.lumi.wallet.config.WalletProperties;
import com.lumi.wallet.event.outbound.OutboxPublisher;
import com.lumi.wallet.redemption.RedemptionService;
import com.lumi.wallet.reward.RewardService;

/**
 * The background sweeps: reservation expiry, reward availability, lot expiry and the outbox drain
 * (HELP.md sections 13, 16, 18, 50, 58).
 *
 * <h2>Why each item gets its own transaction</h2>
 *
 * <p>Every sweep reads a batch of ids and then processes them one at a time through a transactional
 * service method. A single customer whose row is locked, or whose data is somehow inconsistent, then
 * fails alone instead of aborting the batch — which matters most for the reservation sweep, where
 * the whole point is to give points back to customers whose checkout never came back.
 *
 * <p>The ids are read first and re-checked inside each transaction, because the state may have moved
 * on in between: a reservation listed as expired may have been committed a moment later. The service
 * methods therefore re-verify rather than trusting the list, which is HELP.md section 13's
 * requirement that expiry "must never blindly add points".
 */
@Component
@ConditionalOnProperty(prefix = "wallet.scheduler", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class WalletScheduler {

    private static final Logger log = LoggerFactory.getLogger(WalletScheduler.class);

    private final RedemptionService redemptions;
    private final RewardService rewards;
    private final ObjectProvider<OutboxPublisher> outboxPublisher;
    private final WalletProperties properties;

    public WalletScheduler(RedemptionService redemptions, RewardService rewards,
            ObjectProvider<OutboxPublisher> outboxPublisher, WalletProperties properties) {
        this.redemptions = redemptions;
        this.rewards = rewards;
        this.outboxPublisher = outboxPublisher;
        this.properties = properties;
    }

    /**
     * Releases reservations whose TTL elapsed with no commit or release (HELP.md sections 13, 50).
     *
     * <p>This is the safety net for a checkout that crashed: without it the customer's points would
     * stay locked indefinitely.
     */
    @Scheduled(cron = "${wallet.scheduler.redemption-expiry-cron}")
    public int releaseExpiredReservations() {
        List<String> expired = redemptions.findExpiredReservationIds(batchSize());
        int released = 0;
        for (String redemptionId : expired) {
            try {
                if (redemptions.releaseExpired(redemptionId)) {
                    released++;
                }
            } catch (RuntimeException e) {
                log.error("Could not release expired redemption {}", redemptionId, e);
            }
        }
        if (released > 0) {
            log.info("Released {} expired reservation(s)", released);
        }
        return released;
    }

    /**
     * Makes time-eligible rewards available (HELP.md section 16).
     *
     * <p>Only rewards whose rule has no business event to wait for reach this sweep; the rest are
     * released by their event.
     */
    @Scheduled(cron = "${wallet.scheduler.reward-availability-cron}")
    public int releaseDueRewards() {
        List<String> due = rewards.findEarningsDueForAvailability(batchSize());
        int released = 0;
        for (String rewardTransactionId : due) {
            try {
                if (rewards.makeAvailable(rewardTransactionId)) {
                    released++;
                }
            } catch (RuntimeException e) {
                log.error("Could not make reward {} available", rewardTransactionId, e);
            }
        }
        if (released > 0) {
            log.info("Made {} reward(s) available", released);
        }
        return released;
    }

    /** Writes off lots that reached their expiry date with points on them (HELP.md section 18). */
    @Scheduled(cron = "${wallet.scheduler.reward-expiry-cron}")
    public int expireRewardLots() {
        List<String> due = rewards.findLotsDueForExpiry(batchSize());
        int expired = 0;
        for (String lotId : due) {
            try {
                if (rewards.expireLot(lotId)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("Could not expire reward lot {}", lotId, e);
            }
        }
        if (expired > 0) {
            log.info("Expired {} reward lot(s)", expired);
        }
        return expired;
    }

    /**
     * Publishes committed events to RabbitMQ (HELP.md section 58).
     *
     * <p>Absent when no broker is configured, in which case the rows stay PENDING and this does
     * nothing — the wallet's own state is already correct either way, which is the property the
     * outbox exists to provide.
     */
    @Scheduled(cron = "${wallet.scheduler.outbox-publish-cron}")
    public int publishOutbox() {
        OutboxPublisher publisher = outboxPublisher.getIfAvailable();
        if (publisher == null) {
            return 0;
        }
        try {
            return publisher.publishPending(batchSize());
        } catch (RuntimeException e) {
            log.error("Outbox publishing failed; pending events remain queued", e);
            return 0;
        }
    }

    private int batchSize() {
        return properties.scheduler().batchSize();
    }
}
