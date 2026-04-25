package io.quillloom.infrastructure.postdraft.review;

import io.quillloom.application.postdraft.review.model.EvidenceSufficiency;
import io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionMode;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.application.postdraft.review.port.out.LlmStructuredOutputException;
import io.quillloom.application.postdraft.review.port.out.LlmTransientException;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryingReviewAgentStructuredGenerationPortTest {

    @Test
    void shouldRetryTransientFailureAndEventuallySucceed() {
        RecordingDelegate delegate = new RecordingDelegate(
                new LlmTransientException("429"),
                new LlmTransientException("503"),
                new ReviewToolDecision("complete_project", Map.of(), "done")
        );
        RetryingReviewAgentStructuredGenerationPort port = new RetryingReviewAgentStructuredGenerationPort(
                delegate,
                new ReviewAgentLlmRetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(2), 2.0, 0.0)
        );

        ReviewToolDecision decision = port.generateNextToolDecision("system", "user");

        assertEquals("complete_project", decision.toolName());
        assertEquals(3, delegate.nextToolDecisionCalls);
    }

    @Test
    void shouldNotRetryStructuredOutputFailure() {
        RecordingDelegate delegate = new RecordingDelegate(
                new LlmStructuredOutputException("bad json")
        );
        RetryingReviewAgentStructuredGenerationPort port = new RetryingReviewAgentStructuredGenerationPort(
                delegate,
                new ReviewAgentLlmRetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(2), 2.0, 0.0)
        );

        assertThrows(
                LlmStructuredOutputException.class,
                () -> port.generateNextToolDecision("system", "user")
        );
        assertEquals(1, delegate.nextToolDecisionCalls);
    }

    @Test
    void shouldRetryTransientFailureForRevisionSelfCheckAndEventuallySucceed() {
        RecordingDelegate delegate = new RecordingDelegate();
        delegate.revisionSelfCheckOutputs.addLast(new LlmTransientException("GOAWAY received"));
        delegate.revisionSelfCheckOutputs.addLast(new RevisionSelfCheckResult(true, "", List.of()));
        RetryingReviewAgentStructuredGenerationPort port = new RetryingReviewAgentStructuredGenerationPort(
                delegate,
                new ReviewAgentLlmRetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(2), 2.0, 0.0)
        );

        RevisionSelfCheckResult result = port.generateRevisionSelfCheck("system", "user");

        assertEquals(true, result.passed());
        assertEquals(2, delegate.revisionSelfCheckCalls);
    }

    private static final class RecordingDelegate implements ReviewAgentStructuredGenerationPort {
        private final ArrayDeque<Object> nextToolDecisionOutputs;
        private final ArrayDeque<Object> revisionSelfCheckOutputs = new ArrayDeque<>();
        private int nextToolDecisionCalls;
        private int revisionSelfCheckCalls;

        private RecordingDelegate(Object... nextToolDecisionOutputs) {
            this.nextToolDecisionOutputs = new ArrayDeque<>(List.of(nextToolDecisionOutputs));
        }

        @Override
        public ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
            nextToolDecisionCalls++;
            Object next = nextToolDecisionOutputs.removeFirst();
            if (next instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            return (ReviewToolDecision) next;
        }

        @Override
        public ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt) {
            return new ReviewAgentEvaluation(ReviewStrategy.KEEP, "ok", EvidenceSufficiency.SUFFICIENT, false);
        }

        @Override
        public RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt) {
            return new RevisionDraft("ok", RevisionMode.KEEP, List.of(), List.of());
        }

        @Override
        public RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt) {
            revisionSelfCheckCalls++;
            if (revisionSelfCheckOutputs.isEmpty()) {
                return new RevisionSelfCheckResult(true, "", List.of());
            }
            Object next = revisionSelfCheckOutputs.removeFirst();
            if (next instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            return (RevisionSelfCheckResult) next;
        }
    }
}
