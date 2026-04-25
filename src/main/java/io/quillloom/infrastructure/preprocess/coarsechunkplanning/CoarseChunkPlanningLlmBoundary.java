package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

public record CoarseChunkPlanningLlmBoundary(
        Integer endParagraphIndex,
        String summary,
        String boundaryHint
) {
}