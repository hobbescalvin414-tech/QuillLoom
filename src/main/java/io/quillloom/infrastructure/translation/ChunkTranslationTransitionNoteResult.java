package io.quillloom.infrastructure.translation;

public record ChunkTranslationTransitionNoteResult(
        String previousChunkConnection,
        String nextChunkConnection,
        boolean boundaryAdjustmentSuggested
) {
}
