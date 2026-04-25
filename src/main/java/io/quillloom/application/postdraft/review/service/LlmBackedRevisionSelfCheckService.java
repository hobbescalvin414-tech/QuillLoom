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
            你是译后审校 Agent，当前需要对 revision draft 做本地 self-check。
            严格输出 JSON 对象，不要附加解释文本。
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
