package com.lumi.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.lumi.wallet.event.EventEnvelope;
import com.lumi.wallet.event.WalletTopology;
import com.lumi.wallet.event.outbound.OutboxEvent;
import com.lumi.wallet.event.outbound.OutboxStatus;
import com.lumi.wallet.event.outbound.WalletEventType;
import com.lumi.wallet.redemption.Redemption;

import tools.jackson.databind.ObjectMapper;

/**
 * The outbox (HELP.md sections 55 to 58).
 *
 * <p>Outgoing events are written to {@code outbox_event} in the same transaction as the state change
 * they describe, and published afterwards. Publishing directly from the service would let the
 * database commit while the broker call failed, leaving other services permanently unaware of a
 * reward that really happened.
 *
 * <p>No broker runs in these tests, which is exactly the point: the wallet's own state is complete
 * and correct without one, and the pending rows are the evidence.
 */
class OutboxEventTest extends AbstractWalletTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("earning and availability queue the events from HELP.md section 55")
    void rewardLifecycleQueuesItsEvents() {
        String customerId = newCustomerId();
        String orderId = newOrderId();

        earnPending(customerId, orderId, new BigDecimal("500"));
        assertThat(eventTypesFor(customerId)).containsExactly(WalletEventType.REWARD_PENDING.name());

        rewards.applyEligibilityEvent(orderId,
                com.lumi.wallet.reward.EligibilityType.ORDER_DELIVERED);
        assertThat(eventTypesFor(customerId)).containsExactlyInAnyOrder(
                WalletEventType.REWARD_PENDING.name(),
                WalletEventType.REWARD_AVAILABLE.name());
    }

    @Test
    void redemptionLifecycleQueuesItsEvents() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("10000"));

        Redemption committed = redemptions.reserve(
                reserveRequest(null, customerId, newOrderId(), new BigDecimal("30.00")));
        redemptions.commit(committed.getId());

        Redemption released = redemptions.reserve(
                reserveRequest(null, customerId, newOrderId(), new BigDecimal("10.00")));
        redemptions.release(released.getId(), "PAYMENT_FAILED");

        // Order-insensitive on purpose: the test clock is frozen, so every row shares a created_at
        // and their relative order is not defined. What matters is that each transition queued its
        // event exactly once — two reservations, one commit, one release.
        assertThat(eventTypesFor(customerId)).containsExactlyInAnyOrder(
                WalletEventType.REWARD_PENDING.name(),
                WalletEventType.REWARD_AVAILABLE.name(),
                WalletEventType.REDEMPTION_RESERVED.name(),
                WalletEventType.REDEMPTION_COMPLETED.name(),
                WalletEventType.REDEMPTION_RESERVED.name(),
                WalletEventType.REDEMPTION_RELEASED.name());
    }

    @Test
    void reversalQueuesItsEvent() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, orderId, new BigDecimal("500"));

        process(event("ORDER_CANCELLED", payload("orderId", orderId, "customerId", customerId)));

        assertThat(eventTypesFor(customerId)).contains(WalletEventType.REWARD_REVERSED.name());
    }

    @Test
    @DisplayName("every queued event uses the envelope from HELP.md section 25")
    void queuedEventsUseTheStandardEnvelope() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        earnPending(customerId, orderId, new BigDecimal("500"));

        OutboxEvent queued = pendingFor(customerId).get(0);

        assertThat(queued.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(queued.getRetryCount()).isZero();
        assertThat(queued.getPublishedAt()).isNull();
        assertThat(queued.getExchange()).isEqualTo(WalletTopology.WALLET_EXCHANGE);
        assertThat(queued.getRoutingKey())
                .as("routing keys follow HELP.md section 23")
                .isEqualTo("wallet.reward.pending");

        EventEnvelope envelope = objectMapper.readValue(queued.getPayload(), EventEnvelope.class);

        assertThat(envelope.eventId()).isEqualTo(queued.getEventId());
        assertThat(envelope.eventType()).isEqualTo(WalletEventType.REWARD_PENDING.name());
        assertThat(envelope.eventVersion()).isEqualTo(EventEnvelope.CURRENT_VERSION);
        assertThat(envelope.source()).isEqualTo(WalletTopology.SOURCE);
        assertThat(envelope.occurredAt()).isNotNull();
        assertThat(envelope.correlationId()).isNotBlank();
        assertThat(envelope.requireString("customerId")).isEqualTo(customerId);
        assertThat(envelope.requireString("orderId")).isEqualTo(orderId);
        assertThat(envelope.requireDecimal("points")).isEqualByComparingTo(new BigDecimal("500"));
    }

    /**
     * The correlation id of the event that caused the change is carried into the event the wallet
     * emits, so one identifier follows a reward across all three services (HELP.md section 25).
     */
    @Test
    void theIncomingCorrelationIdIsPropagated() {
        String customerId = newCustomerId();
        String orderId = newOrderId();

        EventEnvelope incoming = event("PAYMENT_SUCCEEDED", payload(
                "orderId", orderId, "customerId", customerId,
                "orderAmount", new BigDecimal("500"), "currency", SAR, "orderType", RETAIL));
        process(incoming);

        EventEnvelope emitted = objectMapper.readValue(pendingFor(customerId).get(0).getPayload(),
                EventEnvelope.class);
        assertThat(emitted.correlationId()).isEqualTo(incoming.correlationId());
    }

    /** Events queued by a rolled-back transaction must not survive it (HELP.md section 58). */
    @Test
    void aFailedOperationQueuesNoEvent() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("100"));
        int before = pendingFor(customerId).size();

        try {
            // More points than the customer has: the reservation fails and rolls back.
            redemptions.reserve(reserveRequest(null, customerId, newOrderId(),
                    new BigDecimal("50.00")));
        } catch (RuntimeException expected) {
            // The failure is the point of the test.
        }

        assertThat(pendingFor(customerId)).as("no event for a change that did not happen")
                .hasSize(before);
    }

    private List<OutboxEvent> pendingFor(String customerId) {
        // Scoped by customer id in the payload, because the table is shared with every other test.
        return outboxEvents.findByStatus(OutboxStatus.PENDING).stream()
                .filter(event -> event.getPayload().contains(customerId))
                .sorted(java.util.Comparator.comparing(OutboxEvent::getCreatedAt)
                        .thenComparing(OutboxEvent::getId))
                .toList();
    }

    private List<String> eventTypesFor(String customerId) {
        return pendingFor(customerId).stream().map(OutboxEvent::getEventType).toList();
    }
}
