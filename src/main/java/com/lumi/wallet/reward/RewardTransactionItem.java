package com.lumi.wallet.reward;

import java.math.BigDecimal;

import com.lumi.wallet.common.Ids;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Item-level reward attribution (HELP.md section 53).
 *
 * <p>Needed for accurate partial refunds: if an order contained a 300 item and a 700 item and only
 * the first is refunded, reversing 30% of the order's reward is a guess, whereas reversing the
 * points attributed to that item is correct.
 */
@Entity
@Table(name = "reward_transaction_item")
public class RewardTransactionItem {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "reward_transaction_id", nullable = false, length = 64)
    private String rewardTransactionId;

    @Column(name = "order_item_id", nullable = false, length = 100)
    private String orderItemId;

    @Column(name = "item_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal itemAmount;

    @Column(name = "points", nullable = false, precision = 19, scale = 4)
    private BigDecimal points;

    protected RewardTransactionItem() {
    }

    public RewardTransactionItem(RewardTransaction transaction, String orderItemId,
            BigDecimal itemAmount, BigDecimal points) {
        this.id = Ids.newId("RTI");
        this.rewardTransactionId = transaction.getId();
        this.orderItemId = orderItemId;
        this.itemAmount = itemAmount;
        this.points = points;
    }

    public String getId() {
        return id;
    }

    public String getRewardTransactionId() {
        return rewardTransactionId;
    }

    public String getOrderItemId() {
        return orderItemId;
    }

    public BigDecimal getItemAmount() {
        return itemAmount;
    }

    public BigDecimal getPoints() {
        return points;
    }
}
