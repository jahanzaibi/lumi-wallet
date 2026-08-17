package com.lumi.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.lumi.wallet.ledger.LedgerReferenceType;
import com.lumi.wallet.redemption.Redemption;
import com.lumi.wallet.redemption.RedemptionItem;
import com.lumi.wallet.reward.EligibilityType;
import com.lumi.wallet.reward.RewardLot;
import com.lumi.wallet.reward.RewardLotStatus;
import com.lumi.wallet.reward.RewardTransaction;
import com.lumi.wallet.reward.RewardTransactionStatus;

/**
 * Earning, eligibility, FEFO consumption and expiry (HELP.md sections 14 to 19).
 */
class RewardLifecycleTest extends AbstractWalletTest {

    // =============================================================================================
    // Earning and eligibility (HELP.md sections 14, 15, 16)
    // =============================================================================================

    @Test
    @DisplayName("an earning starts PENDING and cannot be spent (HELP.md section 14)")
    void earningIsPendingUntilEligible() {
        String customerId = newCustomerId();
        String orderId = newOrderId();

        RewardTransaction earning = earnPending(customerId, orderId, new BigDecimal("500"));

        assertThat(earning.getStatus()).isEqualTo(RewardTransactionStatus.PENDING);
        assertThat(earning.getPoints()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(availablePoints(customerId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rewards.pendingPoints(customerId)).isEqualByComparingTo(new BigDecimal("500"));

        // Nothing is posted while a reward is pending: no points have moved anywhere yet.
        assertThat(ledgerPostingCount(LedgerReferenceType.REWARD_TRANSACTION, earning.getId()))
                .isZero();

        List<RewardLot> lots = rewardLots.findByRewardTransactionId(earning.getId());
        assertThat(lots).hasSize(1);
        assertThat(lots.get(0).getStatus()).isEqualTo(RewardLotStatus.PENDING);
    }

    /**
     * The rule decides which event releases the reward (HELP.md section 16). A RETAIL reward waits
     * for delivery, so a stay completing is not its trigger.
     */
    @Test
    void onlyTheRulesOwnEventReleasesTheReward() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        earnPending(customerId, orderId, new BigDecimal("500"));

        assertThat(rewards.applyEligibilityEvent(orderId, EligibilityType.STAY_COMPLETED))
                .as("a hotel event does not release a retail reward").isZero();
        assertThat(availablePoints(customerId)).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(rewards.applyEligibilityEvent(orderId, EligibilityType.ORDER_DELIVERED))
                .isEqualTo(1);
        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("500"));
    }

