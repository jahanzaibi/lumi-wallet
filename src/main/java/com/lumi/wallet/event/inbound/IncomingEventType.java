package com.lumi.wallet.event.inbound;

import java.util.Optional;

import com.lumi.wallet.reward.EligibilityType;

/**
 * The events the wallet reacts to (HELP.md sections 23, 24, 51).
 *
 * <p>Dispatch is on {@code eventType} from the envelope rather than on the routing key. The two
 * normally agree, but the envelope is the contract (section 25) and the routing key is transport
 * detail — and it is the envelope that survives a republish through the retry queues.
 *
 * <p>Unknown types are not an error. The wallet binds to a handful of order and payment events out
 * of a much larger stream, and an upstream service adding a new event should not dead-letter
 * messages here.
 */
public enum IncomingEventType {

    /** Nothing to do: the reward is earned on payment, not on confirmation. */
    ORDER_CONFIRMED(null),

    /** Retail eligibility (HELP.md section 16). */
    ORDER_DELIVERED(EligibilityType.ORDER_DELIVERED),

    /** Generic completion; also releases rewards waiting on a vertical-specific event. */
    ORDER_COMPLETED(EligibilityType.ORDER_COMPLETED),

    /** Hotel eligibility. Expected with this event type on the order.completed routing key. */
    STAY_COMPLETED(EligibilityType.STAY_COMPLETED),

    /** Flight eligibility. */
    TRAVEL_COMPLETED(EligibilityType.TRAVEL_COMPLETED),

    /** Service eligibility. */
    SERVICE_COMPLETED(EligibilityType.SERVICE_COMPLETED),

    /** Reverse the reward, release reservations, credit back committed redemptions (section 51). */
    ORDER_CANCELLED(null),

    /** Reverse part of the reward, per the rule's refund mode (sections 52, 53). */
    ORDER_REFUNDED(null),

    /** Earn the reward and commit any reservation the order was holding (sections 14, 48). */
    PAYMENT_SUCCEEDED(null),

    /** Release whatever the order was holding (sections 11, 49). */
    PAYMENT_FAILED(null),

    /** Treated exactly like an order refund. */
    PAYMENT_REFUNDED(null);

    private final EligibilityType eligibilityTrigger;

    IncomingEventType(EligibilityType eligibilityTrigger) {
        this.eligibilityTrigger = eligibilityTrigger;
    }

    /**
     * The eligibility event this represents, when it is one. A non-null value means handling it is
     * simply "make the order's pending rewards available if their rule agrees".
     */
    public EligibilityType eligibilityTrigger() {
        return eligibilityTrigger;
    }

    public boolean isEligibilityEvent() {
        return eligibilityTrigger != null;
    }

    public static Optional<IncomingEventType> from(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return Optional.empty();
        }
        for (IncomingEventType candidate : values()) {
            if (candidate.name().equalsIgnoreCase(eventType.trim())) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
