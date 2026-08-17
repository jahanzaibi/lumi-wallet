package com.lumi.wallet.ledger;

import java.math.BigDecimal;
import java.time.Instant;

import com.lumi.wallet.account.WalletAccount;
import com.lumi.wallet.common.Ids;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One side of a posting (HELP.md section 37). Append-only by design: no setters exist, so a
 * written entry can never be altered (HELP.md section 60, rule 3).
 */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "ledger_transaction_id", nullable = false, insertable = false, updatable = false,
            length = 64)
    private String ledgerTransactionId;

    @Column(name = "wallet_account_id", nullable = false, length = 64)
    private String walletAccountId;

    @Column(name = "asset_id", nullable = false, length = 64)
    private String assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private Direction direction;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    LedgerEntry(WalletAccount account, Direction direction, BigDecimal amount, Instant createdAt) {
        this.id = Ids.ledgerEntryId();
        this.walletAccountId = account.getId();
        this.assetId = account.getAsset().getId();
        this.direction = direction;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public String getWalletAccountId() {
        return walletAccountId;
    }

    public String getAssetId() {
        return assetId;
    }

    public Direction getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isDebit() {
        return direction == Direction.DEBIT;
    }

    public boolean isCredit() {
        return direction == Direction.CREDIT;
    }

    /** Signed contribution to the account's ledger position: credits add, debits subtract. */
    public BigDecimal signedAmount() {
        return isCredit() ? amount : amount.negate();
    }
}
