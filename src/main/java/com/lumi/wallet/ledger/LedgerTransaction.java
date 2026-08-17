package com.lumi.wallet.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.lumi.wallet.common.Ids;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * One balanced posting (HELP.md section 37). Immutable once written: there are no setters and no
 * way to remove entries, because a completed transaction may not be deleted and a reversal has to
 * be a new transaction (HELP.md section 60, rules 3 to 5).
 */
@Entity
@Table(name = "ledger_transaction")
public class LedgerTransaction {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 50)
    private LedgerReferenceType referenceType;

    @Column(name = "reference_id", nullable = false, length = 100)
    private String referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 50)
    private LedgerTransactionType transactionType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = false)
    @JoinColumn(name = "ledger_transaction_id", nullable = false)
    private List<LedgerEntry> entries = new ArrayList<>();

    protected LedgerTransaction() {
    }

    LedgerTransaction(LedgerReferenceType referenceType, String referenceId,
            LedgerTransactionType transactionType, Instant createdAt) {
        this.id = Ids.ledgerTransactionId();
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.transactionType = transactionType;
        this.createdAt = createdAt;
    }

    void addEntry(LedgerEntry entry) {
        this.entries.add(entry);
    }

    public String getId() {
        return id;
    }

    public LedgerReferenceType getReferenceType() {
        return referenceType;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public LedgerTransactionType getTransactionType() {
        return transactionType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<LedgerEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
