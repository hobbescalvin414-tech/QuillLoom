package io.quillloom.infrastructure.postdraft.review;

import io.quillloom.application.postdraft.review.port.out.LlmTransientException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewAgentLlmRetryPolicyTest {

    @Test
    void shouldOnlyRetryTransientFailuresWithinAttemptBudget() {
        ReviewAgentLlmRetryPolicy policy = new ReviewAgentLlmRetryPolicy(
                3,
                Duration.ofSeconds(1),
                Duration.ofSeconds(8),
                2.0d,
                0.2d
        );

        assertTrue(policy.shouldRetry(1, new LlmTransientException("retry")));
        assertTrue(policy.shouldRetry(2, new LlmTransientException("retry")));
        assertFalse(policy.shouldRetry(3, new LlmTransientException("stop")));
        assertFalse(policy.shouldRetry(1, new IllegalStateException("not transient")));
    }

    @Test
    void shouldApplyDeterministicJitterWithinExpectedBounds() {
        ReviewAgentLlmRetryPolicy policy = new ReviewAgentLlmRetryPolicy(
                3,
                Duration.ofMillis(1000),
                Duration.ofMillis(8000),
                2.0d,
                0.2d
        );

        assertEquals(Duration.ofMillis(800), policy.backoffForAttempt(1, 0.0d));
        assertEquals(Duration.ofMillis(1000), policy.backoffForAttempt(1, 0.5d));
        assertEquals(Duration.ofMillis(1200), policy.backoffForAttempt(1, 1.0d));
        assertEquals(Duration.ofMillis(2400), policy.backoffForAttempt(2, 1.0d));
    }

    @Test
    void shouldRespectMaxBackoffAfterApplyingJitter() {
        ReviewAgentLlmRetryPolicy policy = new ReviewAgentLlmRetryPolicy(
                5,
                Duration.ofMillis(3000),
                Duration.ofMillis(5000),
                2.0d,
                0.2d
        );

        assertEquals(Duration.ofMillis(5000), policy.backoffForAttempt(3, 1.0d));
    }
}
