package io.quillloom.infrastructure.preprocess.bookanalysis;

public record GlobalConstraintBoundaryDecision(
        boolean accepted,
        String reasonCode
) {

    public static GlobalConstraintBoundaryDecision accept() {
        return new GlobalConstraintBoundaryDecision(true, "accepted");
    }

    public static GlobalConstraintBoundaryDecision reject(String reasonCode) {
        return new GlobalConstraintBoundaryDecision(false, reasonCode);
    }
}
