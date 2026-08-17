package com.lumi.wallet.redemption;

import java.math.BigDecimal;
import java.time.Instant;

import com.lumi.wallet.common.Ids;
import com.lumi.wallet.reward.RewardProgram;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A stored calculation of what the wallet could contribute to an order (HELP.md sections 7, 8).
 *
 * <p>A quote deliberately moves no balance. Deducting points when a customer merely opens checkout
 * would lose them their points if they closed the browser (section 8), so only a reservation
 * changes anything.
 *
 * <p>It is persisted because it has to be verifiable later: section 43 requires the redemption to
 * check quote expiration, and a client-supplied quote that the wallet never issued must be
 * rejectable. It is still never <em>trusted</em>: every figure is recomputed at reservation time.
 */
@Entity
@Table(name = "redemption_quote")
public class RedemptionQuote {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "order_id", nullable = false, length = 100)
    private String orderId;

    @Column(name = "program_id", nullable = false, length = 64)
    private String programId;

    @Column(name = "currency", nullable = false, length = 20)
    private String currency;

    @Column(name = "order_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal orderAmount;

    @Column(name = "wallet_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal walletAmount;

    @Column(name = "remaining_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal remainingAmount;

    @Column(name = "points_required", nullable = false, precision = 19, scale = 4)
    private BigDecimal pointsRequired;

    @Column(name = "points_available", nullable = false, precision = 19, scale = 4)
    private BigDecimal pointsAvailable;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RedemptionQuote() {
    }

    private RedemptionQuote(String customerId, String orderId, RewardProgram program,
            String currency, BigDecimal orderAmount, BigDecimal walletAmount,
            BigDecimal remainingAmount, BigDecimal pointsRequired, BigDecimal pointsAvailable,
            Instant expiresAt, Instant now) {
        this.id = Ids.quoteId();
        this.customerId = customerId;
        this.orderId = orderId;
        this.programId = program.getId();
        this.currency = currency;
        this.orderAmount = orderAmount;
        this.walletAmount = walletAmount;
        this.remainingAmount = remainingAmount;
        this.pointsRequired = pointsRequired;
        this.pointsAvailable = pointsAvailable;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    public static RedemptionQuote of(String customerId, String orderId, RewardProgram program,
            String currency, BigDecimal orderAmount, BigDecimal walletAmount,
            BigDecimal remainingAmount, BigDecimal pointsRequired, BigDecimal pointsAvailable,
            Instant expiresAt, Instant now) {
        return new RedemptionQuote(customerId, orderId, program, currency, orderAmount,
                walletAmount, remainingAmount, pointsRequired, pointsAvailable, expiresAt, now);
    }

    public boolean hasExpiredAt(Instant when) {
        return expiresAt.isBefore(when);
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

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getOrderAmount() {
        return orderAmount;
    }

    public BigDecimal getWalletAmount() {
        return walletAmount;
    }

    public BigDecimal getRemainingAmount() {
        return remainingAmount;
    }

    public BigDecimal getPointsRequired() {
        return pointsRequired;
    }

    public BigDecimal getPointsAvailable() {
        return pointsAvailable;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
