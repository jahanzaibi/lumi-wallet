package com.lumi.wallet.reward;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RewardTransactionItemRepository
        extends JpaRepository<RewardTransactionItem, String> {

    List<RewardTransactionItem> findByRewardTransactionId(String rewardTransactionId);
}
