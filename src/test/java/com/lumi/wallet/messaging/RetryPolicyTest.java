package com.lumi.wallet.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The retry ladder from HELP.md sections 28 and 29. A plain unit test: the escalation is pure logic,
 * and testing it without a broker is what makes it worth having as a separate class.
 */
class RetryPolicyTest {

    @Test
    @DisplayName("the delays escalate 5s, 30s, 5m as HELP.md section 29 specifies")
    void delaysMatchTheSpecifiedLadder() {
        assertThat(RetryPolicy.stageCount()).isEqualTo(3);
        assertThat(RetryPolicy.delayForStage(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(RetryPolicy.delayForStage(2)).isEqualTo(Duration.ofSeconds(30));
        assertThat(RetryPolicy.delayForStage(3)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void eachFailedAttemptMovesToTheNextStage() {
        assertThat(RetryPolicy.stageFor(1)).isEqualTo(1);
        assertThat(RetryPolicy.stageFor(2)).isEqualTo(2);
        assertThat(RetryPolicy.stageFor(3)).isEqualTo(3);
    }

    /**
     * Attempts beyond the last stage reuse the longest delay, so that {@code maxAttempts} can be
     * raised in configuration without needing more queues.
     */
    @Test
    void attemptsBeyondTheLastStageReuseTheLongestDelay() {
        assertThat(RetryPolicy.stageFor(4)).isEqualTo(3);
        assertThat(RetryPolicy.stageFor(50)).isEqualTo(3);
    }

    /** After the configured number of attempts the message is dead-lettered (section 28). */
    @Test
    void theAttemptBudgetIsFiveByDefault() {
        assertThat(RetryPolicy.exhausted(1, 5)).isFalse();
        assertThat(RetryPolicy.exhausted(4, 5)).isFalse();
        assertThat(RetryPolicy.exhausted(5, 5)).isTrue();
        assertThat(RetryPolicy.exhausted(6, 5)).isTrue();
    }

    @Test
    void nonsensicalInputIsRejectedRatherThanGuessed() {
        assertThatThrownBy(() -> RetryPolicy.stageFor(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.delayForStage(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.delayForStage(4))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
