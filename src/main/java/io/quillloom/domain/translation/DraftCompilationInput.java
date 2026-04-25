package io.quillloom.domain.translation;

import java.util.List;

/**
 * Agent E 的稳定输入契约，只承载待拼接的 chunk 初稿与项目标识。
 */
public record DraftCompilationInput(
        String projectId,
        List<ChunkTranslationDraft> chunkDrafts
) {
}