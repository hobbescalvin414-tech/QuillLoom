package io.quillloom.application.translation.service;

import io.quillloom.application.preprocess.model.KnowledgeEmbedding;
import io.quillloom.application.preprocess.port.out.KnowledgeEmbeddingService;
import io.quillloom.application.preprocess.port.out.KnowledgeIndexRepository;
import io.quillloom.application.preprocess.port.out.ProjectKnowledgeBaseRepository;
import io.quillloom.application.translation.model.KnowledgeRetrievalPolicy;
import io.quillloom.application.translation.model.KnowledgeRetrievalQuery;
import io.quillloom.application.translation.model.KnowledgeRetrievalUseCase;
import io.quillloom.application.translation.model.KnowledgeRetrievalResult;
import io.quillloom.application.translation.port.out.KnowledgeRetrievalPolicyResolver;
import io.quillloom.application.translation.port.out.KnowledgeRetrievalService;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.infrastructure.preprocess.DefaultKnowledgeRetrievalPolicyResolver;
import io.quillloom.infrastructure.preprocess.NoOpKnowledgeEmbeddingService;
import io.quillloom.infrastructure.preprocess.NoOpKnowledgeIndexRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 统一知识检索层的规则版实现。
 * 当前组合关键词召回、可选向量召回和轻量重排，后续可继续演进为更专业的混合检索实现。
 */
@Component
public class RuleBasedKnowledgeRetrievalService implements KnowledgeRetrievalService {

    private final ProjectKnowledgeBaseRepository projectKnowledgeBaseRepository;
    private final KnowledgeEmbeddingService knowledgeEmbeddingService;
    private final KnowledgeIndexRepository knowledgeIndexRepository;
    private final KnowledgeRetrievalPolicyResolver policyResolver;
    private final KeywordKnowledgeRecallService keywordRecallService;
    private final VectorKnowledgeRecallService vectorRecallService;
    private final HybridKnowledgeRanker hybridKnowledgeRanker;
    private final KnowledgeSelectionService knowledgeSelectionService;

    public RuleBasedKnowledgeRetrievalService() {
        this(new ProjectKnowledgeBaseRepository() {
            @Override
            public Optional<ProjectKnowledgeBase> load(String projectId) {
                return Optional.empty();
            }

            @Override
            public void save(ProjectKnowledgeBase knowledgeBase) {
                throw new UnsupportedOperationException("默认规则检索器不支持写入知识库");
            }
        }, new NoOpKnowledgeEmbeddingService(), new NoOpKnowledgeIndexRepository(), new DefaultKnowledgeRetrievalPolicyResolver());
    }

    public RuleBasedKnowledgeRetrievalService(ProjectKnowledgeBaseRepository projectKnowledgeBaseRepository,
                                              KnowledgeEmbeddingService knowledgeEmbeddingService,
                                              KnowledgeIndexRepository knowledgeIndexRepository) {
        this(projectKnowledgeBaseRepository, knowledgeEmbeddingService, knowledgeIndexRepository, new DefaultKnowledgeRetrievalPolicyResolver());
    }

    public RuleBasedKnowledgeRetrievalService(ProjectKnowledgeBaseRepository projectKnowledgeBaseRepository,
                                              KnowledgeEmbeddingService knowledgeEmbeddingService,
                                              KnowledgeIndexRepository knowledgeIndexRepository,
                                              KnowledgeRetrievalPolicyResolver policyResolver) {
        this.projectKnowledgeBaseRepository = projectKnowledgeBaseRepository;
        this.knowledgeEmbeddingService = knowledgeEmbeddingService;
        this.knowledgeIndexRepository = knowledgeIndexRepository;
        this.policyResolver = policyResolver;
        this.keywordRecallService = new KeywordKnowledgeRecallService();
        this.vectorRecallService = new VectorKnowledgeRecallService(knowledgeEmbeddingService, knowledgeIndexRepository);
        this.hybridKnowledgeRanker = new HybridKnowledgeRanker();
        this.knowledgeSelectionService = new KnowledgeSelectionService();
    }

    @Override
    public KnowledgeRetrievalResult retrieve(String projectId,
                                             ProjectKnowledgeBase preferredKnowledgeBase,
                                             KnowledgeRetrievalQuery query) {
        if (query == null) {
            return new KnowledgeRetrievalResult(List.of());
        }

        ProjectKnowledgeBase knowledgeBase = resolveKnowledgeBase(projectId, preferredKnowledgeBase);
        if (knowledgeBase.cards().isEmpty()) {
            return new KnowledgeRetrievalResult(List.of());
        }

        KnowledgeRetrievalPolicy policy = policyResolver.resolve(query.useCase());
        Set<String> excludedCardIds = new LinkedHashSet<>(query.excludedCardIds());
        LinkedHashMap<String, KnowledgeRetrievalCandidate> candidates = new LinkedHashMap<>(
                keywordRecallService.recall(knowledgeBase, query, policy, excludedCardIds)
        );
        vectorRecallService.merge(projectId, knowledgeBase, query, policy, excludedCardIds, candidates);
        hybridKnowledgeRanker.applyScores(candidates.values(), query, policy);

        List<KnowledgeRetrievalCandidate> ranked = new ArrayList<>(candidates.values());
        ranked.sort(hybridKnowledgeRanker.comparator(query));
        List<KnowledgeCard> selected = knowledgeSelectionService.select(ranked, query, policy);

        return new KnowledgeRetrievalResult(selected);
    }

    private ProjectKnowledgeBase resolveKnowledgeBase(String projectId,
                                                      ProjectKnowledgeBase preferredKnowledgeBase) {
        if (preferredKnowledgeBase != null) {
            return preferredKnowledgeBase;
        }
        if (projectId == null || projectId.isBlank()) {
            return ProjectKnowledgeBase.empty("");
        }
        return projectKnowledgeBaseRepository.load(projectId)
                .orElse(ProjectKnowledgeBase.empty(projectId));
    }
}
