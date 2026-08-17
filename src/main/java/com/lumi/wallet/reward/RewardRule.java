package com.lumi.wallet.reward;

import java.math.BigDecimal;
import java.time.Instant;

import com.lumi.wallet.common.Amounts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * The earning rule for one order type (HELP.md section 17).
 */
@Entity
@Table(name = "reward_rule")
public class RewardRule {

    /** Order type used when an order carries none, or none that has its own rule. */
    public static final String DEFAULT_ORDER_TYPE = "DEFAULT";

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "program_id", nullable = false)
    private RewardProgram program;

    @Column(name = "order_type", nullable = false, length = 50)
    private String orderType;

    /** Fraction of the order value returned as reward value; 0.01 is 1%. */
    @Column(name = "earn_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal earnRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "eligibility_type", nullable = false, length = 30)
    private EligibilityType eligibilityType;

    @Column(name = "eligibility_days")
    private Integer eligibilityDays;

    @Column(name = "minimum_order_amount", precision = 19, scale = 4)
    private BigDecimal minimumOrderAmount;

    @Column(name = "maximum_points", precision = 19, scale = 4)
    private BigDecimal maximumPoints;

    @Column(name = "expiration_days")
    private Integer expirationDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_reversal_mode", nullable = false, length = 20)
    private RefundReversalMode refundReversalMode;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RewardRule() {
    }

    /**
     * Points earned on an order, before debt is considered.
     *
     * <p>Reward value is {@code orderAmount * earnRate} in currency, converted to points with the
     * program rate. With a 1% rate and 100 points per unit, a 500 SAR order earns 500 points,
     * which is the worked example in HELP.md section 14.
     */
    public BigDecimal pointsFor(BigDecimal orderAmount) {
        if (!isEligible(orderAmount)) {
            return Amounts.ZERO_POINTS;
        }
        BigDecimal rewardValue = orderAmount.multiply(earnRate);
        BigDecimal points = Amounts.pointsFor(rewardValue, program.getPointsPerCurrencyUnit());
        if (maximumPoints != null && Amounts.gt(points, maximumPoints)) {
            return Amounts.points(maximumPoints);
        }
        return points;
    }

    public boolean isEligible(BigDecimal orderAmount) {
        if (!active || orderAmount == null || !Amounts.isPositive(orderAmount)) {
            return false;
        }
        return minimumOrderAmount == null || !Amounts.lt(orderAmount, minimumOrderAmount);
    }

    /** True when {@code trigger} satisfies this rule's eligibility event. */
    public boolean isSatisfiedBy(EligibilityType trigger) {
        if (eligibilityType == trigger) {
            return true;
        }
        // A generic "order completed" also settles the vertical-specific completion events, so an
        // order service that only publishes order.completed still releases hotel and flight
        // rewards.
        return trigger == EligibilityType.ORDER_COMPLETED
                && (eligibilityType == EligibilityType.STAY_COMPLETED
                    || eligibilityType == EligibilityType.TRAVEL_COMPLETED
                    || eligibilityType == EligibilityType.SERVICE_COMPLETED
                    || eligibilityType == EligibilityType.ORDER_COMPLETED);
    }

    public String getId() {
        return id;
    }

    public RewardProgram getProgram() {
        return program;
    }

    public String getOrderType() {
        return orderType;
    }

    public BigDecimal getEarnRate() {
        return earnRate;
    }

    public EligibilityType getEligibilityType() {
        return eligibilityType;
    }

    public Integer getEligibilityDays() {
        return eligibilityDays;
    }

    public BigDecimal getMinimumOrderAmount() {
        return minimumOrderAmount;
    }

    public BigDecimal getMaximumPoints() {
        return maximumPoints;
    }

    public Integer getExpirationDays() {
        return expirationDays;
    }

    public RefundReversalMode getRefundReversalMode() {
        return refundReversalMode;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
