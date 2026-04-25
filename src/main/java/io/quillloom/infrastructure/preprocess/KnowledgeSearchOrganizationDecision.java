package io.quillloom.infrastructure.preprocess;

import java.util.Optional;

public record KnowledgeSearchOrganizationDecision(
        boolean accepted,
        int rawHitCount,
        int filteredHitCount,
        OrganizedKnowledgeEvidence organizedEvidence,
        String rejectionKind,
        String rejectionReason
) {

    public static KnowledgeSearchOrganizationDecision accepted(int rawHitCount,
                                                               int filteredHitCount,
                                                               OrganizedKnowledgeEvidence organizedEvidence) {
        return new KnowledgeSearchOrganizationDecision(
                true,
                rawHitCount,
                filteredHitCount,
                organizedEvidence,
                "",
                ""
        );
    }

    public static KnowledgeSearchOrganizationDecision rejected(KnowledgeNeed need,
                                                               int rawHitCount,
                                                               int filteredHitCount,
                                                               String rejectionKind,
                                                               String rejectionReason) {
        return new KnowledgeSearchOrganizationDecision(
                false,
                rawHitCount,
                filteredHitCount,
                null,
                blankToDefault(rejectionKind, "ORGANIZER_REJECTED"),
                blankToDefault(rejectionReason, "organizer rejected current need")
        );
    }

    public Optional<OrganizedKnowledgeEvidence> organizedEvidenceOptional() {
        return Optional.ofNullable(organizedEvidence);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
