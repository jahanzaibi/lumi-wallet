package com.lumi.wallet.redemption;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RedemptionItemRepository extends JpaRepository<RedemptionItem, String> {

    List<RedemptionItem> findByRedemptionId(String redemptionId);

    /**
     * How many of a lot's points are tied up in redemptions of a given state.
     *
     * <p>Reversal needs this to tell two very different situations apart. Points still sitting in
     * the lot can simply be taken back; points a COMPLETED redemption already spent cannot, and
     * become reward debt instead (HELP.md section 22). Without this the two are indistinguishable,
     * because both leave the lot with the same remaining balance.
     */
    @Query("""
            select coalesce(sum(i.points), 0)
            from RedemptionItem i, Redemption r
            where i.redemptionId = r.id
              and i.rewardLotId = :lotId
              and r.status = :status
            """)
    BigDecimal sumPointsForLotInState(@Param("lotId") String lotId,
            @Param("status") RedemptionStatus status);
}
