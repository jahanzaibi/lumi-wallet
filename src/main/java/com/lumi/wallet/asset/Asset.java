package com.lumi.wallet.asset;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An asset a wallet can hold: a currency such as SAR, or the reward asset POINT.
 *
 * <p>Assets are reference data seeded by migration V2 and are never modified at runtime, hence no
 * setters.
 */
@Entity
@Table(name = "asset")
public class Asset {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private AssetType type;

    @Column(name = "decimal_scale", nullable = false)
    private int decimalScale;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Asset() {
    }

    public String getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public AssetType getType() {
        return type;
    }

    public int getDecimalScale() {
        return decimalScale;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isReward() {
        return type == AssetType.REWARD;
    }

    public boolean isMonetary() {
        return type == AssetType.MONETARY;
    }
}
