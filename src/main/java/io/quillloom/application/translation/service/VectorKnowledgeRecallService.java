package io.quillloom.application.translation.service;

import io.quillloom.application.preprocess.model.KnowledgeEmbedding;
import io.quillloom.application.preprocess.model.KnowledgeIndexMatch;
import io.quillloom.application.preprocess.port.out.KnowledgeEmbeddingService;
import io.quillloom.application.preprocess.port.out.KnowledgeIndexRepository;
import io.quillloom.application.translation.model.KnowledgeRetrievalPolicy;
import io.quillloom.application.translation.model.KnowledgeRetrievalQuery;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class VectorKnowledgeRecallService {

    private final KnowledgeEmbeddingService knowledgeEmbeddingService;
    private final KnowledgeIndexRepository knowledgeIndexRepository;

    VectorKnowledgeRecallService(KnowledgeEmbeddingService knowledgeEmbeddingService,
                                 KnowledgeIndexRepository knowledgeIndexRepository) {
        this.knowledgeEmbeddingService = knowledgeEmbeddingService;
        this.knowledgeIndexRepository = knowledgeIndexRepository;
    }

    void merge(String projectId,
               ProjectKnowledgeBase knowledgeBase,
               KnowledgeRetrievalQuery query,
               KnowledgeRetrievalPolicy policy,
               Set<String> excludedCardIds,
               Map<String, KnowledgeRetrievalCandidate> candidates) {
        String semanticQueryText = buildSemanticQueryText(query);
        if (semanticQueryText.isBlank()) {
            return;
        }

        KnowledgeEmbedding queryEmbedding = knowledgeEmbeddingService.embed(semanticQueryText);
        if (queryEmbedding.isEmpty()) {
            return;
        }

        int vectorLimit = resolveVectorRecallLimit(query.limit(), policy);
        List<KnowledgeIndexMatch> matches = knowledgeIndexRepository.searchSimilar(projectId, queryEmbedding, vectorLimit);
        if (matches.isEmpty()) {
            return;
        }

        Map<String, KnowledgeCard> cardById = new LinkedHashMap<>();
        for (KnowledgeCard card : knowledgeBase.cards()) {
            if (card != null) {
                cardById.put(card.cardId(), card);
            }
        }

        for (KnowledgeIndexMatch match : matches) {
            if (match == null || excludedCardIds.contains(match.cardId())) {
                continue;
            }
            KnowledgeCard card = cardById.get(match.cardId());
            if (card == null) {
                continue;
            }
            KnowledgeRetrievalCandidate candidate = candidates.computeIfAbsent(
                    card.cardId(),
                    ignored -> new KnowledgeRetrievalCandidate(
                            card,
                            query.chunkId() != null && !query.chunkId().isBlank() && card.applicableChunkIds().contains(query.chunkId()),
                            query.preferredTypes().contains(card.cardType())
                    )
            );
            int vectorScore = toVectorScore(match.similarityScore(), candidate, policy);
            if (vectorScore <= 0) {
                continue;
            }
            candidate.applyVectorSimilarity(match.similarityScore(), vectorScore);
        }
    }

    private int resolveVectorRecallLimit(int configuredLimit, KnowledgeRetrievalPolicy policy) {
        int base = configuredLimit <= 0 ? policy.defaultLimit() : configuredLimit;
        return Math.max(3, base * Math.max(1, policy.vectorRecallMultiplier()));
    }

    private int toVectorScore(double similarityScore,
                              KnowledgeRetrievalCandidate candidate,
                              KnowledgeRetrievalPolicy policy) {
        if (similarityScore <= 0D) {
            return 0;
        }
        int score = (int) Math.round(similarityScore * policy.vectorSimilarityScale());
        if (candidate.preferredTypeMatch()) {
            score += policy.vectorPreferredTypeBonus();
        }
        if (candidate.directChunkMatch()) {
            score += policy.vectorDirectChunkBonus();
        }
        return score;
    }

    private String buildSemanticQueryText(KnowledgeRetrievalQuery query) {
        StringBuilder builder = new StringBuilder();
        if (query.useCase() == io.quillloom.application.translation.model.KnowledgeRetrievalUseCase.SUPPLEMENTAL_LOOKUP) {
            builder.append("场景：运行期补卡\n");
        } else {
            builder.append("场景：首批选卡\n");
        }
        if (!query.preferredTypes().isEmpty()) {
            builder.append("偏好类型：").append(query.preferredTypes().stream().map(Enum::name).toList()).append("\n");
        }
        if (!query.anchorTerms().isEmpty()) {
            builder.append("锚点：").append(String.join("、", query.anchorTerms())).append("\n");
        }
        if (!query.queryTerms().isEmpty()) {
            builder.append("查询：").append(String.join("；", query.queryTerms())).append("\n");
        }
        return builder.toString().trim();
    }
}
