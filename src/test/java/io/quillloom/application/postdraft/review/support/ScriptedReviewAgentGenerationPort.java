package io.quillloom.application.postdraft.review.support;

import io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;

import java.util.ArrayDeque;
import java.util.List;

public class ScriptedReviewAgentGenerationPort implements ReviewAgentStructuredGenerationPort {

    private final ArrayDeque<Object> toolDecisionQueue;
    private final ArrayDeque<Object> evaluationQueue;
    private final ArrayDeque<Object> revisionDraftQueue;
    private final ArrayDeque<Object> selfCheckQueue;

    public ScriptedReviewAgentGenerationPort(List<?> toolDecisions,
                                             List<?> evaluations,
                                             List<?> revisionDrafts,
                                             List<?> selfChecks) {
        this.toolDecisionQueue = new ArrayDeque<>(toolDecisions == null ? List.of() : toolDecisions);
        this.evaluationQueue = new ArrayDeque<>(evaluations == null ? List.of() : evaluations);
        this.revisionDraftQueue = new ArrayDeque<>(revisionDrafts == null ? List.of() : revisionDrafts);
        this.selfCheckQueue = new ArrayDeque<>(selfChecks == null ? List.of() : selfChecks);
    }

    @Override
    public ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
        return next(toolDecisionQueue, ReviewToolDecision.class, "generateNextToolDecision");
    }

    @Override
    public ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt) {
        return next(evaluationQueue, ReviewAgentEvaluation.class, "generateEvaluationDecision");
    }

    @Override
    public RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt) {
        return next(revisionDraftQueue, RevisionDraft.class, "generateRevisionDraft");
    }

    @Override
    public RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt) {
        return next(selfCheckQueue, RevisionSelfCheckResult.class, "generateRevisionSelfCheck");
    }

    private <T> T next(ArrayDeque<Object> queue, Class<T> type, String operation) {
        if (queue.isEmpty()) {
            throw new IllegalStateException("ScriptedReviewAgentGenerationPort has no scripted output for " + operation);
        }
        Object next = queue.removeFirst();
        if (next instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        return type.cast(next);
    }
}
