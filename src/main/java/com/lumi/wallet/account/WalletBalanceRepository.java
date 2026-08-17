package com.lumi.wallet.account;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The lock itself lives in {@link WalletBalanceLocking}; see that interface for why it cannot be a
 * {@code @Query} method here.
 */
public interface WalletBalanceRepository
        extends JpaRepository<WalletBalance, String>, WalletBalanceLocking {
}
