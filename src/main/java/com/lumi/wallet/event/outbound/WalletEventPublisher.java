package com.lumi.wallet.event.outbound;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.lumi.wallet.common.CorrelationId;
import com.lumi.wallet.common.ErrorCode;
import com.lumi.wallet.common.Ids;
import com.lumi.wallet.common.WalletException;
import com.lumi.wallet.event.EventEnvelope;
import com.lumi.wallet.event.WalletTopology;
import com.lumi.wallet.support.WalletClock;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Records outgoing events in the outbox (HELP.md sections 56 to 58).
 *
 * <p>Deliberately does <em>not</em> touch RabbitMQ. Publishing from inside the service transaction
 * allows the database to commit while the broker call fails, which leaves other services
 * permanently unaware of a reward that really happened. Instead the event is written to
 * {@code outbox_event} in the same transaction as the balance change and the ledger entries, and
 * {@link OutboxPublisher} drains it afterwards.
 *
 * <p>{@link Propagation#MANDATORY} enforces that: calling this outside a transaction is a
 * programming error, and failing loudly here is better than writing an event that is not tied to
 * the state change it describes.
 */
@Service
public class WalletEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WalletEventPublisher.class);

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final WalletClock clock;

    public WalletEventPublisher(OutboxEventRepository outbox, ObjectMapper objectMapper,
            WalletClock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent publish(WalletEventType type, Map<String, Object> payload) {
        EventEnvelope envelope = EventEnvelope.of(Ids.eventId(), type.name(), WalletTopology.SOURCE,
                CorrelationId.getOrGenerate(), clock.now(), payload);

        OutboxEvent event = OutboxEvent.pending(envelope.eventId(), type.name(),
                WalletTopology.WALLET_EXCHANGE, type.routingKey(), serialize(envelope),
                clock.now());

        OutboxEvent saved = outbox.save(event);
        log.debug("Queued {} to outbox as {} (routing key {})", type, saved.getEventId(),
                type.routingKey());
        return saved;
    }

    private String serialize(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JacksonException e) {
            // An event that cannot be serialised must fail the business transaction rather than be
            // silently dropped: the state change and its event are supposed to be inseparable.
            throw new WalletException(ErrorCode.INTERNAL_ERROR,
                    "event payload could not be serialised: " + e.getMessage());
        }
    }
}
