package io.quillloom.domain.translation;

import java.util.List;
import java.util.Map;

/**
 * 单个 chunk 的结构化翻译初稿结果。
 * - `decisionNotes` 用于记录未决问题、风险点、待确认事项
 * - `confirmedTermUpdates` 用于记录已经足够稳定、可以确认写回的术语或译名更新
 * - `candidateUpdates` 用于记录仍处于候选层的译名或表达更新
 * - `transitionNote` 用于告诉 Agent E 这个 chunk 在前后衔接上要注意什么
 */
public record ChunkTranslationDraft(
        String chunkId,
        String translatedText,
        String translatorCommentary,
        List<TranslationDecisionNote> decisionNotes,
        Map<String, String> confirmedTermUpdates,
        List<TranslationCandidateUpdate> candidateUpdates,
        ChunkTransitionNote transitionNote
) {
}
