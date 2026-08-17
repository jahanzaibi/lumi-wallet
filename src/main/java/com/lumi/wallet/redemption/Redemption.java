package com.lumi.wallet.redemption;

import java.math.BigDecimal;
import java.time.Instant;

import com.lumi.wallet.common.Ids;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A reward redemption against an order (HELP.md section 35).
 *
 * <p>Keyed by {@code order_id + redemption_sequence} rather than by order alone. Section 35 offers
 * this as the alternative, and it is the one that works: once a reservation has been released
 * because a card payment failed, the customer must be able to retry the same order, which a plain
 * unique constraint on the order id would forbid. "At most one live redemption per order" is
 * enforced in {@link RedemptionService} while holding the customer's balance lock.
 */
@Entity
@Table(name = "redemption")
public class Redemption {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "order_id", nullable = false, length = 100)
    private String orderId;

    @Column(name = "redemption_sequence", nullable = false)
    private int redemptionSequence;

    @Column(name = "currency", nullable = false, length = 20)
    private String currency;

    @Column(name = "wallet_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal walletAmount;

    @Column(name = "points", nullable = false, precision = 19, scale = 4)
    private BigDecimal points;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RedemptionStatus status;

    @Column(name = "quote_id", length = 64)
    private String quoteId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * Null until first persisted; see the note on {@link com.lumi.wallet.account.WalletBalance}.
     * It matters more here than elsewhere: if {@code save} merged instead of inserting, the returned
     * managed copy — not this instance — would be the one the persistence context tracks, and the
     * CREATED to RESERVED transition applied to this object afterwards would be silently lost.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected Redemption() {
    }

    private Redemption(String customerId, String orderId, int sequence, String currency,
            BigDecimal walletAmount, BigDecimal points, String quoteId, Instant expiresAt,
            Instant now) {
        this.id = Ids.redemptionId();
        this.customerId = customerId;
        this.orderId = orderId;
        this.redemptionSequence = sequence;
        this.currency = currency;
        this.walletAmount = walletAmount;
        this.points = points;
        this.status = RedemptionStatus.CREATED;
        this.quoteId = quoteId;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    public static Redemption create(String customerId, String orderId, int sequence,
            String currency, BigDecimal walletAmount, BigDecimal points, String quoteId,
            Instant expiresAt, Instant now) {
        return new Redemption(customerId, orderId, sequence, currency, walletAmount, points,
                quoteId, expiresAt, now);
    }

    /** CREATED -> RESERVED: the points are now locked (HELP.md section 9). */
    public void reserve() {
        transitionTo(RedemptionStatus.RESERVED);
    }

    /** RESERVED -> COMPLETED: the points are permanently consumed (HELP.md section 10). */
    public void complete(Instant now) {
        transitionTo(RedemptionStatus.COMPLETED);
        this.completedAt = now;
    }

    /** RESERVED -> RELEASED: the points go back to available (HELP.md section 11). */
    public void release(Instant now) {
        transitionTo(RedemptionStatus.RELEASED);
        this.releasedAt = now;
    }

    private void transitionTo(RedemptionStatus target) {
        RedemptionStateMachine.assertCanTransition(id, status, target);
        this.status = target;
    }

    public boolean isReserved() {
        return status == RedemptionStatus.RESERVED;
    }

    public boolean isCompleted() {
        return status == RedemptionStatus.COMPLETED;
    }

    public boolean isReleased() {
        return status == RedemptionStatus.RELEASED;
    }

    /** Whether the reservation TTL has elapsed (HELP.md section 13). */
    public boolean hasExpiredAt(Instant when) {
        return expiresAt != null && expiresAt.isBefore(when);
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

    public int getRedemptionSequence() {
        return redemptionSequence;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getWalletAmount() {
        return walletAmount;
    }

    public BigDecimal getPoints() {
        return points;
    }

    public RedemptionStatus getStatus() {
        return status;
    }

    public String getQuoteId() {
        return quoteId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
