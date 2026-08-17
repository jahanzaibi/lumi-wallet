package com.lumi.wallet.redemption;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RedemptionRepository extends JpaRepository<Redemption, String> {

    /**
     * A redemption that still holds or has consumed points for this order. Used to reject a second
     * live redemption for the same order (HELP.md section 45,
     * {@code DUPLICATE_ORDER_REDEMPTION}), while still allowing a retry after a release.
     */
    @Query("""
            select r from Redemption r
            where r.orderId = :orderId
              and r.status in (com.lumi.wallet.redemption.RedemptionStatus.CREATED,
                               com.lumi.wallet.redemption.RedemptionStatus.RESERVED,
                               com.lumi.wallet.redemption.RedemptionStatus.COMPLETED)
            order by r.redemptionSequence desc
            """)
    List<Redemption> findLiveForOrder(@Param("orderId") String orderId);

    @Query("""
            select r from Redemption r
            where r.orderId = :orderId
              and r.status = com.lumi.wallet.redemption.RedemptionStatus.RESERVED
            order by r.redemptionSequence desc
            """)
    List<Redemption> findReservedForOrder(@Param("orderId") String orderId);

    @Query("""
            select r from Redemption r
            where r.orderId = :orderId
              and r.status = com.lumi.wallet.redemption.RedemptionStatus.COMPLETED
            order by r.redemptionSequence desc
            """)
    List<Redemption> findCompletedForOrder(@Param("orderId") String orderId);

    @Query("select coalesce(max(r.redemptionSequence), 0) from Redemption r "
            + "where r.orderId = :orderId")
    int maxSequenceForOrder(@Param("orderId") String orderId);

    /**
     * Reservations whose TTL elapsed with no commit or release (HELP.md sections 13, 50). The
     * scheduler releases these through the ordinary state machine.
     */
    @Query("""
            select r from Redemption r
            where r.status = com.lumi.wallet.redemption.RedemptionStatus.RESERVED
              and r.expiresAt is not null
              and r.expiresAt <= :now
            order by r.expiresAt
            """)
    List<Redemption> findExpiredReservations(@Param("now") Instant now, Limit limit);

    Page<Redemption> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);

    Optional<Redemption> findByOrderIdAndRedemptionSequence(String orderId, int redemptionSequence);
}
