package com.lumi.wallet.account;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletAccountRepository extends JpaRepository<WalletAccount, String> {

    @Query("select a from WalletAccount a where a.customerId = :customerId "
            + "and a.asset.code = :assetCode")
    Optional<WalletAccount> findByCustomerAndAsset(
            @Param("customerId") String customerId, @Param("assetCode") String assetCode);

    @Query("select a from WalletAccount a where a.customerId = :customerId order by a.asset.code")
    List<WalletAccount> findByCustomer(@Param("customerId") String customerId);

    @Query("select a from WalletAccount a where a.accountType = "
            + "com.lumi.wallet.account.AccountType.LIABILITY and a.asset.code = :assetCode")
    Optional<WalletAccount> findLiabilityAccount(@Param("assetCode") String assetCode);
}
