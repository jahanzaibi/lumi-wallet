package com.lumi.wallet.messaging;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.lumi.wallet.event.WalletTopology;

/**
 * The broker topology from HELP.md sections 23, 24, 28 and 29.
 *
 * <pre>
 * order.events   (topic) --order.confirmed/delivered/cancelled/completed/refunded--&gt; wallet.order-events
 * payment.events (topic) --payment.succeeded/failed/refunded-------------------------&gt; wallet.payment-events
 * wallet.events  (topic) &lt;-- the wallet's own events, published from the outbox
 *
 * on failure: wallet.retry --&gt; retry-1 (5s) --&gt; retry-2 (30s) --&gt; retry-3 (5m) --&gt; wallet.dlx --&gt; dlq
 * </pre>
 *
 * <h2>How the retry ladder actually escalates</h2>
 *
 * <p>The obvious wiring — give the work queue a dead-letter exchange pointing at a delay queue that
 * dead-letters back — produces an infinite loop at the first delay, because nothing in it counts
 * attempts. A message failing for the fourth time takes exactly the same path as the first.
 *
 * <p>So the escalation is explicit instead: {@link EventRetryDispatcher} reads the attempt count off
 * the message, republishes to the retry queue for that stage, and each retry queue's only job is to
 * hold the message for its TTL and then dead-letter it back to the work queue. That keeps the
 * counting somewhere it can be reasoned about and tested, and keeps the delays in the broker where
 * they do not occupy a consumer thread.
 *
 * <p>Declared only when {@code wallet.rabbit.enabled} is true, so that the service — whose inbound
 * handling and outbox are both broker-independent — still boots and tests without RabbitMQ.
 */
@Configuration
@ConditionalOnProperty(prefix = "wallet.rabbit", name = "enabled", havingValue = "true")
public class RabbitTopologyConfig {

    /** Header carrying how many times delivery has been attempted. */
    public static final String ATTEMPT_HEADER = "x-wallet-attempt";

    @Bean
    public TopicExchange walletExchange() {
        return new TopicExchange(WalletTopology.WALLET_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(WalletTopology.ORDER_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange paymentExchange() {
        return new TopicExchange(WalletTopology.PAYMENT_EXCHANGE, true, false);
    }

    /** Carries messages into their delay queue, and expired delays back to the work queue. */
    @Bean
    public DirectExchange retryExchange() {
        return new DirectExchange(WalletTopology.RETRY_EXCHANGE, true, false);
    }

    /** Terminal destination for messages that used up their attempts (HELP.md section 28). */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(WalletTopology.DLQ_EXCHANGE, true, false);
    }

    /**
     * The order work queue and its bindings (HELP.md section 24).
     *
     * <p>{@code order.created} is deliberately not bound: nothing in the wallet happens until the
     * payment succeeds (section 14).
     */
    @Bean
    public Declarables orderQueueDeclarables() {
        return workQueue(WalletTopology.ORDER_QUEUE, WalletTopology.ORDER_EXCHANGE, List.of(
                WalletTopology.ORDER_CONFIRMED_KEY,
                WalletTopology.ORDER_DELIVERED_KEY,
                WalletTopology.ORDER_CANCELLED_KEY,
                WalletTopology.ORDER_COMPLETED_KEY,
                WalletTopology.ORDER_REFUNDED_KEY));
    }

    /** The payment work queue and its bindings (HELP.md section 24). */
    @Bean
    public Declarables paymentQueueDeclarables() {
        return workQueue(WalletTopology.PAYMENT_QUEUE, WalletTopology.PAYMENT_EXCHANGE, List.of(
                WalletTopology.PAYMENT_SUCCEEDED_KEY,
                WalletTopology.PAYMENT_FAILED_KEY,
                WalletTopology.PAYMENT_REFUNDED_KEY));
    }

    /**
     * A work queue, its retry ladder and its dead-letter queue.
     *
     * @param sourceExchange the upstream topic exchange to bind to
     * @param routingKeys    the events this queue wants
     */
    private Declarables workQueue(String queueName, String sourceExchange,
            List<String> routingKeys) {

        List<Declarable> declarables = new ArrayList<>();

        // A safety net rather than the retry mechanism. Retries are republished explicitly, so this
        // dead-letter route only catches messages the broker rejects on the wallet's behalf — most
        // importantly one whose republish failed, which the listener then declines to acknowledge.
        // With spring.rabbitmq.listener.simple.default-requeue-rejected set to false, a rejected
        // message without a dead-letter exchange is discarded outright, and a silently dropped
        // order event is the one outcome worth engineering against.
        Map<String, Object> workArguments = new HashMap<>();
        workArguments.put("x-dead-letter-exchange", WalletTopology.DLQ_EXCHANGE);
        workArguments.put("x-dead-letter-routing-key", WalletTopology.deadLetterQueue(queueName));

        Queue work = QueueBuilder.durable(queueName).withArguments(workArguments).build();
        declarables.add(work);
        for (String routingKey : routingKeys) {
            declarables.add(BindingBuilder.bind(work)
                    .to(new TopicExchange(sourceExchange, true, false))
                    .with(routingKey));
        }

        // Expired delays come back here, addressed by the work queue's own name.
        declarables.add(new Binding(queueName, Binding.DestinationType.QUEUE,
                WalletTopology.RETRY_EXCHANGE, queueName, null));

        for (int stage = 1; stage <= RetryPolicy.stageCount(); stage++) {
            String retryName = WalletTopology.retryQueue(queueName, stage);
            Map<String, Object> arguments = new HashMap<>();
            // Hold for the stage's delay, then dead-letter back to the work queue for another go.
            arguments.put("x-message-ttl", RetryPolicy.delayForStage(stage).toMillis());
            arguments.put("x-dead-letter-exchange", WalletTopology.RETRY_EXCHANGE);
            arguments.put("x-dead-letter-routing-key", queueName);

            Queue retry = QueueBuilder.durable(retryName).withArguments(arguments).build();
            declarables.add(retry);
            declarables.add(new Binding(retryName, Binding.DestinationType.QUEUE,
                    WalletTopology.RETRY_EXCHANGE, retryName, null));
        }

        String dlqName = WalletTopology.deadLetterQueue(queueName);
        declarables.add(QueueBuilder.durable(dlqName).build());
        declarables.add(new Binding(dlqName, Binding.DestinationType.QUEUE,
                WalletTopology.DLQ_EXCHANGE, dlqName, null));

        return new Declarables(declarables);
    }

}
