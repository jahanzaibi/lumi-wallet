package com.lumi.wallet.account;

import java.util.Optional;

/**
 * The pessimistic balance lock, as a repository fragment.
 *
 * <p>Separated from {@link WalletBalanceRepository} because it cannot be expressed as a derived or
 * {@code @Query} method on this stack: Hibernate's community Derby dialect appends the lock clause
 * twice when it decorates an HQL query, producing {@code for update with rs with rs}, which Derby
 * rejects outright. Loading the entity by primary key with an explicit lock mode goes through
 * Hibernate's entity loader instead, which emits the clause once and stays portable — the same call
 * produces {@code FOR UPDATE} on PostgreSQL, the intended production database (HELP.md section 59).
 */
public interface WalletBalanceLocking {

    /**
     * Loads a balance under a {@code PESSIMISTIC_WRITE} row lock, blocking until any concurrent
     * holder commits (HELP.md sections 41, 42).
     */
    Optional<WalletBalance> lockForUpdate(String walletAccountId);
}
