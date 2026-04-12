package io.quillloom.infrastructure.preprocess.bookanalysis;

public record RejectedGlobalConstraintTracePayload(
        String type,
        String description,
        String reasonCode
) {
}
