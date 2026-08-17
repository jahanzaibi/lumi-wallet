package com.lumi.wallet.reward;

import java.math.BigDecimal;
import java.time.Instant;

import com.lumi.wallet.common.Amounts;
import com.lumi.wallet.common.ErrorCode;
import com.lumi.wallet.common.Ids;
import com.lumi.wallet.common.WalletException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A tranche of points from one earning (HELP.md section 18). Lots are what make expiry and FEFO
 * consumption possible: without them there is no way to know which of a customer's points expire
 * in January and which in June.
 *
 * <p>Mutation is always performed while the caller holds the customer's balance row lock, so the
 * {@code @Version} column is a second line of defence rather than the primary one.
 */
@Entity
@Table(name = "reward_lot")
public class RewardLot {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "reward_transaction_id", nullable = false, length = 64)
    private String rewardTransactionId;

    @Column(name = "original_points", nullable = false, precision = 19, scale = 4)
    private BigDecimal originalPoints;

    @Column(name = "remaining_points", nullable = false, precision = 19, scale = 4)
    private BigDecimal remainingPoints;

    @Column(name = "available_at")
    private Instant availableAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RewardLotStatus status;

    /** Null until first persisted; see the note on {@link com.lumi.wallet.account.WalletBalance}. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RewardLot() {
    }

    private RewardLot(String customerId, String rewardTransactionId, BigDecimal points,
            RewardLotStatus status, Instant availableAt, Instant expiresAt, Instant now) {
        this.id = Ids.rewardLotId();
        this.customerId = customerId;
        this.rewardTransactionId = rewardTransactionId;
        this.originalPoints = points;
        this.remainingPoints = points;
        this.status = status;
        this.availableAt = availableAt;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** A lot that is not yet spendable, awaiting its eligibility event or delay. */
    public static RewardLot pending(RewardTransaction earning, Instant now) {
        return new RewardLot(earning.getCustomerId(), earning.getId(), earning.getPoints(),
                RewardLotStatus.PENDING, earning.getAvailableAt(), earning.getExpiresAt(), now);
    }

    /** A lot that is spendable immediately, used when points are credited back. */
    public static RewardLot available(RewardTransaction transaction, Instant now) {
        return new RewardLot(transaction.getCustomerId(), transaction.getId(),
                transaction.getPoints(), RewardLotStatus.AVAILABLE, now,
                transaction.getExpiresAt(), now);
    }

    // -----------------------------------------------------------------------------------------

    public void markAvailable(Instant now) {
        if (status != RewardLotStatus.PENDING) {
            throw WalletException.of(ErrorCode.REWARD_NOT_AVAILABLE,
                    "lot %s cannot become available from %s", id, status);
        }
        this.status = RewardLotStatus.AVAILABLE;
        this.availableAt = now;
        touch(now);
    }

    /** Cancelled while pending: no balance ever moved, so nothing to unwind (section 20). */
    public void markVoided(Instant now) {
        this.status = RewardLotStatus.VOIDED;
        this.remainingPoints = BigDecimal.ZERO;
        touch(now);
    }

    /**
     * Takes points out of this lot.
     *
     * @return the amount actually taken, which is capped at what the lot has left
     */
    public BigDecimal take(BigDecimal requested, Instant now) {
        BigDecimal taken = Amounts.min(remainingPoints, requested);
        if (!Amounts.isPositive(taken)) {
            return Amounts.ZERO_POINTS;
        }
        this.remainingPoints = this.remainingPoints.subtract(taken);
        if (Amounts.isZero(remainingPoints) && status == RewardLotStatus.AVAILABLE) {
            this.status = RewardLotStatus.CONSUMED;
        }
        touch(now);
        return taken;
    }

    /**
     * Puts points back, used when a reservation is released (HELP.md section 11). A lot that had
     * been fully consumed becomes available again.
     *
     * <p>A lot that expired while these points were locked also returns to AVAILABLE, rather than
     * staying EXPIRED with points on it. The points are past their expiry date and the next expiry
     * sweep will write them off — but it has to be able to <em>see</em> them to do that, and writing
     * them off through the sweep produces the ledger entry that records the loss. Leaving them
     * invisible instead would make the wallet's available balance disagree with the sum of its
     * available lots.
     */
    public void restore(BigDecimal amount, Instant now) {
        if (!Amounts.isPositive(amount)) {
            return;
        }
        BigDecimal restored = this.remainingPoints.add(amount);
        if (Amounts.gt(restored, originalPoints)) {
            throw WalletException.of(ErrorCode.INVALID_AMOUNT,
                    "restoring %s to lot %s would exceed its original %s", amount, id,
                    originalPoints);
        }
        this.remainingPoints = restored;
        if (status == RewardLotStatus.CONSUMED || status == RewardLotStatus.EXPIRED) {
            this.status = RewardLotStatus.AVAILABLE;
        }
        touch(now);
    }

    /** Writes off whatever is left because the lot passed its expiry date (section 18). */
    public BigDecimal expire(Instant now) {
        BigDecimal lost = remainingPoints;
        this.remainingPoints = BigDecimal.ZERO;
        this.status = RewardLotStatus.EXPIRED;
        touch(now);
        return lost;
    }

    public boolean isAvailable() {
        return status == RewardLotStatus.AVAILABLE;
    }

    public boolean isPending() {
        return status == RewardLotStatus.PENDING;
    }

    public boolean hasRemaining() {
        return Amounts.isPositive(remainingPoints);
    }

    public boolean isExpiredAt(Instant when) {
        return expiresAt != null && expiresAt.isBefore(when);
    }

    private void touch(Instant now) {
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getRewardTransactionId() {
        return rewardTransactionId;
    }

    public BigDecimal getOriginalPoints() {
        return originalPoints;
    }

    public BigDecimal getRemainingPoints() {
        return remainingPoints;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public RewardLotStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
