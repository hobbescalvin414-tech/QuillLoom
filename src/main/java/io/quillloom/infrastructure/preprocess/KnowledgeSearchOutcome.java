package io.quillloom.infrastructure.preprocess;

import java.util.Optional;

public record KnowledgeSearchOutcome(
        KnowledgeNeed need,
        int rawHitCount,
        int filteredHitCount,
        OrganizedKnowledgeEvidence organizedEvidence,
        String rejectionKind,
        String rejectionReason
) {

    public boolean accepted() {
        return organizedEvidence != null;
    }

    public Optional<OrganizedKnowledgeEvidence> organizedEvidenceOptional() {
        return Optional.ofNullable(organizedEvidence);
    }
}
