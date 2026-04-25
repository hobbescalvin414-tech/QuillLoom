package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.KnowledgeCardType;

import java.util.List;

/**
 * C0 第一阶段规划出的受控知识需求。
 */
public record KnowledgeNeed(
        KnowledgeCardType cardType,
        String queryText,
        List<String> anchorNames,
        List<String> keywords,
        List<String> originRefs,
        String reason,
        int priority,
        KnowledgeNeedKind needKind,
        KnowledgeNeedSignalSource signalSource,
        String coverageKey,
        String searchIntent
) {

    public KnowledgeNeed(KnowledgeCardType cardType,
                         String queryText,
                         List<String> anchorNames,
                         List<String> keywords,
                         List<String> originRefs,
                         String reason,
                         int priority) {
        this(
                cardType,
                queryText,
                anchorNames,
                keywords,
                originRefs,
                reason,
                priority,
                KnowledgeNeedKind.GENERAL_ENRICHMENT,
                KnowledgeNeedSignalSource.UNKNOWN,
                "",
                ""
        );
    }

    public KnowledgeSearchQuery toSearchQuery(String scope) {
        return new KnowledgeSearchQuery(
                cardType,
                queryText,
                keywords,
                anchorNames,
                originRefs,
                scope
        );
    }
}
