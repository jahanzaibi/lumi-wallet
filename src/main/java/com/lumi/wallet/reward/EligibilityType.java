package com.lumi.wallet.reward;

/**
 * What makes a pending reward become available (HELP.md section 16).
 *
 * <p>The spec is explicit that "7 days" should not be the fundamental rule: a business event is
 * preferred, and a time delay is the fallback for when no such event exists.
 */
public enum EligibilityType {

    /** Retail: the goods arrived. */
    ORDER_DELIVERED,

    /** Hotel: the stay finished. */
    STAY_COMPLETED,

    /** Flight: the travel finished. */
    TRAVEL_COMPLETED,

    /** Services: the service was rendered. */
    SERVICE_COMPLETED,

    /** Generic completion, for order types with no more specific event. */
    ORDER_COMPLETED,

    /** Fallback when no business event is available: wait {@code eligibility_days}. */
    TIME;

    public boolean isTimeBased() {
        return this == TIME;
    }
}
