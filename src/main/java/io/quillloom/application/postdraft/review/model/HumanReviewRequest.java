package io.quillloom.application.postdraft.review.model;

public record HumanReviewRequest(
        String projectId,
        ReviewFocus focus,
        ReviewProcessSummary processSummary,
        String requestNote,
        String questionForHuman,
        String requestReason,
        ReviewAgentState waitingState,
        String resumeHint,
        int completedChunkCount,
        int pendingChunkCount
) {

    public HumanReviewRequest(String projectId,
                              ReviewFocus focus,
                              ReviewProcessSummary processSummary,
                              String requestNote,
                              String questionForHuman,
                              String requestReason,
                              ReviewAgentState waitingState,
                              String resumeHint) {
        this(
                projectId,
                focus,
                processSummary,
                requestNote,
                questionForHuman,
                requestReason,
                waitingState,
                resumeHint,
                0,
                0
        );
    }

    public HumanReviewRequest {
        requestNote = requestNote == null ? "" : requestNote.trim();
        questionForHuman = questionForHuman == null ? "" : questionForHuman.trim();
        requestReason = requestReason == null ? "" : requestReason.trim();
        resumeHint = resumeHint == null ? "" : resumeHint.trim();
        if (waitingState == null) {
            waitingState = ReviewAgentState.WAITING_HUMAN;
        }
        if (completedChunkCount < 0) {
            throw new IllegalArgumentException("completedChunkCount must be >= 0");
        }
        if (pendingChunkCount < 0) {
            throw new IllegalArgumentException("pendingChunkCount must be >= 0");
        }
    }
}
