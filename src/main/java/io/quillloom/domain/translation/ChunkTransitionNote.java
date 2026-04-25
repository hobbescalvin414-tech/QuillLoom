package io.quillloom.domain.translation;

/**
 * 提供给后续拼接阶段的衔接提示。
 */
public record ChunkTransitionNote(
        String previousChunkConnection,
        String nextChunkConnection,
        boolean boundaryAdjustmentSuggested
) {
}