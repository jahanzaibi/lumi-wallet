package com.lumi.wallet.account;

import java.time.Instant;

import com.lumi.wallet.asset.Asset;
import com.lumi.wallet.common.Ids;

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
 * A customer's wallet for one asset (HELP.md section 31). A customer may hold SAR, USD and POINT
 * side by side, but only one account per asset.
 *
 * <p>Ledger liability accounts are also stored here under the reserved customer id
 * {@link #SYSTEM_CUSTOMER_ID}, so that every ledger entry references a real account row.
 */
@Entity
@Table(name = "wallet_account")
public class WalletAccount {

    /** Reserved customer id owning the ledger's liability accounts. */
    public static final String SYSTEM_CUSTOMER_ID = "SYSTEM";

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WalletAccount() {
    }

    private WalletAccount(String id, String customerId, Asset asset, AccountType accountType,
            AccountStatus status, Instant createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.asset = asset;
        this.accountType = accountType;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static WalletAccount forCustomer(String customerId, Asset asset, Instant now) {
        return new WalletAccount(Ids.newId("ACC"), customerId, asset, AccountType.CUSTOMER,
                AccountStatus.ACTIVE, now);
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public Asset getAsset() {
        return asset;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public void block() {
        this.status = AccountStatus.BLOCKED;
    }

    public void unblock() {
        this.status = AccountStatus.ACTIVE;
    }
}
