package io.quillloom.application.translation.service;

import io.quillloom.application.translation.model.KnowledgeRetrievalPolicy;
import io.quillloom.application.translation.model.KnowledgeRetrievalQuery;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class KeywordKnowledgeRecallService {

    Map<String, KnowledgeRetrievalCandidate> recall(ProjectKnowledgeBase knowledgeBase,
                                                    KnowledgeRetrievalQuery query,
                                                    KnowledgeRetrievalPolicy policy,
                                                    Set<String> excludedCardIds) {
        Map<String, KnowledgeRetrievalCandidate> candidates = new LinkedHashMap<>();
        for (KnowledgeCard card : knowledgeBase.cards()) {
            if (card == null || excludedCardIds.contains(card.cardId())) {
                continue;
            }
            KnowledgeRetrievalCandidate candidate = new KnowledgeRetrievalCandidate(
                    card,
                    directChunkMatch(card, query),
                    query.preferredTypes().contains(card.cardType())
            );

            int lexicalScore = scoreCard(candidate, query, policy);
            if (lexicalScore > 0 || candidate.directChunkMatch() || candidate.preferredTypeMatch()) {
                candidate.addKeywordScore(lexicalScore);
                candidates.put(card.cardId(), candidate);
            }
        }
        return candidates;
    }

    private int scoreCard(KnowledgeRetrievalCandidate candidate,
                          KnowledgeRetrievalQuery query,
                          KnowledgeRetrievalPolicy policy) {
        KnowledgeCard card = candidate.card();
        int score = 0;

        for (String term : query.queryTerms()) {
            if (term == null || term.isBlank()) {
                continue;
            }
            String normalized = normalize(term);
            score += countPartialMatch(card.anchorNames(), normalized) * policy.queryAnchorWeight();
            score += countPartialMatch(card.keywords(), normalized) * policy.queryKeywordWeight();
            score += countText(card.title(), normalized) * policy.titleTextWeight();
            score += countText(card.content(), normalized) * policy.contentTextWeight();
        }

        for (String anchor : query.anchorTerms()) {
            if (anchor == null || anchor.isBlank()) {
                continue;
            }
            String normalized = normalize(anchor);
            if (containsExact(card.anchorNames(), normalized) || containsExact(card.title(), normalized)) {
                candidate.markExactAnchorMatch();
            }
            score += countPartialMatch(card.anchorNames(), normalized) * policy.anchorHintWeight();
            score += countText(card.title(), normalized) * policy.anchorTitleWeight();
        }
        return score;
    }

    private boolean directChunkMatch(KnowledgeCard card,
                                     KnowledgeRetrievalQuery query) {
        return query.chunkId() != null
                && !query.chunkId().isBlank()
                && card.applicableChunkIds().contains(query.chunkId());
    }

    private boolean containsExact(List<String> values, String normalizedTerm) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (normalize(value).equals(normalizedTerm)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsExact(String value, String normalizedTerm) {
        return normalize(value).equals(normalizedTerm);
    }

    private int countPartialMatch(List<String> values, String normalizedTerm) {
        if (values == null) {
            return 0;
        }
        int matches = 0;
        for (String value : values) {
            String normalizedValue = normalize(value);
            if (normalizedValue.isBlank()) {
                continue;
            }
            if (normalizedValue.contains(normalizedTerm) || normalizedTerm.contains(normalizedValue)) {
                matches++;
            }
        }
        return matches;
    }

    private int countText(String value, String normalizedTerm) {
        String normalizedValue = normalize(value);
        return normalizedValue.contains(normalizedTerm) ? 1 : 0;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
