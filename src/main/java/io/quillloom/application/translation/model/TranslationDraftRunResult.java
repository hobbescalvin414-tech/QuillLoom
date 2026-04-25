package io.quillloom.application.translation.model;

import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.translation.ChunkTranslationDraft;

import java.util.List;

public record TranslationDraftRunResult(
        List<ChunkTranslationDraft> drafts,
        ProjectMemorySnapshot finalProjectMemory
) {
    public TranslationDraftRunResult {
        drafts = drafts == null ? List.of() : List.copyOf(drafts);
    }
}
