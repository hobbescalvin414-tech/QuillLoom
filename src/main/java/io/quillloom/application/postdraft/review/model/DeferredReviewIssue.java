package io.quillloom.application.postdraft.review.model;

public record DeferredReviewIssue(
        String issueId,
        String relatedChunkId,
        String summary
) {

    public DeferredReviewIssue {
        if (issueId == null || issueId.isBlank()) {
            throw new IllegalArgumentException("issueId must not be blank");
        }
        if (relatedChunkId == null || relatedChunkId.isBlank()) {
            throw new IllegalArgumentException("relatedChunkId must not be blank");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        issueId = issueId.trim();
        relatedChunkId = relatedChunkId.trim();
        summary = summary.trim();
    }
}
