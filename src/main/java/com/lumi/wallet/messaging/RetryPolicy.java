package com.lumi.wallet.messaging;

import java.time.Duration;
import java.util.List;

/**
 * The staged retry ladder from HELP.md sections 28 and 29.
 *
 * <pre>
 * attempt 1 fails -> wait 5 seconds
 * attempt 2 fails -> wait 30 seconds
 * attempt 3 fails -> wait 5 minutes
 * budget spent    -> dead-letter queue
 * </pre>
 *
 * <p>Pure logic with no broker involved, so the escalation can be tested directly rather than
 * inferred from queue behaviour. The delays escalate because the failures worth retrying are mostly
 * transient — a lock timeout clears in milliseconds, a dependency restart takes minutes — while the
 * failures that are not transient, like a malformed payload, must not be retried forever
 * (section 28).
 */
public final class RetryPolicy {

    /** The delays from HELP.md section 29. */
    private static final List<Duration> DELAYS = List.of(
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(5));

    private RetryPolicy() {
    }

    public static int stageCount() {
        return DELAYS.size();
    }

    /**
     * Which retry stage handles the failure of a given attempt.
     *
     * <p>Attempts beyond the last stage reuse the longest delay rather than failing to have a home,
     * so that {@code maxAttempts} can be raised in configuration without adding queues.
     *
     * @param attempt the 1-based attempt that just failed
     */
    public static int stageFor(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be 1 or greater, was " + attempt);
        }
        return Math.min(attempt, DELAYS.size());
    }

    /** How long the message waits before the given attempt is retried. */
    public static Duration delayForStage(int stage) {
        if (stage < 1 || stage > DELAYS.size()) {
            throw new IllegalArgumentException("no retry stage " + stage);
        }
        return DELAYS.get(stage - 1);
    }

    /**
     * Whether the attempt budget is spent and the message belongs in the dead-letter queue
     * (HELP.md section 28, recommended 5 attempts).
     *
     * @param attempt     the 1-based attempt that just failed
     * @param maxAttempts total deliveries allowed
     */
    public static boolean exhausted(int attempt, int maxAttempts) {
        return attempt >= maxAttempts;
    }
}
