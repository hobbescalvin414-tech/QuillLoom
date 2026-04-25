package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionMode;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;
import io.quillloom.application.postdraft.review.prompt.RevisionPromptBuilder;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.util.List;
import java.util.Objects;

public class PromptBackedRevisionDraftProvider implements RevisionDraftProvider {

    private static final String REVISION_SYSTEM_PROMPT = """
            你是译后审校 Agent。请基于当前证据生成正式译文草稿，并给出可追溯依据。
            严格输出 JSON 对象，不要附加解释文本。
            formalTranslation 必须是非空字符串。
            revisionMode 必须与 targetStrategy 完全一致。
            """;

    private final RevisionPromptBuilder promptBuilder;
    private final ReviewAgentStructuredGenerationPort generationPort;

    public PromptBackedRevisionDraftProvider() {
        this(new RevisionPromptBuilder());
    }

    public PromptBackedRevisionDraftProvider(RevisionPromptBuilder promptBuilder) {
        this(promptBuilder, unsupportedGenerationPort());
    }

    public PromptBackedRevisionDraftProvider(RevisionPromptBuilder promptBuilder,
                                             ReviewAgentStructuredGenerationPort generationPort) {
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.generationPort = Objects.requireNonNull(generationPort, "generationPort");
    }

    @Override
    public RevisionDraft generate(PostDraftReviewSession session,
                                  PostDraftChunkRecord chunk,
                                  ReviewStrategy strategy) {
        List<String> keyRationales = session.keyEvidenceSummaries().isEmpty()
                ? session.evidenceSummaries()
                : session.keyEvidenceSummaries();
        List<String> residualRisks = session.conflictingEvidenceSummaries().isEmpty()
                ? session.evidenceGaps()
                : session.conflictingEvidenceSummaries();
        String userPrompt = promptBuilder.build(session, chunk, strategy, keyRationales, residualRisks);
        try {
            return generateAndValidate(userPrompt, strategy);
        } catch (RuntimeException firstFailure) {
            String retryUserPrompt = promptBuilder.buildRetryPrompt(
                    session,
                    chunk,
                    strategy,
                    keyRationales,
                    residualRisks,
                    summarizeFailure(firstFailure)
            );
            try {
                return generateAndValidate(retryUserPrompt, strategy);
            } catch (RuntimeException retryFailure) {
                throw new IllegalStateException(
                        "revision draft generation failed after retry: " + summarizeFailure(retryFailure),
                        retryFailure
                );
            }
        }
    }

    private RevisionDraft generateAndValidate(String userPrompt, ReviewStrategy strategy) {
        RevisionDraft draft = generationPort.generateRevisionDraft(REVISION_SYSTEM_PROMPT, userPrompt);
        if (draft.revisionMode() != RevisionMode.valueOf(strategy.name())) {
            throw new IllegalStateException(
                    "revision draft mode does not match requested strategy: strategy=" + strategy + ", mode=" + draft.revisionMode()
            );
        }
        return draft;
    }

    private String summarizeFailure(RuntimeException exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
            return exception == null ? "unknown_failure" : exception.getClass().getSimpleName();
        }
        return exception.getMessage().trim();
    }

    private static ReviewAgentStructuredGenerationPort unsupportedGenerationPort() {
        return new ReviewAgentStructuredGenerationPort() {
            @Override
            public io.quillloom.application.postdraft.review.model.ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
                throw new IllegalStateException("ReviewAgentStructuredGenerationPort is not configured");
            }

            @Override
            public io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt) {
                throw new IllegalStateException("ReviewAgentStructuredGenerationPort is not configured");
            }

            @Override
            public RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt) {
                throw new IllegalStateException("ReviewAgentStructuredGenerationPort is not configured");
            }

            @Override
            public io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt) {
                throw new IllegalStateException("ReviewAgentStructuredGenerationPort is not configured");
            }
        };
    }
}
