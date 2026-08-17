package com.lumi.wallet.reward;

import java.math.BigDecimal;
import java.time.Instant;

import com.lumi.wallet.asset.Asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A reward program and, critically, the conversion rate between points and money.
 *
 * <p>HELP.md never states the rate: it shows 3,000 points settling 30.00 SAR (section 7) and
 * 500 SAR earning 500 points at 1% (section 14), both of which hold when 100 points are worth one
 * currency unit. Keeping the rate here as data rather than as a constant means a business can
 * change it, and means the earn rate and the redemption rate stay consistent with each other.
 */
@Entity
@Table(name = "reward_program")
public class RewardProgram {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "reward_asset_id", nullable = false)
    private Asset rewardAsset;

    @Column(name = "points_per_currency_unit", nullable = false, precision = 19, scale = 4)
    private BigDecimal pointsPerCurrencyUnit;

    /** Cap on how much of an order value may be settled with points. */
    @Column(name = "max_redemption_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxRedemptionPercent;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RewardProgram() {
    }

    public String getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Asset getRewardAsset() {
        return rewardAsset;
    }

    public BigDecimal getPointsPerCurrencyUnit() {
        return pointsPerCurrencyUnit;
    }

    public BigDecimal getMaxRedemptionPercent() {
        return maxRedemptionPercent;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
