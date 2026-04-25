package io.quillloom.infrastructure.preprocess.chunksegmentation;

public record ChunkSegmentationPlanningLlmBoundary(
        Integer endParagraphIndex,
        String boundaryHint
) {
}