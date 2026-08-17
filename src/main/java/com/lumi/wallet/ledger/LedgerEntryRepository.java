package com.lumi.wallet.ledger;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, String> {

    List<LedgerEntry> findByLedgerTransactionId(String ledgerTransactionId);

    List<LedgerEntry> findByWalletAccountId(String walletAccountId);

    /**
     * The account's ledger position: credits minus debits. Used to assert the invariant that a
     * customer's reward position equals available + locked - debt.
     */
    @Query("""
            select coalesce(sum(case when e.direction = com.lumi.wallet.ledger.Direction.CREDIT
                                     then e.amount else -e.amount end), 0)
            from LedgerEntry e
            where e.walletAccountId = :accountId
            """)
    BigDecimal netPosition(@Param("accountId") String accountId);
}
