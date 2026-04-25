package io.quillloom.application.translation.model;

public record ConfirmedTermConflict(
        String sourceKey,
        String existingSourceTerm,
        String existingTargetTerm,
        String incomingSourceTerm,
        String incomingTargetTerm,
        String evidenceChunkId
) {
}
