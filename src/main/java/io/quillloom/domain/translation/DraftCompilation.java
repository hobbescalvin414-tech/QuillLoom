package io.quillloom.domain.translation;

import java.util.List;

/**
 * 面向审阅的、按顺序拼接的 chunk 初稿汇编结果。
 */
public record DraftCompilation(
        String projectId,
        List<ChunkTranslationDraft> chunkDrafts,
        String mergedDraft,
        List<TranslationDecisionNote> carriedDecisionNotes
) {
}