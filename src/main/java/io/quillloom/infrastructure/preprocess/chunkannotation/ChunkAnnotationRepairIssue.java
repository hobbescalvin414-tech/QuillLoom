package io.quillloom.infrastructure.preprocess.chunkannotation;

public record ChunkAnnotationRepairIssue(
        String reasonCode,
        String detail,
        String rawResponse
) {
}
