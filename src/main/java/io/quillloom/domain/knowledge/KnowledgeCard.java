package io.quillloom.domain.knowledge;

import java.util.List;
import java.util.Map;

/**
 * 项目级知识库中的最小知识单元。
 */
public record KnowledgeCard(
        String cardId,
        KnowledgeCardType cardType,
        String title,
        String content,
        List<String> keywords,
        List<String> anchorNames,
        List<String> sourceRefs,
        String scope,
        List<String> applicableChunkIds,
        Map<String, Object> metadata
) {

    public KnowledgeCard(
            String cardId,
            KnowledgeCardType cardType,
            String title,
            String content,
            List<String> keywords,
            List<String> anchorNames,
            List<String> sourceRefs,
            String scope,
            List<String> applicableChunkIds
    ) {
        this(cardId, cardType, title, content, keywords, anchorNames, sourceRefs, scope, applicableChunkIds, Map.of());
    }

    public KnowledgeCard {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        anchorNames = anchorNames == null ? List.of() : List.copyOf(anchorNames);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        applicableChunkIds = applicableChunkIds == null ? List.of() : List.copyOf(applicableChunkIds);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
