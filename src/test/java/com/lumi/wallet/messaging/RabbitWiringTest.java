package com.lumi.wallet.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.lumi.wallet.event.WalletTopology;
import com.lumi.wallet.event.outbound.OutboxPublisher;
import com.lumi.wallet.support.WalletTestConfig;

/**
 * Checks the AMQP half of the service is wired correctly (HELP.md sections 23, 24, 28, 29).
 *
 * <p>No broker runs here, so this cannot prove a message survives a round trip. What it does prove is
 * the part that would otherwise go entirely unverified until deployment: that the beans exist, that
 * the declared topology is the one HELP.md describes, and that the retry queues carry the TTL and
 * dead-letter arguments that make the escalation work. A typo in a queue name or a missing
 * {@code x-message-ttl} is silent at runtime — RabbitMQ accepts the message and it simply never comes
 * back — so it is worth catching here.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(WalletTestConfig.class)
@TestPropertySource(properties = "wallet.rabbit.enabled=true")
class RabbitWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Declarables orderQueueDeclarables;

    @Autowired
    private Declarables paymentQueueDeclarables;

    /**
     * The context starts without a reachable broker. Connections are established lazily, so a wiring
     * mistake shows up here while a connectivity problem does not — which is the distinction this
     * test is for.
     */
    @Test
    void theAmqpBeansAreCreated() {
        assertThat(context.getBean(WalletEventListener.class)).isNotNull();
        assertThat(context.getBean(EventRetryDispatcher.class)).isNotNull();
        assertThat(context.getBean(OutboxPublisher.class))
                .as("publishing is only wired when a broker is configured").isNotNull();
    }

    @Test
    @DisplayName("the order queue binds the routing keys from HELP.md section 24")
    void orderQueueBindsTheSpecifiedRoutingKeys() {
        assertThat(routingKeysBoundTo(orderQueueDeclarables, WalletTopology.ORDER_QUEUE))
                .containsExactlyInAnyOrder(
                        "order.confirmed",
                        "order.delivered",
                        "order.cancelled",
                        "order.completed",
                        "order.refunded");
    }

    @Test
    void paymentQueueBindsTheSpecifiedRoutingKeys() {
        assertThat(routingKeysBoundTo(paymentQueueDeclarables, WalletTopology.PAYMENT_QUEUE))
                .containsExactlyInAnyOrder(
                        "payment.succeeded",
                        "payment.failed",
                        "payment.refunded");
    }

    /** order.created is deliberately not consumed: nothing happens until the payment succeeds. */
    @Test
    void orderCreatedIsNotConsumed() {
        assertThat(routingKeysBoundTo(orderQueueDeclarables, WalletTopology.ORDER_QUEUE))
                .doesNotContain("order.created");
    }

    @Test
    @DisplayName("each queue has the 5s/30s/5m retry ladder and a DLQ (HELP.md sections 28, 29)")
    void retryLadderAndDeadLetterQueueAreDeclared() {
        for (String workQueue : List.of(WalletTopology.ORDER_QUEUE, WalletTopology.PAYMENT_QUEUE)) {
            Declarables declarables = workQueue.equals(WalletTopology.ORDER_QUEUE)
                    ? orderQueueDeclarables
                    : paymentQueueDeclarables;

            assertThat(queueNames(declarables)).contains(
                    workQueue,
                    workQueue + ".retry-1",
                    workQueue + ".retry-2",
                    workQueue + ".retry-3",
                    workQueue + ".dlq");

            assertRetryQueue(declarables, workQueue, 1, 5_000L);
            assertRetryQueue(declarables, workQueue, 2, 30_000L);
            assertRetryQueue(declarables, workQueue, 3, 300_000L);
            assertWorkQueueDeadLetters(declarables, workQueue);
        }
    }

    /**
     * The work queue itself dead-letters, so a message the broker rejects cannot vanish. Retries do
     * not use this path — they are republished explicitly — but a failed republish relies on it.
     */
    private void assertWorkQueueDeadLetters(Declarables declarables, String workQueue) {
        Map<String, Object> arguments = queue(declarables, workQueue).getArguments();
        assertThat(arguments.get("x-dead-letter-exchange")).isEqualTo(WalletTopology.DLQ_EXCHANGE);
        assertThat(arguments.get("x-dead-letter-routing-key"))
                .isEqualTo(WalletTopology.deadLetterQueue(workQueue));
    }

    /**
     * A retry queue holds a message for its delay and then dead-letters it back to the work queue.
     * Both arguments matter: without the TTL the message waits forever, and without the dead-letter
     * routing key it never comes back.
     */
    private void assertRetryQueue(Declarables declarables, String workQueue, int stage,
            long expectedTtlMillis) {

        Queue retryQueue = queue(declarables, WalletTopology.retryQueue(workQueue, stage));
        Map<String, Object> arguments = retryQueue.getArguments();

        assertThat(arguments.get("x-message-ttl"))
                .as("retry stage %d of %s waits %dms", stage, workQueue, expectedTtlMillis)
                .isEqualTo(expectedTtlMillis);
        assertThat(arguments.get("x-dead-letter-exchange"))
                .isEqualTo(WalletTopology.RETRY_EXCHANGE);
        assertThat(arguments.get("x-dead-letter-routing-key"))
                .as("an expired delay returns to the work queue for another attempt")
                .isEqualTo(workQueue);
    }

    private static List<String> queueNames(Declarables declarables) {
        return declarables.getDeclarablesByType(Queue.class).stream().map(Queue::getName).toList();
    }

    private static Queue queue(Declarables declarables, String name) {
        return declarables.getDeclarablesByType(Queue.class).stream()
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no queue declared named " + name));
    }

    private static List<String> routingKeysBoundTo(Declarables declarables, String queueName) {
        return declarables.getDeclarablesByType(Binding.class).stream()
                .filter(binding -> queueName.equals(binding.getDestination()))
                .filter(binding -> !WalletTopology.RETRY_EXCHANGE.equals(binding.getExchange()))
                .map(Binding::getRoutingKey)
                .toList();
    }
}
