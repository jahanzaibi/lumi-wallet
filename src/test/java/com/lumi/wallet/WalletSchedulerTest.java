package com.lumi.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.lumi.wallet.redemption.Redemption;
import com.lumi.wallet.redemption.RedemptionStatus;
import com.lumi.wallet.reward.RewardLotStatus;
import com.lumi.wallet.reward.RewardTransaction;
import com.lumi.wallet.scheduler.WalletScheduler;

/**
 * The background sweeps (HELP.md sections 13, 16, 18, 50).
 *
 * <p>The scheduler is enabled here — the other tests run with it off — but every cron is pinned to a
 * date that will not arrive during the test run, so the sweeps only happen when this test calls them.
 * A sweep firing on its own timer mid-test would act on other tests' data and make failures
 * unreproducible.
 */
@TestPropertySource(properties = {
        "wallet.scheduler.enabled=true",
        "wallet.scheduler.redemption-expiry-cron=0 0 0 1 1 *",
        "wallet.scheduler.reward-availability-cron=0 0 0 1 1 *",
        "wallet.scheduler.reward-expiry-cron=0 0 0 1 1 *",
        "wallet.scheduler.outbox-publish-cron=0 0 0 1 1 *"
})
class WalletSchedulerTest extends AbstractWalletTest {

    @Autowired
    private WalletScheduler scheduler;

    @Test
    @DisplayName("the sweep releases a reservation nobody came back for (HELP.md section 50)")
    void expiredReservationsAreSweptUp() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("10000"));
        Redemption redemption = redemptions.reserve(
                reserveRequest(null, customerId, newOrderId(), new BigDecimal("30.00")));

        assertThat(scheduler.releaseExpiredReservations())
                .as("nothing to do before the TTL elapses").isZero();

        clock.advanceMinutes(6);
        assertThat(scheduler.releaseExpiredReservations()).isPositive();

        assertThat(redemptions.require(redemption.getId()).getStatus())
                .isEqualTo(RedemptionStatus.RELEASED);
        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("10000"));

        // Running again must not credit the points a second time.
        int secondPass = scheduler.releaseExpiredReservations();
        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(secondPass).as("the reservation is no longer RESERVED").isZero();
        assertLotsMatchBalance(customerId);
    }

    @Test
    @DisplayName("the sweep makes time-eligible rewards available (HELP.md section 16)")
    void dueRewardsAreMadeAvailable() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        rewards.earn(new com.lumi.wallet.reward.RewardService.EarnRequest(customerId, orderId,
                "DEFAULT", new BigDecimal("600"), SAR, java.util.List.of()));

        assertThat(scheduler.releaseDueRewards()).isZero();
        assertThat(availablePoints(customerId)).isEqualByComparingTo(BigDecimal.ZERO);

        // The seeded DEFAULT rule waits 7 days.
        clock.advanceDays(8);
        assertThat(scheduler.releaseDueRewards()).isPositive();

        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("600"));
        assertLedgerConsistent(customerId);
    }

    @Test
    @DisplayName("the sweep writes off expired lots (HELP.md section 18)")
    void expiredLotsAreSweptUp() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, orderId, new BigDecimal("1000"));

        assertThat(scheduler.expireRewardLots()).isZero();

        clock.advanceDays(366);
        assertThat(scheduler.expireRewardLots()).isPositive();

        RewardTransaction earning = rewardTransactions.findEarningsForOrder(orderId).get(0);
        assertThat(rewardLots.findByRewardTransactionId(earning.getId()).get(0).getStatus())
                .isEqualTo(RewardLotStatus.EXPIRED);
        assertThat(rewardBalance(customerId).getAvailableAmount())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertLedgerConsistent(customerId);
    }

    /**
     * Publishing is a no-op without a broker: the events stay pending and the wallet's own state is
     * unaffected, which is the property the outbox exists to give (HELP.md section 58).
     */
    @Test
    void outboxPublishingIsInertWithoutABroker() {
        assertThat(scheduler.publishOutbox()).isZero();
    }
}
