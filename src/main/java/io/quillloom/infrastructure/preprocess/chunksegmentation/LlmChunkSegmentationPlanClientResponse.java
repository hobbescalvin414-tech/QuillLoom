package io.quillloom.infrastructure.preprocess.chunksegmentation;

public record LlmChunkSegmentationPlanClientResponse(
        String rawResponse,
        ChunkSegmentationPlanningLlmResult result
) {
}