package com.lumi.wallet.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.lumi.wallet.common.Amounts;
import com.lumi.wallet.idempotency.IdempotencyService;
import com.lumi.wallet.redemption.Redemption;
import com.lumi.wallet.redemption.RedemptionStatus;

/**
 * The redemption response shared by reserve, get, commit and release.
 *
 * <p>HELP.md shows three slightly different shapes for these (sections 9, 10, 11) — reserve quotes
 * an expiry, commit does not, release quotes only the points. This is their superset, and null
 * fields are omitted from the JSON, so each response still carries exactly the meaningful fields for
 * its stage. One type is worth having because these four endpoints describe one resource, and a
 * client that can parse a reservation should not need a second parser for the commit of it.
 *
 * <p>Implements {@link IdempotencyService.HasResourceId} so a stored idempotent response can be
 * traced back to the redemption it created.
 */
public record RedemptionResponse(
        String redemptionId,
        RedemptionStatus status,
        String customerId,
        String orderId,
        String currency,
        BigDecimal walletAmount,
        BigDecimal points,
        Instant expiresAt,
        Instant createdAt,
        Instant completedAt,
        Instant releasedAt) implements IdempotencyService.HasResourceId {

    public static RedemptionResponse of(Redemption redemption) {
        return new RedemptionResponse(
                redemption.getId(),
                redemption.getStatus(),
                redemption.getCustomerId(),
                redemption.getOrderId(),
                redemption.getCurrency(),
                Amounts.money(redemption.getWalletAmount()),
                Amounts.points(redemption.getPoints()),
                redemption.getExpiresAt(),
                redemption.getCreatedAt(),
                redemption.getCompletedAt(),
                redemption.getReleasedAt());
    }

    @Override
    public String resourceId() {
        return redemptionId;
    }
}
