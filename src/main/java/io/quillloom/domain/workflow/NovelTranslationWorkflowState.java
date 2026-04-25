package io.quillloom.domain.workflow;

import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.preprocess.PreprocessDossier;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.DraftCompilation;

import java.util.List;

/**
 * 受控翻译工作流的显式状态对象。
 * 当前先覆盖预处理、初稿生成和拼接三个阶段，后续可直接映射到图状态。
 */
public record NovelTranslationWorkflowState(
        String projectId,
        TranslationWorkflowStage stage,
        PreprocessDossier preprocessDossier,
        List<ChunkTranslationDraft> chunkDrafts,
        DraftCompilation draftCompilation,
        ProjectMemorySnapshot finalProjectMemory
) {

    private static final String REVISION_ROUND_FALLBACK_TYPE = "revision-round-fallback";

    public NovelTranslationWorkflowState(String projectId,
                                         TranslationWorkflowStage stage,
                                         PreprocessDossier preprocessDossier,
                                         List<ChunkTranslationDraft> chunkDrafts,
                                         DraftCompilation draftCompilation) {
        this(projectId, stage, preprocessDossier, chunkDrafts, draftCompilation, null);
    }

    public static NovelTranslationWorkflowState initialized(String projectId) {
        return new NovelTranslationWorkflowState(projectId, TranslationWorkflowStage.INITIALIZED, null, List.of(), null, null);
    }

    public NovelTranslationWorkflowState advanceToPreprocessed(PreprocessDossier dossier) {
        if (dossier == null) {
            throw new IllegalArgumentException("Preprocess dossier must not be null.");
        }
        return new NovelTranslationWorkflowState(projectId, TranslationWorkflowStage.PREPROCESSED, dossier, List.of(), null, null);
    }

    public NovelTranslationWorkflowState advanceToDrafted(List<ChunkTranslationDraft> drafts) {
        return advanceToDrafted(drafts, null);
    }

    public NovelTranslationWorkflowState advanceToDrafted(List<ChunkTranslationDraft> drafts,
                                                          ProjectMemorySnapshot finalProjectMemory) {
        if (stage != TranslationWorkflowStage.PREPROCESSED && stage != TranslationWorkflowStage.DRAFTED) {
            throw new IllegalStateException("Draft stage requires a preprocessed workflow state.");
        }
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalArgumentException("Chunk drafts must not be empty.");
        }
        return new NovelTranslationWorkflowState(
                projectId,
                TranslationWorkflowStage.DRAFTED,
                preprocessDossier,
                List.copyOf(drafts),
                null,
                finalProjectMemory
        );
    }

    public NovelTranslationWorkflowState advanceToCompiled(DraftCompilation compilation) {
        if (stage != TranslationWorkflowStage.DRAFTED) {
            throw new IllegalStateException("Compilation stage requires drafted chunks.");
        }
        if (compilation == null) {
            throw new IllegalArgumentException("Draft compilation must not be null.");
        }
        return new NovelTranslationWorkflowState(
                projectId,
                TranslationWorkflowStage.COMPILED,
                preprocessDossier,
                chunkDrafts,
                compilation,
                finalProjectMemory
        );
    }

    public boolean hasFallbackDrafts() {
        return !fallbackChunkIds().isEmpty();
    }

    public List<String> fallbackChunkIds() {
        if (chunkDrafts == null || chunkDrafts.isEmpty()) {
            return List.of();
        }
        return chunkDrafts.stream()
                .filter(draft -> draft.decisionNotes() != null && draft.decisionNotes().stream()
                        .anyMatch(note -> REVISION_ROUND_FALLBACK_TYPE.equals(note.type())))
                .map(ChunkTranslationDraft::chunkId)
                .toList();
    }
}
