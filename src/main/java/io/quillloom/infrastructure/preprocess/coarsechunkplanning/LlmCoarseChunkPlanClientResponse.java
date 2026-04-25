package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

public record LlmCoarseChunkPlanClientResponse(
        String rawResponse,
        CoarseChunkPlanningLlmResult result,
        int timeoutSeconds
) {
}
