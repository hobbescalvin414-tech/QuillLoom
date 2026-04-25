package io.quillloom.application.preprocess.model;

import java.util.List;

public record CoarseChunkPlanningResult(
        List<CoarseChunkBoundaryPlan> boundaries
) {

    public CoarseChunkPlanningResult {
        boundaries = boundaries == null ? List.of() : List.copyOf(boundaries);
    }
}