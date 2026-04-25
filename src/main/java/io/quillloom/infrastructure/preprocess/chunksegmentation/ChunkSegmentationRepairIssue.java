package io.quillloom.infrastructure.preprocess.chunksegmentation;

public record ChunkSegmentationRepairIssue(
        String detail,
        String rawResponse
) {
}
