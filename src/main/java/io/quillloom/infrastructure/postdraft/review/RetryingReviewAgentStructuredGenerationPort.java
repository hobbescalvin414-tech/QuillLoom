package io.quillloom.infrastructure.postdraft.review;

import io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation;
import io.quillloom.application.postdraft.review.model.RecordConfirmedTermsProposal;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public class RetryingReviewAgentStructuredGenerationPort implements ReviewAgentStructuredGenerationPort {

    private static final Logger log = LoggerFactory.getLogger(RetryingReviewAgentStructuredGenerationPort.class);

    private final ReviewAgentStructuredGenerationPort delegate;
    private final ReviewAgentLlmRetryPolicy retryPolicy;

    public RetryingReviewAgentStructuredGenerationPort(ReviewAgentStructuredGenerationPort delegate,
                                                       ReviewAgentLlmRetryPolicy retryPolicy) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    @Override
    public ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
        return executeWithRetry(
                "generateNextToolDecision",
                systemPrompt,
                userPrompt,
                () -> delegate.generateNextToolDecision(systemPrompt, userPrompt)
        );
    }

    @Override
    public RecordConfirmedTermsProposal generateRecordConfirmedTermsProposal(String systemPrompt, String userPrompt) {
        return executeWithRetry(
                "generateRecordConfirmedTermsProposal",
                systemPrompt,
                userPrompt,
                () -> delegate.generateRecordConfirmedTermsProposal(systemPrompt, userPrompt)
        );
    }

    @Override
    public ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt) {
        return executeWithRetry(
                "generateEvaluationDecision",
                systemPrompt,
                userPrompt,
                () -> delegate.generateEvaluationDecision(systemPrompt, userPrompt)
        );
    }

    @Override
    public RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt) {
        return executeWithRetry(
                "generateRevisionDraft",
                systemPrompt,
                userPrompt,
                () -> delegate.generateRevisionDraft(systemPrompt, userPrompt)
        );
    }

    @Override
    public RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt) {
        return executeWithRetry(
                "generateRevisionSelfCheck",
                systemPrompt,
                userPrompt,
                () -> delegate.generateRevisionSelfCheck(systemPrompt, userPrompt)
        );
    }

    private <T> T executeWithRetry(String operation,
                                   String systemPrompt,
                                   String userPrompt,
                                   GenerationCall<T> call) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            try {
                log.info(
                        "review_agent_llm_call_started operation={} attempt={} max_attempts={} system_prompt_chars={} user_prompt_chars={}",
                        operation,
                        attempt,
                        retryPolicy.maxAttempts(),
                        lengthOf(systemPrompt),
                        lengthOf(userPrompt)
                );
                T result = call.invoke();
                log.info(
                        "review_agent_llm_call_succeeded operation={} attempt={}",
                        operation,
                        attempt
                );
                return result;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (!retryPolicy.shouldRetry(attempt, ex)) {
                    if (attempt > 1) {
                        log.error(
                                "review_agent_llm_retry_exhausted operation={} attempts_exhausted={} final_exception_type={} final_reason={}",
                                operation,
                                attempt,
                                ex.getClass().getSimpleName(),
                                safeMessage(ex)
                        );
                    }
                    throw ex;
                }
                java.time.Duration backoff = retryPolicy.backoffForAttempt(attempt);
                log.warn(
                        "review_agent_llm_retry operation={} retry_attempt={} max_attempts={} retry_reason={} backoff_ms={} exception_type={}",
                        operation,
                        attempt,
                        retryPolicy.maxAttempts(),
                        safeMessage(ex),
                        backoff.toMillis(),
                        ex.getClass().getSimpleName()
                );
                sleep(backoff);
            }
        }
        throw Objects.requireNonNull(lastFailure, "lastFailure");
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "(none)" : message;
    }

    private int lengthOf(String text) {
        return text == null ? 0 : text.length();
    }

    private void sleep(java.time.Duration duration) {
        long millis = Math.max(0L, duration.toMillis());
        if (millis == 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry review-agent llm call", ex);
        }
    }

    @FunctionalInterface
    private interface GenerationCall<T> {
        T invoke();
    }
}
