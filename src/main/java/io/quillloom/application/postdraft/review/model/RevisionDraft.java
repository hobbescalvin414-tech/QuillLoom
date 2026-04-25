package io.quillloom.application.postdraft.review.model;

import java.util.List;

public record RevisionDraft(
        String formalTranslation,
        RevisionMode revisionMode,
        List<String> keyRationales,
        List<String> residualRisks
) {

    public RevisionDraft {
        if (formalTranslation == null || formalTranslation.isBlank()) {
            throw new IllegalArgumentException("formalTranslation must not be blank");
        }
        if (revisionMode == null) {
            throw new IllegalArgumentException("revisionMode must not be null");
        }
        if (keyRationales == null) {
            throw new IllegalArgumentException("keyRationales must not be null");
        }
        if (residualRisks == null) {
            throw new IllegalArgumentException("residualRisks must not be null");
        }
        formalTranslation = formalTranslation.trim();
        keyRationales = List.copyOf(keyRationales);
        residualRisks = List.copyOf(residualRisks);
    }
}
