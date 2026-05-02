package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;
import io.quillloom.application.postdraft.review.prompt.RevisionSelfCheckPromptBuilder;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.util.Objects;

public class LlmBackedRevisionSelfCheckService implements RevisionSelfCheckService {

    private static final String SELF_CHECK_SYSTEM_PROMPT = """
            You are a post-draft review agent. Perform a local self-check on the current revision draft.
            Return a JSON object only. Do not add explanatory text outside JSON.
            """;

    private final RevisionSelfCheckPromptBuilder promptBuilder;
    private final ReviewAgentStructuredGenerationPort generationPort;

    public LlmBackedRevisionSelfCheckService(RevisionSelfCheckPromptBuilder promptBuilder,
                                             ReviewAgentStructuredGenerationPort generationPort) {
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.generationPort = Objects.requireNonNull(generationPort, "generationPort");
    }

    @Override
    public RevisionSelfCheckResult check(PostDraftReviewSession session,
                                         PostDraftChunkRecord chunk,
                                         ReviewStrategy strategy,
                                         RevisionDraft draft) {
        RevisionSelfCheckResult firstAttempt = generationPort.generateRevisionSelfCheck(
                SELF_CHECK_SYSTEM_PROMPT,
                promptBuilder.build(session, chunk, strategy, draft)
        );
        if (firstAttempt.passed()) {
            return firstAttempt;
        }
        return generationPort.generateRevisionSelfCheck(
                SELF_CHECK_SYSTEM_PROMPT,
                promptBuilder.buildRetryPrompt(session, chunk, strategy, draft, firstAttempt)
        );
    }
}
