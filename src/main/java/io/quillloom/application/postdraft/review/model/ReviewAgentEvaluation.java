package io.quillloom.application.postdraft.review.model;

public record ReviewAgentEvaluation(
        ReviewStrategy recommendedStrategy,
        String strategyReason,
        EvidenceSufficiency evidenceSufficiency,
        boolean continueInvestigation
) {

    public ReviewAgentEvaluation {
        if (recommendedStrategy == null) {
            throw new IllegalArgumentException("recommendedStrategy must not be null");
        }
        if (strategyReason == null || strategyReason.isBlank()) {
            throw new IllegalArgumentException("strategyReason must not be blank");
        }
        if (evidenceSufficiency == null) {
            throw new IllegalArgumentException("evidenceSufficiency must not be null");
        }
        strategyReason = strategyReason.trim();
    }
}
