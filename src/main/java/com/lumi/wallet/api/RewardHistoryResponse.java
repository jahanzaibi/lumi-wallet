package com.lumi.wallet.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.lumi.wallet.reward.RewardTransaction;
import com.lumi.wallet.reward.RewardTransactionStatus;
import com.lumi.wallet.reward.RewardTransactionType;

/**
 * {@code GET /api/v1/wallet/rewards/history} (HELP.md section 46).
 *
 * <p>Append-only, so this reads as a statement rather than a mutable list: an earning that was later
 * reversed appears as the original EARN plus a REVERSE that points back at it, never as an edited
 * earning (HELP.md sections 21, 60.5).
 */
public record RewardHistoryResponse(
        String customerId,
        int page,
        int size,
        long totalElements,
        List<RewardHistoryEntry> entries) {

    /**
     * @param reversalOf set on a REVERSE entry, naming the earning it takes back
     */
    public record RewardHistoryEntry(
            String rewardTransactionId,
            RewardTransactionType type,
            RewardTransactionStatus status,
            BigDecimal points,
            String orderId,
            String currency,
            BigDecimal orderAmount,
            String reversalOf,
            Instant availableAt,
            Instant expiresAt,
            Instant createdAt) {

        public static RewardHistoryEntry of(RewardTransaction transaction) {
            return new RewardHistoryEntry(
                    transaction.getId(),
                    transaction.getType(),
                    transaction.getStatus(),
                    transaction.getPoints(),
                    transaction.getOrderId(),
                    transaction.getCurrency(),
                    transaction.getOrderAmount(),
                    transaction.getReversalOf(),
                    transaction.getAvailableAt(),
                    transaction.getExpiresAt(),
                    transaction.getCreatedAt());
        }
    }
}
