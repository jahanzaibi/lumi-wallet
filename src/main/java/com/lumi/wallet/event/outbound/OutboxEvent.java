package com.lumi.wallet.event.outbound;

import java.time.Instant;

import com.lumi.wallet.common.Ids;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * An outgoing event awaiting publication (HELP.md sections 56, 57).
 *
 * <p>Written inside the same transaction as the balance change and the ledger entries, then
 * published asynchronously. Publishing directly from the service would allow the database to commit
 * while the broker call fails, leaving other services permanently unaware of a reward that did
 * happen.
 *
 * <p>{@code payload} is a CLOB rather than the VARCHAR(50000) sketched in section 57, because Derby
 * caps VARCHAR at 32672 characters.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "exchange", nullable = false, length = 100)
    private String exchange;

    @Column(name = "routing_key", nullable = false, length = 200)
    private String routingKey;

    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    private OutboxEvent(String eventId, String eventType, String exchange, String routingKey,
            String payload, Instant now) {
        this.id = Ids.newId("OBX");
        this.eventId = eventId;
        this.eventType = eventType;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.retryCount = 0;
        this.createdAt = now;
    }

    public static OutboxEvent pending(String eventId, String eventType, String exchange,
            String routingKey, String payload, Instant now) {
        return new OutboxEvent(eventId, eventType, exchange, routingKey, payload, now);
    }

    public void markPublished(Instant now) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = now;
        this.lastError = null;
    }

    /**
     * Records a failed attempt. Once the attempt budget is used up the event is parked as FAILED
     * rather than retried forever; a malformed event should not be retried endlessly
     * (HELP.md section 28).
     */
    public void markAttemptFailed(String error, int maxAttempts) {
        this.retryCount = this.retryCount + 1;
        this.lastError = error == null ? null
                : error.substring(0, Math.min(error.length(), 2000));
        if (this.retryCount >= maxAttempts) {
            this.status = OutboxStatus.FAILED;
        }
    }

    public String getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getExchange() {
        return exchange;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
