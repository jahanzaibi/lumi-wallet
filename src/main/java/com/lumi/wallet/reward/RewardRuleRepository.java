package com.lumi.wallet.reward;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RewardRuleRepository extends JpaRepository<RewardRule, String> {

    @Query("""
            select r from RewardRule r
            where r.orderType = :orderType and r.active = true
            """)
    Optional<RewardRule> findActiveByOrderType(@Param("orderType") String orderType);
}
