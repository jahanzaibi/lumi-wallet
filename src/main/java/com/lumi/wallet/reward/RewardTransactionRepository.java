package com.lumi.wallet.reward;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RewardTransactionRepository extends JpaRepository<RewardTransaction, String> {

    Optional<RewardTransaction> findByDedupeKey(String dedupeKey);

    boolean existsByDedupeKey(String dedupeKey);

    List<RewardTransaction> findByOrderId(String orderId);

    Page<RewardTransaction> findByCustomerIdOrderByCreatedAtDesc(String customerId,
            Pageable pageable);

    /**
     * The earnings for an order, which are what a cancellation or a refund reverses. Reversals and
     * redemptions are excluded: reversing a reversal is not a thing.
     */
    @Query("""
            select t from RewardTransaction t
            where t.orderId = :orderId
              and t.type = com.lumi.wallet.reward.RewardTransactionType.EARN
            order by t.createdAt
            """)
    List<RewardTransaction> findEarningsForOrder(@Param("orderId") String orderId);

    /**
     * How much of an earning has already been reversed. A partial refund reverses part of a reward
     * and leaves the rest live (HELP.md section 53), so several refunds against one order must not
     * be able to claw back more than was earned in the first place.
     */
    @Query("""
            select coalesce(sum(t.points), 0) from RewardTransaction t
            where t.reversalOf = :rewardTransactionId
            """)
    BigDecimal sumReversedPoints(@Param("rewardTransactionId") String rewardTransactionId);
}
