package com.lumi.wallet.messaging;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.lumi.wallet.config.WalletProperties;
import com.lumi.wallet.event.WalletTopology;

/**
 * Sends a failed message either to its next retry queue or to the dead-letter queue
 * (HELP.md sections 28, 29).
 *
 * <p>The attempt count travels with the message in a header, which is what lets the delay escalate
 * from 5 seconds to 30 to 5 minutes instead of looping forever at the first stage. Escalation cannot
 * be derived from broker state alone: a message on its fourth delivery is indistinguishable from its
 * first unless something counts.
 */
@Component
@ConditionalOnProperty(prefix = "wallet.rabbit", name = "enabled", havingValue = "true")
public class EventRetryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventRetryDispatcher.class);

    /** Why the message ended up here, so an operator reading the DLQ has somewhere to start. */
    public static final String FAILURE_HEADER = "x-wallet-failure";
    public static final String ORIGIN_QUEUE_HEADER = "x-wallet-origin-queue";

    private final RabbitTemplate template;
    private final WalletProperties properties;

    public EventRetryDispatcher(RabbitTemplate template, WalletProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    /**
     * Republishes {@code message} to wherever it belongs next.
     *
     * @param queueName the work queue the message failed on
     * @return true when the message was successfully handed on and may be acknowledged
     */
    public boolean rerouteFailed(Message message, String queueName, Throwable failure) {
        int attempt = attemptOf(message);
        int maxAttempts = properties.rabbit().maxAttempts();

        try {
            if (RetryPolicy.exhausted(attempt, maxAttempts)) {
                String dlq = WalletTopology.deadLetterQueue(queueName);
                republish(message, WalletTopology.DLQ_EXCHANGE, dlq, attempt, queueName, failure);
                log.error("Message from {} failed {} times and was dead-lettered to {}: {}",
                        queueName, attempt, dlq, failure.getMessage());
                return true;
            }

            int stage = RetryPolicy.stageFor(attempt);
            String retryQueue = WalletTopology.retryQueue(queueName, stage);
            Duration delay = RetryPolicy.delayForStage(stage);
            republish(message, WalletTopology.RETRY_EXCHANGE, retryQueue, attempt + 1, queueName,
                    failure);
            log.warn("Attempt {} of {} for a message from {} failed; retrying in {}s via {}: {}",
                    attempt, maxAttempts, queueName, delay.toSeconds(), retryQueue,
                    failure.getMessage());
            return true;

        } catch (RuntimeException e) {
            // The broker would not take the message. Refusing to acknowledge is the only safe
            // answer: better a redelivery than a silently dropped event.
            log.error("Could not reroute a failed message from {}; leaving it unacknowledged",
                    queueName, e);
            return false;
        }
    }

    /**
     * Sends a message straight to the dead-letter queue, skipping the retry ladder.
     *
     * <p>For failures that cannot improve with time — an unparseable body, an event with no id.
     * Retrying those would burn the whole attempt budget reproducing the same failure, which is
     * exactly what HELP.md section 28 warns against.
     *
     * @return true when the message was handed on and may be acknowledged
     */
    public boolean deadLetter(Message message, String queueName, Throwable failure) {
        String dlq = WalletTopology.deadLetterQueue(queueName);
        try {
            republish(message, WalletTopology.DLQ_EXCHANGE, dlq, attemptOf(message), queueName,
                    failure);
            log.error("Message from {} is unprocessable and went straight to {}: {}", queueName,
                    dlq, failure.getMessage());
            return true;
        } catch (RuntimeException e) {
            log.error("Could not dead-letter a message from {}; leaving it unacknowledged",
                    queueName, e);
            return false;
        }
    }

    private void republish(Message message, String exchange, String routingKey, int nextAttempt,
            String originQueue, Throwable failure) {

        Message copy = MessageBuilder.withBody(message.getBody())
                .copyProperties(message.getMessageProperties())
                .setHeader(RabbitTopologyConfig.ATTEMPT_HEADER, nextAttempt)
                .setHeader(ORIGIN_QUEUE_HEADER, originQueue)
                .setHeader(FAILURE_HEADER, describe(failure))
                .build();
        // The TTL and dead-letter wiring live on the retry queue itself, so nothing about the delay
        // needs to be set per message here.
        template.send(exchange, routingKey, copy);
    }

    private static String describe(Throwable failure) {
        String description = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return description.length() <= 500 ? description : description.substring(0, 500);
    }

    /** The 1-based attempt this delivery represents. */
    static int attemptOf(Message message) {
        Object header = message.getMessageProperties()
                .getHeader(RabbitTopologyConfig.ATTEMPT_HEADER);
        if (header instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        return 1;
    }
}
