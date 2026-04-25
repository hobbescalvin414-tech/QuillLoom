package io.quillloom.infrastructure.postdraft.review;

import io.quillloom.application.postdraft.review.model.HumanReviewRequest;
import io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult;
import io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome;
import io.quillloom.application.postdraft.review.model.ReviewProcessSummary;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentWriter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class PassThroughPostDraftReviewAgentWriter implements PostDraftReviewAgentWriter {

    @Override
    public PostDraftReviewAgentResult writeCompleted(String finalTranslatedText, ReviewProcessSummary processSummary) {
        return new PostDraftReviewAgentResult(
                finalTranslatedText,
                Objects.requireNonNull(processSummary, "processSummary"),
                Optional.empty()
        );
    }

    @Override
    public PostDraftReviewAgentResult writeHumanRequired(HumanReviewRequest request) {
        HumanReviewRequest effectiveRequest = Objects.requireNonNull(request, "request");
        return new PostDraftReviewAgentResult(
                "",
                effectiveRequest.processSummary(),
                Optional.of(effectiveRequest)
        );
    }

    @Override
    public void writeCompletedChunks(String projectId, List<ProjectChunkReviewOutcome> outcomes) {
        // 仅用于旧单 chunk 兼容路径；项目级 autonomous review 不应注入该实现。
    }

    @Override
    public void writeMergedDraftText(String projectId, String mergedDraftText) {
        // 仅用于旧单 chunk 兼容路径；项目级 autonomous review 不应注入该实现。
    }
}
