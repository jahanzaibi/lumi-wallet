package com.lumi.wallet.event.inbound;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lumi.wallet.common.CorrelationId;
import com.lumi.wallet.event.EventEnvelope;
import com.lumi.wallet.redemption.RedemptionService;
import com.lumi.wallet.reward.RewardService;
import com.lumi.wallet.support.WalletClock;

/**
 * Applies an inbound event to the wallet, exactly once (HELP.md sections 26, 27, 51 to 54).
 *
 * <p>The transaction boundary is the whole point of this class:
 *
 * <pre>
 * BEGIN
 *   has this event been processed?  -> yes: return, nothing to do
 *   record the event id
 *   do the work (reward, ledger, outbox)
 * COMMIT
 * ACK
 * </pre>
 *
 * <p>The {@code processed_event} row and the work commit <em>together</em>. That is what makes the
 * guarantee two-sided: a redelivery of a handled event does nothing, and a redelivery of an event
 * whose handling failed genuinely retries, because the row rolled back with the work. Recording the
 * event id in a separate transaction would create the worst possible outcome — an event marked
 * handled whose effects were rolled back.
 *
 * <p>The existence check comes before the insert rather than relying on catching the unique
 * constraint. A failed insert inside a JPA transaction leaves the persistence context unusable, so
 * "insert and catch" could not then commit anything. The constraint is still the real backstop for a
 * genuine race: the loser's exception propagates, the message is retried, and the check catches it
 * the second time.
 */
