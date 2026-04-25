package io.quillloom.infrastructure.translation;

public record LlmChunkTranslationClientResponse(
        String rawResponse,
        ChunkTranslationLlmResult result
) {
}