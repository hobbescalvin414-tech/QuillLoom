package io.quillloom.interfaces.api.dto;

import io.quillloom.application.postdraft.review.model.PostDraftReviewProjectStatusView;

public record PostDraftReviewProjectStatusResponse(
        String projectId,
        String status,
        String stopReason,
        String currentChunkId,
        int completedChunkCount,
        boolean waitingHuman,
        String latestHumanQuestion
) {
    public static PostDraftReviewProjectStatusResponse from(PostDraftReviewProjectStatusView view) {
        return new PostDraftReviewProjectStatusResponse(
                view.projectId(),
                view.status(),
                view.stopReason(),
                view.currentChunkId(),
                view.completedChunkCount(),
                view.waitingHuman(),
                view.latestHumanQuestion()
        );
    }
}
