package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

import java.util.List;

public record CoarseChunkPlanningLlmResult(
        List<CoarseChunkPlanningLlmBoundary> boundaries
) {
}