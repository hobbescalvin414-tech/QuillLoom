package io.quillloom.application.translation.service;

import io.quillloom.domain.knowledge.KnowledgeCard;

final class KnowledgeRetrievalCandidate {

    private final KnowledgeCard card;
    private final boolean directChunkMatch;
    private final boolean preferredTypeMatch;
    private boolean exactAnchorMatch;
    private int keywordScore;
    private double vectorSimilarity;
    private int vectorScore;
    private int finalScore;

    KnowledgeRetrievalCandidate(KnowledgeCard card,
                                boolean directChunkMatch,
                                boolean preferredTypeMatch) {
        this.card = card;
        this.directChunkMatch = directChunkMatch;
        this.preferredTypeMatch = preferredTypeMatch;
    }

    KnowledgeCard card() {
        return card;
    }

    boolean directChunkMatch() {
        return directChunkMatch;
    }

    boolean preferredTypeMatch() {
        return preferredTypeMatch;
    }

    boolean exactAnchorMatch() {
        return exactAnchorMatch;
    }

    void markExactAnchorMatch() {
        this.exactAnchorMatch = true;
    }

    int keywordScore() {
        return keywordScore;
    }

    void addKeywordScore(int keywordScore) {
        this.keywordScore += keywordScore;
    }

    double vectorSimilarity() {
        return vectorSimilarity;
    }

    void applyVectorSimilarity(double vectorSimilarity, int vectorScore) {
        if (vectorSimilarity <= this.vectorSimilarity) {
            return;
        }
        this.vectorSimilarity = vectorSimilarity;
        this.vectorScore = vectorScore;
    }

    int vectorScore() {
        return vectorScore;
    }

    int finalScore() {
        return finalScore;
    }

    void setFinalScore(int finalScore) {
        this.finalScore = finalScore;
    }
}
