package com.lumi.wallet.redemption;

import java.math.BigDecimal;

import com.lumi.wallet.common.Ids;
import com.lumi.wallet.reward.RewardLot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Exactly which lot gave up how many points to a redemption (HELP.md section 36).
 *
 * <p>This is what makes a release exact rather than approximate: the points go back to the very
 * lots they came from, preserving their original expiry dates. Without it, releasing a reservation
 * would have to invent an expiry date, and a customer could launder a soon-to-expire lot into a
 * fresh one by reserving and releasing.
 */
@Entity
@Table(name = "redemption_item")
public class RedemptionItem {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "redemption_id", nullable = false, length = 64)
    private String redemptionId;

    @Column(name = "reward_lot_id", nullable = false, length = 64)
    private String rewardLotId;

    @Column(name = "points", nullable = false, precision = 19, scale = 4)
    private BigDecimal points;

    protected RedemptionItem() {
    }

    public RedemptionItem(Redemption redemption, RewardLot lot, BigDecimal points) {
        this.id = Ids.newId("RDI");
        this.redemptionId = redemption.getId();
        this.rewardLotId = lot.getId();
        this.points = points;
    }

    public String getId() {
        return id;
    }

    public String getRedemptionId() {
        return redemptionId;
    }

    public String getRewardLotId() {
        return rewardLotId;
    }

    public BigDecimal getPoints() {
        return points;
    }
}
