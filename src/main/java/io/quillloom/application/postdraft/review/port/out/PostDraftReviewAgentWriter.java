package io.quillloom.application.postdraft.review.port.out;

import io.quillloom.application.postdraft.review.model.HumanReviewRequest;
import io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult;
import io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome;
import io.quillloom.application.postdraft.review.model.ReviewProcessSummary;

import java.util.List;

public interface PostDraftReviewAgentWriter {

    PostDraftReviewAgentResult writeCompleted(String finalTranslatedText, ReviewProcessSummary processSummary);

    PostDraftReviewAgentResult writeHumanRequired(HumanReviewRequest request);

    default void writeCompletedChunks(String projectId, List<ProjectChunkReviewOutcome> outcomes) {
        throw new UnsupportedOperationException("This writer does not support project-level completed chunk writeback");
    }

    default void writeMergedDraftText(String projectId, String mergedDraftText) {
        throw new UnsupportedOperationException("This writer does not support project-level merged draft writeback");
    }

    default void writeMergedDraftFromProjectChunks(String projectId) {
        throw new UnsupportedOperationException("This writer does not support project-level merged draft assembly");
    }

    default void resetProjectRevisions(String projectId) {
        throw new UnsupportedOperationException("This writer does not support project-level revision reset");
    }
}
