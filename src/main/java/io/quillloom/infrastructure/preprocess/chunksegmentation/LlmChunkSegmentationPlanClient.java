package io.quillloom.infrastructure.preprocess.chunksegmentation;

public interface LlmChunkSegmentationPlanClient {

    ChunkSegmentationPlanningLlmResult generate(String prompt);

    default LlmChunkSegmentationPlanClientResponse generateDetailed(String prompt) {
        return new LlmChunkSegmentationPlanClientResponse(null, generate(prompt));
    }
}