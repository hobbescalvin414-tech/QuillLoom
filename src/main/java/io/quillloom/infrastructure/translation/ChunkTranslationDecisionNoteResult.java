package io.quillloom.infrastructure.translation;

public record ChunkTranslationDecisionNoteResult(
        String type,
        String sourceAnchor,
        String description,
        String recommendation
) {
}
