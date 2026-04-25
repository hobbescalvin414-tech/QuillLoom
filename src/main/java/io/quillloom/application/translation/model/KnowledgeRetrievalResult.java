package io.quillloom.application.translation.model;

import io.quillloom.domain.knowledge.KnowledgeCard;

import java.util.List;

/**
 * 统一知识检索层返回的结果。
 */
public record KnowledgeRetrievalResult(
        List<KnowledgeCard> cards
) {

    public KnowledgeRetrievalResult {
        cards = cards == null ? List.of() : List.copyOf(cards);
    }
}
