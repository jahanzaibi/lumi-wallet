package com.lumi.wallet.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.lumi.wallet.common.CorrelationId;
import com.lumi.wallet.event.EventEnvelope;
import com.lumi.wallet.event.WalletTopology;
import com.lumi.wallet.event.inbound.WalletEventProcessor;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The RabbitMQ consumers (HELP.md sections 24, 54).
 *
 * <p>Each listener does as little as possible: deserialise, delegate, and decide what to do if that
 * failed. All the reward logic and all the idempotency live in {@link WalletEventProcessor}, which is
 * an ordinary transactional bean and is therefore testable without a broker — the reason the wallet's
 * event handling can be fully exercised with {@code wallet.rabbit.enabled=false}.
 *
 * <h2>Acknowledgement</h2>
 *
 * <p>The database transaction commits before the message is acknowledged, which is the ordering
 * HELP.md section 27 asks for. With {@code acknowledge-mode: auto} that falls out of returning
 * normally: Spring acknowledges after the listener method returns, and the transaction has committed
 * by then.
 *
 * <p>On failure the message is republished into the retry ladder and <em>then</em> acknowledged,
 * because responsibility for it has been handed to the retry queue. If that republish fails the
 * exception propagates instead, and the broker routes the message to the queue's dead-letter
 * exchange, where an operator can find it. Either way it lands somewhere: a dropped order event is
 * the one outcome there is no recovering from.
 */
@Component
@ConditionalOnProperty(prefix = "wallet.rabbit", name = "enabled", havingValue = "true")
public class WalletEventListener {

    private static final Logger log = LoggerFactory.getLogger(WalletEventListener.class);

    private final WalletEventProcessor processor;
    private final EventRetryDispatcher retries;
    private final ObjectMapper objectMapper;

    public WalletEventListener(WalletEventProcessor processor, EventRetryDispatcher retries,
            ObjectMapper objectMapper) {
        this.processor = processor;
        this.retries = retries;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = WalletTopology.ORDER_QUEUE)
    public void onOrderEvent(Message message) {
        consume(message, WalletTopology.ORDER_QUEUE);
    }

    @RabbitListener(queues = WalletTopology.PAYMENT_QUEUE)
    public void onPaymentEvent(Message message) {
        consume(message, WalletTopology.PAYMENT_QUEUE);
    }

    private void consume(Message message, String queue) {
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getBody(), EventEnvelope.class);
        } catch (JacksonException | IllegalArgumentException e) {
            // Unparseable. Retrying would fail identically every time, so it goes straight to the
            // dead-letter queue: "do not endlessly retry malformed messages" (HELP.md section 28).
            if (!retries.deadLetter(message, queue, e)) {
                throw new AmqpRejectAndDontRequeueException("unparseable message", e);
            }
            return;
        }

        try {
            WalletEventProcessor.Outcome outcome = processor.process(envelope, queue);
            log.debug("Event {} from {} -> {}", envelope.eventId(), queue, outcome);
        } catch (RuntimeException e) {
            if (!retries.rerouteFailed(message, queue, e)) {
                throw e;
            }
        } finally {
            CorrelationId.clear();
        }
    }
}
