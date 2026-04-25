package io.quillloom.application.postdraft.review.model;

public record HumanReviewResolution(
        ReviewStrategy strategy,
        ReviewAgentState resumeState,
        String reviewerNote
) {

    public HumanReviewResolution {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must not be null");
        }
        if (resumeState == null) {
            throw new IllegalArgumentException("resumeState must not be null");
        }
        if (resumeState != ReviewAgentState.INVESTIGATING && resumeState != ReviewAgentState.REVISING) {
            throw new IllegalArgumentException("resumeState must be INVESTIGATING or REVISING");
        }
        reviewerNote = reviewerNote == null ? "" : reviewerNote.trim();
    }
}
