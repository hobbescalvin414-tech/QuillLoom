package io.quillloom.domain.knowledge;

import java.util.List;

/**
 * 项目级知识库快照。
 */
public record ProjectKnowledgeBase(
        String projectId,
        List<KnowledgeCard> cards,
        List<CandidateTerm> candidateTerms
) {

    public ProjectKnowledgeBase {
        cards = cards == null ? List.of() : List.copyOf(cards);
        candidateTerms = candidateTerms == null ? List.of() : List.copyOf(candidateTerms);
    }

    public static ProjectKnowledgeBase empty(String projectId) {
        return new ProjectKnowledgeBase(projectId, List.of(), List.of());
    }
}