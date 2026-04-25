package io.quillloom.infrastructure.preprocess;

import java.util.List;

public record KnowledgeSearchOrganizerLlmResult(
        boolean shouldCreateCard,
        String title,
        String summary,
        List<String> translationNotes,
        List<String> keywords,
        List<String> anchorNames,
        List<Integer> usedEvidenceIndexes,
        String confidence,
        String rejectionReason
) {
}