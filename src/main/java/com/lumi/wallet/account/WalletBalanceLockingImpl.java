package com.lumi.wallet.account;

import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * Takes the row lock that serialises every change to one customer's points
 * (HELP.md sections 41, 42).
 *
 * <p>Two requests each redeeming 8,000 of 10,000 points cannot both pass the availability check,
 * because the second one waits here until the first commits and then reads the 2,000 that are left.
 *
 * <p>Always a primary-key load, and always the only lock a wallet operation takes. Locking exactly
 * one row per operation — this one — means the service has no lock ordering to get wrong, and it is
 * why the reward lots do not need locking of their own: anything touching a customer's lots holds
 * their balance lock first.
 */
class WalletBalanceLockingImpl implements WalletBalanceLocking {

    private final EntityManager entityManager;

    WalletBalanceLockingImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<WalletBalance> lockForUpdate(String walletAccountId) {
        // The balance may already be in this transaction's persistence context from an earlier read,
        // in which case find() would hand it back without going to the database. Locking then has to
        // be requested explicitly, or the "lock" would be a no-op against a stale copy.
        WalletBalance attached = entityManager.find(WalletBalance.class, walletAccountId,
                LockModeType.PESSIMISTIC_WRITE);
        if (attached != null) {
            entityManager.refresh(attached, LockModeType.PESSIMISTIC_WRITE);
        }
        return Optional.ofNullable(attached);
    }
}
