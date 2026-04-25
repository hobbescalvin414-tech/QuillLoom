package io.quillloom.application.postdraft.review.model;

import java.util.Objects;

public record ProjectChunkReviewOutcome(
        String chunkId,
        String finalTranslation,
        ReviewStrategy strategy,
        ReviewProcessSummary processSummary
) {
    public ProjectChunkReviewOutcome {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId must not be blank");
        }
        if (finalTranslation == null || finalTranslation.isBlank()) {
            throw new IllegalArgumentException("finalTranslation must not be blank");
        }
        strategy = strategy == null ? ReviewStrategy.KEEP : strategy;
        processSummary = Objects.requireNonNull(processSummary, "processSummary");
        finalTranslation = finalTranslation.trim();
    }
}
