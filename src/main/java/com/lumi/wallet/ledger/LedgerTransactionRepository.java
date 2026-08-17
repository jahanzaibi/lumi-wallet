package com.lumi.wallet.ledger;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, String> {

    Optional<LedgerTransaction> findByReferenceTypeAndReferenceIdAndTransactionType(
            LedgerReferenceType referenceType, String referenceId,
            LedgerTransactionType transactionType);

    List<LedgerTransaction> findByReferenceTypeAndReferenceId(
            LedgerReferenceType referenceType, String referenceId);
}
