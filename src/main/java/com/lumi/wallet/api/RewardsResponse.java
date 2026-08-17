package com.lumi.wallet.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.lumi.wallet.reward.RewardLot;
import com.lumi.wallet.reward.RewardLotStatus;

/**
 * {@code GET /api/v1/wallet/rewards} (HELP.md section 46): the reward position a customer would
 * recognise as "my points".
 *
 * @param pendingPoints   earned but not yet usable, awaiting an eligibility event or delay
 *                        (HELP.md sections 14, 16)
 * @param lockedPoints    held by a checkout in progress (section 9)
 * @param rewardDebt      owed back after an already-spent reward was reversed; future rewards pay
 *                        this down first (section 22)
 * @param availableValue  what the available points are worth as money, at the programme's rate
 * @param lots            the individual tranches, which is where expiry dates live (section 18)
 */
public record RewardsResponse(
        String customerId,
        String asset,
        BigDecimal availablePoints,
        BigDecimal lockedPoints,
        BigDecimal pendingPoints,
        BigDecimal rewardDebt,
        BigDecimal pointsPerCurrencyUnit,
        BigDecimal availableValue,
        List<RewardLotView> lots) {

    /**
     * @param remainingPoints what is left of the lot; consumption is earliest-expiring-first, so a
     *                        lot may be partly spent (HELP.md section 19)
     */
    public record RewardLotView(
            String lotId,
            RewardLotStatus status,
            BigDecimal originalPoints,
            BigDecimal remainingPoints,
            Instant availableAt,
            Instant expiresAt) {

        public static RewardLotView of(RewardLot lot) {
            return new RewardLotView(lot.getId(), lot.getStatus(), lot.getOriginalPoints(),
                    lot.getRemainingPoints(), lot.getAvailableAt(), lot.getExpiresAt());
        }
    }
}
