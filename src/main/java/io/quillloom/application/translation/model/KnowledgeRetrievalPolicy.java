package io.quillloom.application.translation.model;

/**
 * 统一知识检索层策略。
 */
public record KnowledgeRetrievalPolicy(
        int directChunkMatchWeight,
        int exactAnchorMatchWeight,
        int queryAnchorWeight,
        int queryKeywordWeight,
        int titleTextWeight,
        int contentTextWeight,
        int anchorHintWeight,
        int anchorTitleWeight,
        int preferredTypeWeight,
        int vectorSimilarityScale,
        int vectorPreferredTypeBonus,
        int vectorDirectChunkBonus,
        int vectorRecallMultiplier,
        int defaultLimit,
        int defaultPerTypeLimit
) {
}
