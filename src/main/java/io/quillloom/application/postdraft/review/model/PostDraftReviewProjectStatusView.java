package io.quillloom.application.postdraft.review.model;

public record PostDraftReviewProjectStatusView(
        String projectId,
        String status,
        String stopReason,
        String currentChunkId,
        int completedChunkCount,
        boolean waitingHuman,
        String latestHumanQuestion
) {
}
