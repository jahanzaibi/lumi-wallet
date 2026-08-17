package com.lumi.wallet.reward;

import java.math.BigDecimal;
import java.time.Instant;

import com.lumi.wallet.common.Ids;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An entry in the customer's reward history (HELP.md section 33).
 *
 * <p>Append-only in spirit: a reversal is a new row pointing at the original through
 * {@code reversalOf}, and the original's points are never edited (HELP.md section 21). The only
 * mutation allowed is the lifecycle status, which is what section 15 describes moving through
 * PENDING, AVAILABLE, VOIDED and so on.
 */
@Entity
@Table(name = "reward_transaction")
public class RewardTransaction {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "order_id", length = 100)
    private String orderId;

    @Column(name = "program_id", length = 64)
    private String programId;

    @Column(name = "rule_id", length = 64)
    private String ruleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private RewardTransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RewardTransactionStatus status;

    @Column(name = "points", nullable = false, precision = 19, scale = 4)
    private BigDecimal points;

    @Column(name = "order_amount", precision = 19, scale = 4)
    private BigDecimal orderAmount;

    @Column(name = "currency", length = 20)
    private String currency;

    @Column(name = "reversal_of", length = 64)
    private String reversalOf;

    @Column(name = "dedupe_key", nullable = false, length = 200)
    private String dedupeKey;

    @Column(name = "available_at")
    private Instant availableAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RewardTransaction() {
    }

    private RewardTransaction(String customerId, RewardTransactionType type,
            RewardTransactionStatus status, BigDecimal points, String dedupeKey, Instant now) {
        this.id = Ids.rewardTransactionId();
        this.customerId = customerId;
        this.type = type;
        this.status = status;
        this.points = points;
        this.dedupeKey = dedupeKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** A new earning, pending until its eligibility is satisfied (HELP.md section 14). */
    public static RewardTransaction earn(String customerId, String orderId, RewardRule rule,
            BigDecimal points, BigDecimal orderAmount, String currency, Instant availableAt,
            Instant expiresAt, Instant now) {
        RewardTransaction tx = new RewardTransaction(customerId, RewardTransactionType.EARN,
                RewardTransactionStatus.PENDING, points, DedupeKeys.earn(orderId), now);
        tx.orderId = orderId;
        tx.programId = rule.getProgram().getId();
        tx.ruleId = rule.getId();
        tx.orderAmount = orderAmount;
        tx.currency = currency;
        tx.availableAt = availableAt;
        tx.expiresAt = expiresAt;
        return tx;
    }

    /** A reversal of {@code original}; the original row is left untouched. */
    public static RewardTransaction reversalOf(RewardTransaction original, BigDecimal points,
            String dedupeKey, Instant now) {
        RewardTransaction tx = new RewardTransaction(original.customerId,
                RewardTransactionType.REVERSE, RewardTransactionStatus.COMPLETED, points,
                dedupeKey, now);
        tx.orderId = original.orderId;
        tx.programId = original.programId;
        tx.ruleId = original.ruleId;
        tx.currency = original.currency;
        tx.reversalOf = original.id;
        return tx;
    }

    /** A record that a redemption consumed points. */
    public static RewardTransaction redemption(String customerId, String orderId,
            String redemptionId, BigDecimal points, String currency, Instant now) {
        RewardTransaction tx = new RewardTransaction(customerId, RewardTransactionType.REDEEM,
                RewardTransactionStatus.COMPLETED, points, DedupeKeys.redeem(redemptionId), now);
        tx.orderId = orderId;
        tx.currency = currency;
        return tx;
    }

    /** A record that a lot expired. */
    public static RewardTransaction expiry(String customerId, String lotId, BigDecimal points,
            Instant now) {
        return new RewardTransaction(customerId, RewardTransactionType.EXPIRE,
                RewardTransactionStatus.COMPLETED, points, DedupeKeys.expiry(lotId), now);
    }

    /** A record that newly available points paid down reward debt (HELP.md section 22). */
    public static RewardTransaction debtSettlement(String customerId, String triggeringTxId,
            BigDecimal points, Instant now) {
        return new RewardTransaction(customerId, RewardTransactionType.DEBT_SETTLEMENT,
                RewardTransactionStatus.COMPLETED, points,
                DedupeKeys.debtSettlement(triggeringTxId), now);
    }

    /**
     * Points credited back because a committed redemption was reversed: the order it paid for was
     * cancelled, so the customer gets the points back as a fresh grant (HELP.md section 51).
     */
    public static RewardTransaction redemptionReversal(String customerId, String orderId,
            String redemptionId, BigDecimal points, Instant expiresAt, Instant now) {
        RewardTransaction tx = new RewardTransaction(customerId, RewardTransactionType.REVERSE,
                RewardTransactionStatus.AVAILABLE, points,
                DedupeKeys.redemptionReversal(redemptionId), now);
        tx.orderId = orderId;
        tx.availableAt = now;
        tx.expiresAt = expiresAt;
        return tx;
    }

    // -----------------------------------------------------------------------------------------
    // Lifecycle (HELP.md section 15)
    // -----------------------------------------------------------------------------------------

    public void markAvailable(Instant now) {
        this.status = RewardTransactionStatus.AVAILABLE;
        this.availableAt = now;
        this.updatedAt = now;
    }

    /** Cancelled before becoming available; no balance change is required (section 20). */
    public void markVoided(Instant now) {
        this.status = RewardTransactionStatus.VOIDED;
        this.updatedAt = now;
    }

    public void markReversed(Instant now) {
        this.status = RewardTransactionStatus.REVERSED;
        this.updatedAt = now;
    }

    public void markRedeemed(Instant now) {
        this.status = RewardTransactionStatus.REDEEMED;
        this.updatedAt = now;
    }

    public void markExpired(Instant now) {
        this.status = RewardTransactionStatus.EXPIRED;
        this.updatedAt = now;
    }

    public boolean isPending() {
        return status == RewardTransactionStatus.PENDING;
    }

    public boolean isAvailable() {
        return status == RewardTransactionStatus.AVAILABLE;
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getProgramId() {
        return programId;
    }

    public String getRuleId() {
        return ruleId;
    }

    public RewardTransactionType getType() {
        return type;
    }

    public RewardTransactionStatus getStatus() {
        return status;
    }

    public BigDecimal getPoints() {
        return points;
    }

    public BigDecimal getOrderAmount() {
        return orderAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReversalOf() {
        return reversalOf;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
