package io.quillloom.domain.preprocess;

import io.quillloom.domain.knowledge.CandidateTerm;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;

import java.util.List;

/**
 * 预处理阶段生成的项目级知识库快照。
 */
public record KnowledgeEnrichmentBundle(
        ProjectKnowledgeBase projectKnowledgeBase
) {

    public KnowledgeEnrichmentBundle {
        if (projectKnowledgeBase == null) {
            throw new IllegalArgumentException("projectKnowledgeBase must not be null.");
        }
    }

    public List<KnowledgeCard> knowledgeCards() {
        return projectKnowledgeBase.cards();
    }

    public List<CandidateTerm> candidateTerms() {
        return projectKnowledgeBase.candidateTerms();
    }
}