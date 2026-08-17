package com.lumi.wallet.event.outbound;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.lumi.wallet.config.WalletProperties;
import com.lumi.wallet.support.WalletClock;

/**
 * Drains {@code outbox_event} to RabbitMQ (HELP.md sections 56 to 58).
 *
 * <p>This is the second half of the outbox pattern. The first half — writing the event in the same
 * transaction as the state change — is {@link WalletEventPublisher}. Splitting them is what makes the
 * database and the broker consistent: a commit that cannot be followed by a successful publish leaves
 * a row that will be published later, rather than an event that never existed.
 *
 * <p>Delivery is at-least-once, not exactly-once. If the broker accepts a message and this
 * transaction then fails, the row stays PENDING and is published again. That is the correct trade:
 * consumers deduplicate on {@code eventId} (section 26), and a duplicate event is harmless in a way
 * that a lost one is not.
 *
 * <p>Only present when a broker is configured. With {@code wallet.rabbit.enabled=false} the rows
 * simply accumulate, which is also what lets the outbox be asserted on in tests without a broker.
 */
@Component
@ConditionalOnProperty(prefix = "wallet.rabbit", name = "enabled", havingValue = "true")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String EVENT_TYPE_HEADER = "x-event-type";

    private final OutboxEventRepository outbox;
    private final RabbitTemplate template;
    private final WalletProperties properties;
    private final WalletClock clock;

    public OutboxPublisher(OutboxEventRepository outbox, RabbitTemplate template,
            WalletProperties properties, WalletClock clock) {
        this.outbox = outbox;
        this.template = template;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Publishes a batch of pending events, oldest first.
     *
     * @return how many reached the broker
     */
    @Transactional
    public int publishPending(int batchSize) {
        List<OutboxEvent> pending = outbox.findPending(Limit.of(batchSize));
        if (pending.isEmpty()) {
            return 0;
        }

        Instant now = clock.now();
        int published = 0;
        for (OutboxEvent event : pending) {
            try {
                template.send(event.getExchange(), event.getRoutingKey(), toMessage(event));
                event.markPublished(now);
                published++;
            } catch (AmqpException e) {
                // One unpublishable event must not hold up the rest of the batch, so the failure is
                // recorded on the row and the loop continues. Once the attempt budget is spent the
                // row is parked as FAILED for an operator rather than retried forever.
                event.markAttemptFailed(e.getMessage(), properties.rabbit().maxAttempts());
                log.error("Failed to publish outbox event {} ({}), attempt {}: {}",
                        event.getEventId(), event.getEventType(), event.getRetryCount(),
                        e.getMessage());
            }
        }

        log.debug("Published {} of {} pending outbox events", published, pending.size());
        return published;
    }

    /**
     * The stored JSON is sent as-is rather than being deserialised and re-serialised. The payload was
     * written inside the business transaction and is the record of what happened; re-encoding it here
     * would let a later change to the serialiser silently alter events already committed.
     */
    private Message toMessage(OutboxEvent event) {
        return MessageBuilder.withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(event.getEventId())
                .setHeader(EVENT_TYPE_HEADER, event.getEventType())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }
}
