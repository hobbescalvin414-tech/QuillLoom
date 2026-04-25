package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;

import java.util.List;

/**
 * C0 面向外部搜索工具发出的单次检索任务。
 */
public record KnowledgeSearchQuery(
        KnowledgeCardType cardType,
        String queryText,
        List<String> keywords,
        List<String> anchorNames,
        List<String> sourceRefs,
        String scope
) {
}