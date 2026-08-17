package com.lumi.wallet.asset;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepository extends JpaRepository<Asset, String> {

    Optional<Asset> findByCode(String code);
}
