package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;

import java.util.List;

/**
 * 搜索工具返回的受控结果。
 */
public record KnowledgeSearchResult(
        KnowledgeCardType cardType,
        String title,
        String content,
        List<String> keywords,
        List<String> anchorNames,
        List<String> sourceRefs,
        String scope
) {
}