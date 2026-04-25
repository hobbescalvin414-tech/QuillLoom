package io.quillloom.application.translation.service;

import io.quillloom.application.translation.model.KnowledgeRetrievalPolicy;
import io.quillloom.application.translation.model.KnowledgeRetrievalQuery;
import java.util.Comparator;

final class HybridKnowledgeRanker {

    void applyScores(Iterable<KnowledgeRetrievalCandidate> candidates,
                     KnowledgeRetrievalQuery query,
                     KnowledgeRetrievalPolicy policy) {
        for (KnowledgeRetrievalCandidate candidate : candidates) {
            int score = candidate.keywordScore() + candidate.vectorScore();
            if (candidate.directChunkMatch()) {
                score += policy.directChunkMatchWeight();
            }
            if (candidate.preferredTypeMatch()) {
                score += policy.preferredTypeWeight();
            }
            if (candidate.exactAnchorMatch()) {
                score += policy.exactAnchorMatchWeight();
            }
            candidate.setFinalScore(score);
        }
    }

    Comparator<KnowledgeRetrievalCandidate> comparator(KnowledgeRetrievalQuery query) {
        return Comparator
                .comparingInt(KnowledgeRetrievalCandidate::finalScore).reversed()
                .thenComparing(KnowledgeRetrievalCandidate::directChunkMatch, Comparator.reverseOrder())
                .thenComparing(KnowledgeRetrievalCandidate::exactAnchorMatch, Comparator.reverseOrder())
                .thenComparing(KnowledgeRetrievalCandidate::preferredTypeMatch, Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.card().title() == null ? "" : candidate.card().title());
    }
}
