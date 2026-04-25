package io.quillloom.application.translation.service;

import io.quillloom.application.translation.model.KnowledgeRetrievalPolicy;
import io.quillloom.application.translation.model.KnowledgeRetrievalQuery;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class KnowledgeSelectionService {

    List<KnowledgeCard> select(List<KnowledgeRetrievalCandidate> rankedCandidates,
                               KnowledgeRetrievalQuery query,
                               KnowledgeRetrievalPolicy policy) {
        int totalLimit = query.limit() <= 0 ? policy.defaultLimit() : query.limit();
        int perTypeLimit = query.perTypeLimit() <= 0 ? policy.defaultPerTypeLimit() : query.perTypeLimit();

        Map<KnowledgeCardType, Integer> typeCounts = new java.util.LinkedHashMap<>();
        List<KnowledgeCard> selected = new ArrayList<>();
        for (KnowledgeRetrievalCandidate candidate : rankedCandidates) {
            if (selected.size() >= totalLimit) {
                break;
            }
            KnowledgeCard card = candidate.card();
            int count = typeCounts.getOrDefault(card.cardType(), 0);
            if (count >= perTypeLimit) {
                continue;
            }
            selected.add(card);
            typeCounts.put(card.cardType(), count + 1);
        }
        return List.copyOf(selected);
    }
}
