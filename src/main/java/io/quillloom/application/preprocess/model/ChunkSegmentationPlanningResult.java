package io.quillloom.application.preprocess.model;

import java.util.List;

public record ChunkSegmentationPlanningResult(
        List<ChunkBoundaryPlan> boundaries
) {

    public ChunkSegmentationPlanningResult {
        boundaries = boundaries == null ? List.of() : List.copyOf(boundaries);
    }
}