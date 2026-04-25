package io.quillloom.infrastructure.postdraft.review;

import io.quillloom.application.postdraft.review.port.out.LlmTransientException;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public record ReviewAgentLlmRetryPolicy(
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        double backoffMultiplier,
        double jitterFactor
) {

    public ReviewAgentLlmRetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (initialBackoff == null || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("initialBackoff must not be negative");
        }
        if (maxBackoff == null || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("maxBackoff must not be negative");
        }
        if (backoffMultiplier < 1.0d) {
            throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
        }
        if (jitterFactor < 0.0d) {
            throw new IllegalArgumentException("jitterFactor must be >= 0.0");
        }
    }

    public boolean shouldRetry(int attempt, RuntimeException exception) {
        return attempt < maxAttempts && exception instanceof LlmTransientException;
    }

    public Duration backoffForAttempt(int attempt) {
        return backoffForAttempt(attempt, ThreadLocalRandom.current().nextDouble());
    }

    Duration backoffForAttempt(int attempt, double randomValue) {
        double scaled = initialBackoff.toMillis() * Math.pow(backoffMultiplier, Math.max(0, attempt - 1));
        double jitterMultiplier = 1.0d;
        if (jitterFactor > 0.0d) {
            double boundedRandom = Math.max(0.0d, Math.min(1.0d, randomValue));
            jitterMultiplier = 1.0d + ((boundedRandom * 2.0d) - 1.0d) * jitterFactor;
        }
        long millis = Math.min(maxBackoff.toMillis(), Math.round(scaled * jitterMultiplier));
        return Duration.ofMillis(Math.max(0L, millis));
    }
}