    /**
     * A generic order completion also settles a reward waiting on a vertical-specific event, so an
     * order service that only publishes {@code order.completed} still works.
     */
    @Test
    void orderCompletedSatisfiesVerticalEvents() {
        String customerId = newCustomerId();
        String orderId = newOrderId();

        rewards.earn(new com.lumi.wallet.reward.RewardService.EarnRequest(customerId, orderId,
                "HOTEL", new BigDecimal("1000"), SAR, List.of()));

        assertThat(rewards.applyEligibilityEvent(orderId, EligibilityType.ORDER_COMPLETED))
                .isEqualTo(1);
        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("1000"));
    }

    /**
     * A time delay is the fallback when no business event exists (HELP.md section 16). The seeded
     * DEFAULT rule waits 7 days.
     */
    @Test
    @DisplayName("a time-based reward becomes available only after its delay")
    void timeBasedRewardWaitsForItsDelay() {
        String customerId = newCustomerId();
        String orderId = newOrderId();

        rewards.earn(new com.lumi.wallet.reward.RewardService.EarnRequest(customerId, orderId,
                "DEFAULT", new BigDecimal("400"), SAR, List.of()));

        assertThat(rewards.findEarningsDueForAvailability(10)).isEmpty();

        clock.advanceDays(6);
        assertThat(rewards.findEarningsDueForAvailability(10)).isEmpty();

        clock.advanceDays(2);
        List<String> due = rewards.findEarningsDueForAvailability(10);
        assertThat(due).hasSize(1);

        assertThat(rewards.makeAvailable(due.get(0))).isTrue();
        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("400"));

        // Making it available twice must not credit twice.
        assertThat(rewards.makeAvailable(due.get(0))).isFalse();
        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("400"));
        assertLedgerConsistent(customerId);
    }

    @Test
    void anOrderBelowTheRuleMinimumEarnsNothing() {
        String customerId = newCustomerId();
        // The seeded FLIGHT rule has a 100.00 minimum order amount.
        assertThat(rewards.earn(new com.lumi.wallet.reward.RewardService.EarnRequest(customerId,
                newOrderId(), "FLIGHT", new BigDecimal("50"), SAR, List.of()))).isEmpty();
        assertThat(availablePoints(customerId)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void earningTwiceForOneOrderIsRefused() {
        String customerId = newCustomerId();
        String orderId = newOrderId();

        RewardTransaction first = earnPending(customerId, orderId, new BigDecimal("500"));
        RewardTransaction second = earnPending(customerId, orderId, new BigDecimal("500"));

        assertThat(second.getId()).as("one earning per order (HELP.md section 60, rule 12)")
                .isEqualTo(first.getId());
        assertThat(rewards.pendingPoints(customerId)).isEqualByComparingTo(new BigDecimal("500"));
    }

    // =============================================================================================
    // FEFO consumption (HELP.md section 19)
    // =============================================================================================

    @Test
    @DisplayName("redemption consumes the earliest expiring lots first (HELP.md section 19)")
    void consumptionIsFirstExpiringFirstOut() {
        String customerId = newCustomerId();

        // Three lots earned a month apart, so their expiry dates differ in the same order.
        String orderA = newOrderId();
        grantAvailablePoints(customerId, orderA, new BigDecimal("1000"));
        clock.advanceDays(30);
        String orderB = newOrderId();
        earnPending(customerId, orderB, new BigDecimal("2000"));
        rewards.applyEligibilityEvent(orderB, EligibilityType.ORDER_DELIVERED);
        clock.advanceDays(30);
        String orderC = newOrderId();
        earnPending(customerId, orderC, new BigDecimal("500"));
        rewards.applyEligibilityEvent(orderC, EligibilityType.ORDER_DELIVERED);

        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("3500"));

        // Redeem 1,200: HELP.md's example takes 1,000 from lot A and 200 from lot B.
        Redemption redemption = redemptions.reserve(reserveRequest(null, customerId, newOrderId(),
                moneyForPoints(new BigDecimal("1200"))));

        RewardLot lotA = onlyLotOf(orderA);
        RewardLot lotB = onlyLotOf(orderB);
        RewardLot lotC = onlyLotOf(orderC);

        assertThat(lotA.getRemainingPoints()).as("lot A is spent first, in full")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(lotB.getRemainingPoints()).as("lot B covers the remaining 200")
                .isEqualByComparingTo(new BigDecimal("1800"));
        assertThat(lotC.getRemainingPoints()).as("lot C is untouched")
                .isEqualByComparingTo(new BigDecimal("500"));

        List<RedemptionItem> items = redemptions.itemsOf(redemption.getId());
        assertThat(items).as("the allocation is recorded per lot (HELP.md section 36)").hasSize(2);
        assertLotsMatchBalance(customerId);
    }

    // =============================================================================================
    // Expiry (HELP.md section 18)
    // =============================================================================================

    @Test
    @DisplayName("points left in a lot past its expiry are written off (HELP.md section 18)")
    void expiredPointsAreWrittenOff() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, orderId, new BigDecimal("1000"));

        assertThat(rewards.findLotsDueForExpiry(10)).isEmpty();

        // The seeded RETAIL rule expires points after 365 days.
        clock.advanceDays(366);

        List<String> due = rewards.findLotsDueForExpiry(10);
        String lotId = onlyLotOf(orderId).getId();
        assertThat(due).contains(lotId);

        assertThat(rewards.expireLot(lotId)).isTrue();

        assertThat(rewardBalance(customerId).getAvailableAmount())
                .as("the points leave the balance").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rewardLots.findById(lotId).orElseThrow().getStatus())
                .isEqualTo(RewardLotStatus.EXPIRED);
        assertThat(ledgerPostingCount(LedgerReferenceType.REWARD_LOT, lotId))
                .as("the write-off is recorded in the ledger").isEqualTo(1);
        assertLedgerConsistent(customerId);

        // Expiring twice must not debit twice.
        assertThat(rewards.expireLot(lotId)).isFalse();
        assertLedgerConsistent(customerId);
    }

    @Test
    void expiredPointsCannotBeRedeemed() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("1000"));

        clock.advanceDays(366);

        assertThat(availablePoints(customerId))
                .as("an expired lot is not consumable even before the sweep runs")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    private RewardLot onlyLotOf(String orderId) {
        RewardTransaction earning = rewardTransactions.findEarningsForOrder(orderId).get(0);
        List<RewardLot> lots = rewardLots.findByRewardTransactionId(earning.getId());
        assertThat(lots).hasSize(1);
        return lots.get(0);
    }
}
