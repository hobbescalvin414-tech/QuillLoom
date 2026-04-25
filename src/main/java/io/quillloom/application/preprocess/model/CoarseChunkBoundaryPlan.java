package io.quillloom.application.preprocess.model;

public record CoarseChunkBoundaryPlan(
        int endParagraphIndex,
        String summary,
        String boundaryHint
) {
}