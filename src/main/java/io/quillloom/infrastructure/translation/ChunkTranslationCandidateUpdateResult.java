package io.quillloom.infrastructure.translation;

public record ChunkTranslationCandidateUpdateResult(
        String sourceTerm,
        String candidateTranslation,
        String rationale,
        boolean requiresReview
) {
}
