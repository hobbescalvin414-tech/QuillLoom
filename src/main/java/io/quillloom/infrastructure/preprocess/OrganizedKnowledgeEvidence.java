package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;

import java.util.List;

public record OrganizedKnowledgeEvidence(
        KnowledgeCardType cardType,
        String title,
        String content,
        List<String> anchorNames,
        List<String> evidenceUrls,
        List<String> originRefs,
        String searchProvider,
        String confidence
) {
}
