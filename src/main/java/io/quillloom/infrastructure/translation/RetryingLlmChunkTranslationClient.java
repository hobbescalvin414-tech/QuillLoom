package io.quillloom.infrastructure.translation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

public class RetryingLlmChunkTranslationClient implements LlmChunkTranslationClient {

    private static final Logger log = LoggerFactory.getLogger(RetryingLlmChunkTranslationClient.class);

    private final LlmChunkTranslationClient delegate;
    private final int maxAttempts;
    private final Duration backoff;
    private final Sleeper sleeper;

    public RetryingLlmChunkTranslationClient(LlmChunkTranslationClient delegate) {
        this(delegate, 3, Duration.ofMillis(800), Thread::sleep);
    }

    RetryingLlmChunkTranslationClient(LlmChunkTranslationClient delegate,
                                      int maxAttempts,
                                      Duration backoff,
                                      Sleeper sleeper) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be > 0");
        }
        this.maxAttempts = maxAttempts;
        this.backoff = backoff == null ? Duration.ZERO : backoff;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    @Override
    public ChunkTranslationLlmResult generate(String prompt) {
        return generateDetailed(prompt).result();
    }

    @Override
    public LlmChunkTranslationClientResponse generateDetailed(String prompt) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return delegate.generateDetailed(prompt);
            } catch (RuntimeException exception) {
                if (!isTransient(exception) || attempt >= maxAttempts) {
                    throw exception;
                }
                long backoffMs = backoff.toMillis();
                log.warn(
                        "translation_llm_retry retry_attempt={} retry_reason={} backoff_ms={} roundLabel={}",
                        attempt,
                        retryReason(exception),
                        backoffMs,
                        inferRoundLabel(prompt)
                );
                sleep(backoffMs);
            }
        }
        throw new IllegalStateException("unreachable translation retry state");
    }

    private boolean isTransient(RuntimeException exception) {
        if (exception instanceof ChunkTranslationTransientException) {
            return true;
        }
        if (exception instanceof ChunkTranslationStructuredOutputException
                || exception instanceof IllegalArgumentException
                || exception instanceof NullPointerException) {
            return false;
        }
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("429")
                || lower.contains("503")
                || lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("rate limit")
                || lower.contains("temporarily unavailable");
    }

    private String retryReason(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.trim();
    }

    private String inferRoundLabel(String prompt) {
        if (prompt == null) {
            return "unknown";
        }
        if (prompt.contains("confirmed term conflict repair")) {
            return "confirmed-term-conflict-repair";
        }
        if (prompt.contains("第 2 轮")) {
            return "revision";
        }
        if (prompt.contains("第 1 轮")) {
            return "draft";
        }
        return "unknown";
    }

    private void sleep(long backoffMs) {
        if (backoffMs <= 0) {
            return;
        }
        try {
            sleeper.sleep(backoffMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ChunkTranslationTransientException("translation LLM retry interrupted", exception);
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
