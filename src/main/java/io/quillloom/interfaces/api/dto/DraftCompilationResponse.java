package io.quillloom.interfaces.api.dto;

import io.quillloom.domain.translation.DraftCompilation;

public record DraftCompilationResponse(
        String projectId,
        int chunkCount,
        String mergedDraft,
        int carriedDecisionNoteCount
) {

    public static DraftCompilationResponse from(DraftCompilation compilation) {
        return new DraftCompilationResponse(
                compilation.projectId(),
                compilation.chunkDrafts().size(),
                compilation.mergedDraft(),
                compilation.carriedDecisionNotes().size()
        );
    }
}