@Service
public class WalletEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(WalletEventProcessor.class);

    // Payload fields the wallet reads (HELP.md sections 25, 51).
    private static final String FIELD_ORDER_ID = "orderId";
    private static final String FIELD_CUSTOMER_ID = "customerId";
    private static final String FIELD_ORDER_AMOUNT = "orderAmount";
    private static final String FIELD_ORDER_TYPE = "orderType";
    private static final String FIELD_CURRENCY = "currency";
    private static final String FIELD_REFUND_AMOUNT = "refundAmount";
    private static final String FIELD_REFUND_ID = "refundId";
    private static final String FIELD_REFUNDED_ITEMS = "refundedOrderItemIds";
    private static final String FIELD_ITEMS = "items";
    private static final String FIELD_ITEM_ID = "orderItemId";
    private static final String FIELD_ITEM_AMOUNT = "amount";
    private static final String FIELD_REASON = "reason";

    private final ProcessedEventRepository processedEvents;
    private final RewardService rewards;
    private final RedemptionService redemptions;
    private final WalletClock clock;

    public WalletEventProcessor(ProcessedEventRepository processedEvents, RewardService rewards,
            RedemptionService redemptions, WalletClock clock) {
        this.processedEvents = processedEvents;
        this.rewards = rewards;
        this.redemptions = redemptions;
        this.clock = clock;
    }

    /**
     * Handles one event.
     *
     * @param consumer which queue this arrived on, recorded for diagnostics
     * @return what happened, so the caller can log it; any failure is thrown, not returned
     */
    @Transactional
    public Outcome process(EventEnvelope envelope, String consumer) {
        if (envelope == null || envelope.eventId() == null || envelope.eventId().isBlank()) {
            // Without an event id there is no way to deduplicate, and silently processing an
            // unidentifiable event twice is worse than refusing it.
            throw new IllegalArgumentException("event is missing an eventId; cannot deduplicate");
        }

        // Carry the publisher's correlation id, so a reward event the wallet emits in response can
        // be traced back to the order event that caused it (HELP.md section 25).
        if (envelope.correlationId() != null && !envelope.correlationId().isBlank()) {
            CorrelationId.set(envelope.correlationId());
        }

        if (processedEvents.existsByEventId(envelope.eventId())) {
            log.info("Event {} ({}) was already processed; ignoring duplicate", envelope.eventId(),
                    envelope.eventType());
            return Outcome.DUPLICATE;
        }

        Optional<IncomingEventType> type = IncomingEventType.from(envelope.eventType());
        if (type.isEmpty()) {
            // Recorded as SKIPPED rather than retried: replaying an event the wallet has no handler
            // for would fail identically every time, all the way to the dead-letter queue.
            processedEvents.save(ProcessedEvent.skipped(envelope.eventId(), envelope.eventType(),
                    consumer, "no handler for this event type", clock.now()));
            log.debug("Event {} has type '{}', which the wallet does not handle",
                    envelope.eventId(), envelope.eventType());
            return Outcome.SKIPPED;
        }

        processedEvents.save(ProcessedEvent.processed(envelope.eventId(), envelope.eventType(),
                consumer, clock.now()));
        handle(type.get(), envelope);

        log.info("Processed event {} ({}) from {}", envelope.eventId(), envelope.eventType(),
                envelope.source());
        return Outcome.PROCESSED;
    }

    private void handle(IncomingEventType type, EventEnvelope envelope) {
        if (type.isEligibilityEvent()) {
            // HELP.md section 16: a business event, not a timer, is what makes a reward usable.
            int released = rewards.applyEligibilityEvent(envelope.requireString(FIELD_ORDER_ID),
                    type.eligibilityTrigger());
            log.debug("Eligibility event {} released {} earnings", type, released);
            return;
        }

        switch (type) {
            case PAYMENT_SUCCEEDED -> onPaymentSucceeded(envelope);
            case PAYMENT_FAILED -> onPaymentFailed(envelope);
            case ORDER_CANCELLED -> onOrderCancelled(envelope);
            case ORDER_REFUNDED, PAYMENT_REFUNDED -> onRefund(envelope);
            case ORDER_CONFIRMED -> log.debug("Order {} confirmed; reward follows the payment",
                    envelope.optionalString(FIELD_ORDER_ID));
            default -> log.debug("No action for {}", type);
        }
    }

    /**
     * The money went through (HELP.md sections 14, 48).
     *
     * <p>Two things follow: the order earns its reward, and any reservation the order was holding is
     * committed. The commit is here as well as on the API because this event is the reliable path —
     * a checkout that crashes after paying still gets the redemption committed, and committing twice
     * is a no-op (section 40).
     */
    private void onPaymentSucceeded(EventEnvelope envelope) {
        String orderId = envelope.requireString(FIELD_ORDER_ID);

        redemptions.commitReservedForOrder(orderId);

        rewards.earn(new RewardService.EarnRequest(
                envelope.requireString(FIELD_CUSTOMER_ID),
                orderId,
                envelope.optionalString(FIELD_ORDER_TYPE),
                envelope.requireDecimal(FIELD_ORDER_AMOUNT),
                envelope.optionalString(FIELD_CURRENCY),
                orderItems(envelope)));
    }

    /** The money failed, so the wallet gives the points back (HELP.md sections 11, 49). */
    private void onPaymentFailed(EventEnvelope envelope) {
        redemptions.releaseReservedForOrder(envelope.requireString(FIELD_ORDER_ID),
                "PAYMENT_FAILED");
    }

    /**
     * The order was cancelled (HELP.md section 51).
     *
     * <p>The order of these three steps matters. Reversing the reward first lets the reward debt it
     * may create be paid off immediately by the points that come back from the committed redemption.
     * Crediting those points back first would instead leave the customer holding both the returned
     * points and an equal debt — arithmetically the same, but visible to them as a balance they
     * cannot spend and a debt they do not understand.
     */
    private void onOrderCancelled(EventEnvelope envelope) {
        String orderId = envelope.requireString(FIELD_ORDER_ID);
        String reason = Optional.ofNullable(envelope.optionalString(FIELD_REASON))
                .orElse("ORDER_CANCELLED");

        redemptions.releaseReservedForOrder(orderId, reason);
        rewards.reverseForCancellation(orderId);
        redemptions.reverseCompletedForOrder(orderId);
    }

    /**
     * Money went back (HELP.md sections 52, 53). How much reward follows it is the rule's decision,
     * not this method's: a refund does not automatically mean a full reward reversal.
     */
    private void onRefund(EventEnvelope envelope) {
        String orderId = envelope.requireString(FIELD_ORDER_ID);
        // Keyed on the refund so that several partial refunds of one order each reverse their own
        // share; the event id is the fallback when upstream does not identify the refund.
        String refundId = Optional.ofNullable(envelope.optionalString(FIELD_REFUND_ID))
                .orElse(envelope.eventId());

        rewards.reverseForRefund(new RewardService.RefundRequest(orderId, refundId,
                envelope.optionalDecimal(FIELD_REFUND_AMOUNT),
                envelope.optionalStringList(FIELD_REFUNDED_ITEMS)));
    }

    /** The order's item breakdown, when supplied, enabling accurate partial refunds (section 53). */
    private List<RewardService.OrderItem> orderItems(EventEnvelope envelope) {
        List<RewardService.OrderItem> items = new ArrayList<>();
        for (Map<String, Object> raw : envelope.optionalObjectList(FIELD_ITEMS)) {
            Object id = raw.get(FIELD_ITEM_ID);
            Object amount = raw.get(FIELD_ITEM_AMOUNT);
            if (id == null || amount == null) {
                continue;
            }
            try {
                items.add(new RewardService.OrderItem(id.toString(),
                        new BigDecimal(amount.toString().trim())));
            } catch (NumberFormatException e) {
                log.warn("Ignoring order item {} with unreadable amount '{}' on event {}", id,
                        amount, envelope.eventId());
            }
        }
        return items;
    }

    /** What handling an event did, for the caller's log line. */
    public enum Outcome {

        PROCESSED,

        /** Already handled; the redelivery was ignored (HELP.md section 26). */
        DUPLICATE,

        /** Recognised as irrelevant to the wallet. */
        SKIPPED
    }
}
