package io.quillloom.infrastructure.translation;

public interface LlmChunkTranslationClient {

    ChunkTranslationLlmResult generate(String prompt);

    default LlmChunkTranslationClientResponse generateDetailed(String prompt) {
        return new LlmChunkTranslationClientResponse(null, generate(prompt));
    }
}