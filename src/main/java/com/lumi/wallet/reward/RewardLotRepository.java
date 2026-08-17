package com.lumi.wallet.reward;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RewardLotRepository extends JpaRepository<RewardLot, String> {

    /**
     * Lots to consume, earliest expiring first: FEFO (HELP.md section 19). Lots with no expiry sort
     * last, because a lot that never expires should only be spent once the perishable ones are
     * gone.
     *
     * <p>Deliberately not locked. Derby refuses {@code FOR UPDATE} on a query with ORDER BY, and
     * locking here would be redundant anyway: callers hold the customer's balance row lock, which
     * serialises every mutation of that customer's lots.
     */
    @Query("""
            select l from RewardLot l
            where l.customerId = :customerId
              and l.status = com.lumi.wallet.reward.RewardLotStatus.AVAILABLE
              and l.remainingPoints > 0
              and (l.expiresAt is null or l.expiresAt > :now)
            order by case when l.expiresAt is null then 1 else 0 end, l.expiresAt, l.createdAt
            """)
    List<RewardLot> findConsumableFefo(@Param("customerId") String customerId,
            @Param("now") Instant now);

    /** Points the customer can actually spend right now, recomputed from the lots themselves. */
    @Query("""
            select coalesce(sum(l.remainingPoints), 0) from RewardLot l
            where l.customerId = :customerId
              and l.status = com.lumi.wallet.reward.RewardLotStatus.AVAILABLE
              and (l.expiresAt is null or l.expiresAt > :now)
            """)
    BigDecimal sumAvailablePoints(@Param("customerId") String customerId,
            @Param("now") Instant now);

    /** Points earned but not yet usable, which a customer expects to see alongside their balance. */
    @Query("""
            select coalesce(sum(l.remainingPoints), 0) from RewardLot l
            where l.customerId = :customerId
              and l.status = com.lumi.wallet.reward.RewardLotStatus.PENDING
            """)
    BigDecimal sumPendingPoints(@Param("customerId") String customerId);

    List<RewardLot> findByRewardTransactionId(String rewardTransactionId);

    List<RewardLot> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    /**
     * Pending lots whose time-based delay has elapsed (HELP.md section 16). Ordered so that the
     * sweep is deterministic and resumable across batches.
     */
    @Query("""
            select l from RewardLot l
            where l.status = com.lumi.wallet.reward.RewardLotStatus.PENDING
              and l.availableAt is not null
              and l.availableAt <= :now
            order by l.availableAt
            """)
    List<RewardLot> findDueForAvailability(@Param("now") Instant now, Limit limit);

    /** Lots past their expiry with points still on them (HELP.md section 18). */
    @Query("""
            select l from RewardLot l
            where l.status = com.lumi.wallet.reward.RewardLotStatus.AVAILABLE
              and l.expiresAt is not null
              and l.expiresAt <= :now
              and l.remainingPoints > 0
            order by l.expiresAt
            """)
    List<RewardLot> findExpired(@Param("now") Instant now, Limit limit);
}
