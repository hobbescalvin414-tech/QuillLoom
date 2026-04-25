package io.quillloom.application.translation.service;

import io.quillloom.application.translation.model.KnowledgeRetrievalQuery;
import io.quillloom.application.translation.model.KnowledgeRetrievalUseCase;
import io.quillloom.application.translation.port.out.KnowledgeCardSelector;
import io.quillloom.application.translation.port.out.KnowledgeRetrievalService;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.translation.TranslationRuntimeOptions;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 首批知识卡规则筛选器。
 * 当前通过统一知识检索层做召回与排序，后续可平滑升级为混合检索实现。
 */
@Component
public class RuleBasedKnowledgeCardSelector implements KnowledgeCardSelector {

    private static final int DEFAULT_TOTAL_LIMIT = 6;
    private static final int DEFAULT_PER_TYPE_LIMIT = 2;

    private final KnowledgeRetrievalService knowledgeRetrievalService;

    public RuleBasedKnowledgeCardSelector() {
        this(new RuleBasedKnowledgeRetrievalService());
    }

    public RuleBasedKnowledgeCardSelector(KnowledgeRetrievalService knowledgeRetrievalService) {
        this.knowledgeRetrievalService = knowledgeRetrievalService;
    }

    @Override
    public List<KnowledgeCard> selectForChunk(ChunkAnnotation chunk,
                                              ProjectKnowledgeBase knowledgeBase,
                                              TranslationRuntimeOptions runtimeOptions) {
        if (knowledgeBase == null || knowledgeBase.cards().isEmpty()) {
            return List.of();
        }
        return knowledgeRetrievalService.retrieve(knowledgeBase.projectId(), knowledgeBase, buildQuery(chunk)).cards();
    }

    private KnowledgeRetrievalQuery buildQuery(ChunkAnnotation chunk) {
        Set<String> queryTerms = new LinkedHashSet<>();
        addAll(queryTerms, chunk.backgroundQuestions());
        addAll(queryTerms, chunk.translationRisks());
        addAll(queryTerms, chunk.keyExpressions());
        addAll(queryTerms, chunk.entities());

        Set<String> anchorTerms = new LinkedHashSet<>();
        addAll(anchorTerms, chunk.entities());
        addAll(anchorTerms, chunk.keyExpressions());

        Set<KnowledgeCardType> preferredTypes = new LinkedHashSet<>();
        if (chunk.entities() != null && !chunk.entities().isEmpty()) {
            preferredTypes.add(KnowledgeCardType.CHARACTER_PROFILE);
        }
        if (chunk.backgroundQuestions() != null && !chunk.backgroundQuestions().isEmpty()) {
            preferredTypes.add(KnowledgeCardType.HISTORICAL_BACKGROUND);
            preferredTypes.add(KnowledgeCardType.CULTURAL_BACKGROUND);
            preferredTypes.add(KnowledgeCardType.IMAGERY);
        }

        return new KnowledgeRetrievalQuery(
                KnowledgeRetrievalUseCase.ASSEMBLY,
                chunk.chunk().chunkId(),
                List.copyOf(queryTerms),
                List.copyOf(anchorTerms),
                List.copyOf(preferredTypes),
                List.of(),
                DEFAULT_TOTAL_LIMIT,
                DEFAULT_PER_TYPE_LIMIT
        );
    }

    private void addAll(Set<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            target.add(value.trim());
        }
    }
}
