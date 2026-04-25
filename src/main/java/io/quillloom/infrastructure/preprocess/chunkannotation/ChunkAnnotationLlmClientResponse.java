package io.quillloom.infrastructure.preprocess.chunkannotation;

public record ChunkAnnotationLlmClientResponse(
        String rawResponse,
        ChunkAnnotationLlmResult result
) {
}