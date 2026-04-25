package io.quillloom.application.translation.model;

import io.quillloom.domain.knowledge.KnowledgeCardType;

import java.util.List;

/**
 * 统一知识检索层的查询对象。
 * 当前先服务规则检索，后续可平滑映射到数据库、向量检索或混合检索。
 */
public record KnowledgeRetrievalQuery(
        KnowledgeRetrievalUseCase useCase,
        String chunkId,
        List<String> queryTerms,
        List<String> anchorTerms,
        List<KnowledgeCardType> preferredTypes,
        List<String> excludedCardIds,
        int limit,
        int perTypeLimit
) {

    public KnowledgeRetrievalQuery {
        useCase = useCase == null ? KnowledgeRetrievalUseCase.ASSEMBLY : useCase;
        queryTerms = queryTerms == null ? List.of() : List.copyOf(queryTerms);
        anchorTerms = anchorTerms == null ? List.of() : List.copyOf(anchorTerms);
        preferredTypes = preferredTypes == null ? List.of() : List.copyOf(preferredTypes);
        excludedCardIds = excludedCardIds == null ? List.of() : List.copyOf(excludedCardIds);
    }
}
