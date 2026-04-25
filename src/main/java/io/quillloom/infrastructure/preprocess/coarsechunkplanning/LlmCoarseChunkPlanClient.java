package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

public interface LlmCoarseChunkPlanClient {

    CoarseChunkPlanningLlmResult generate(String prompt);

    default LlmCoarseChunkPlanClientResponse generateDetailed(String prompt) {
        return new LlmCoarseChunkPlanClientResponse(null, generate(prompt), 60);
    }
}
