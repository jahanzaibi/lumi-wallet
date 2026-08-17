package com.lumi.wallet.event.inbound;

import java.time.Instant;

import com.lumi.wallet.common.Ids;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A consumed event id (HELP.md section 26). RabbitMQ gives delivery guarantees, not
 * exactly-once delivery, so the application has to assume the same event will arrive twice.
 *
 * <p>The unique constraint on {@code event_id} is mandatory per the spec, and it is what actually
 * enforces the rule: the insert is attempted before any work is done, so a duplicate loses the race
 * at the database rather than in application logic.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "consumer", nullable = false, length = 100)
    private String consumer;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProcessedEventStatus status;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    private ProcessedEvent(String eventId, String eventType, String consumer,
            ProcessedEventStatus status, Instant processedAt) {
        this.id = Ids.newId("PEV");
        this.eventId = eventId;
        this.eventType = eventType;
        this.consumer = consumer;
        this.status = status;
        this.processedAt = processedAt;
    }

    public static ProcessedEvent processed(String eventId, String eventType, String consumer,
            Instant now) {
        return new ProcessedEvent(eventId, eventType, consumer, ProcessedEventStatus.PROCESSED,
                now);
    }

    public static ProcessedEvent skipped(String eventId, String eventType, String consumer,
            String reason, Instant now) {
        ProcessedEvent event = new ProcessedEvent(eventId, eventType, consumer,
                ProcessedEventStatus.SKIPPED, now);
        event.errorMessage = truncate(reason);
        return event;
    }

    public void markFailed(String message, Instant now) {
        this.status = ProcessedEventStatus.FAILED;
        this.errorMessage = truncate(message);
        this.processedAt = now;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000);
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

    public String getConsumer() {
        return consumer;
    }

    public ProcessedEventStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
