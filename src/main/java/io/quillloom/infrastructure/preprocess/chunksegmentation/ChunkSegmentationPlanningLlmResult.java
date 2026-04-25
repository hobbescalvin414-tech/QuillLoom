package io.quillloom.infrastructure.preprocess.chunksegmentation;

import java.util.List;

public record ChunkSegmentationPlanningLlmResult(
        List<ChunkSegmentationPlanningLlmBoundary> boundaries
) {
}