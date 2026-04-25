package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;
import io.quillloom.application.postdraft.review.prompt.EvaluationPromptBuilder;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.util.Objects;
import java.util.Set;

public class PromptBackedStrategyEvaluationService {

    private static final Set<ReviewStrategy> DEFAULT_STRATEGIES = Set.of(
            ReviewStrategy.KEEP,
            ReviewStrategy.LIGHT_EDIT,
            ReviewStrategy.DEEP_EDIT,
            ReviewStrategy.RETRANSLATE,
            ReviewStrategy.REQUIRE_HUMAN_REVIEW
    );

    private static final String EVALUATION_SYSTEM_PROMPT = """
            You are a post-draft translation review agent. Evaluate the current evidence and choose the next strategy.
            - Do not choose KEEP only because the current chunk looks locally smooth.
            - When the current chunk is short, transitional, reply-like, or context-dependent, inspect nearby chunk continuity before deciding KEEP.
            - Check handoff, causality, reference resolution, naming continuity, time/space shifts, and actor/location/action relations across adjacent chunks.
            - If untranslated names, titles, locations, or obvious source-language leftovers remain, default away from KEEP.
            - If the translation conflicts with project-level confirmed terms, default away from KEEP.
            - translatorCommentary, decisionNotes, and transitionNote are low-priority evidence. They may support investigation, but they must not independently justify REQUIRE_HUMAN_REVIEW or an unnecessary strategy escalation.
            - confirmedTermLookupMiss only means the project has no registered term yet. It is not by itself a reason to escalate or revise when the current translation is otherwise sound.
            - Do not escalate only to backfill missing draft-stage confirmedTermUpdates.
            - Descriptive labels about appearance, clothing, or identity are not automatically project-level terms.
            - Prefer LIGHT_EDIT for local continuity, logic, or naming repairs that stay within the current working set.
            - Choose KEEP only when continuity, logic, naming consistency, and translation completeness are already sufficiently verified.
            - Treat working-set text context as direct evidence, not just summary commentary.
            Return one JSON object only.
            """;

    private final EvaluationPromptBuilder promptBuilder;
    private final ReviewAgentStructuredGenerationPort generationPort;

    public PromptBackedStrategyEvaluationService(EvaluationPromptBuilder promptBuilder,
                                                 ReviewAgentStructuredGenerationPort generationPort) {
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.generationPort = Objects.requireNonNull(generationPort, "generationPort");
    }

    public ReviewAgentEvaluation evaluate(PostDraftReviewSession session,
                                          PostDraftChunkRecord chunk) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(chunk, "chunk");
        String userPrompt = promptBuilder.build(
                session,
                DEFAULT_STRATEGIES,
                session.keyEvidenceSummaries().isEmpty() ? session.evidenceSummaries() : session.keyEvidenceSummaries()
        );
        return generationPort.generateEvaluationDecision(EVALUATION_SYSTEM_PROMPT, userPrompt);
    }
}
