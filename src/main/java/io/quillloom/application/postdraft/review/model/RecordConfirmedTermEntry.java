package io.quillloom.application.postdraft.review.model;

public record RecordConfirmedTermEntry(
        String sourceTerm,
        String targetTerm
) {

    public RecordConfirmedTermEntry {
        if (sourceTerm == null || sourceTerm.isBlank()) {
            throw new IllegalArgumentException("sourceTerm must not be blank");
        }
        if (targetTerm == null || targetTerm.isBlank()) {
            throw new IllegalArgumentException("targetTerm must not be blank");
        }
        sourceTerm = sourceTerm.trim();
        targetTerm = targetTerm.trim();
    }
}
