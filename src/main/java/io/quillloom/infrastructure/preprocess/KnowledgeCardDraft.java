package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;

import java.util.List;

public record KnowledgeCardDraft(
        KnowledgeCardType cardType,
        String title,
        String content,
        List<String> anchorNames,
        List<String> sourceRefs,
        List<String> applicableChunkIds
) {
}
