package io.quillloom.application.preprocess.model;

public record ChunkBoundaryPlan(
        int endParagraphIndex,
        String boundaryHint
) {
}