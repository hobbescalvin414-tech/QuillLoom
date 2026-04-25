package io.quillloom.application.postdraft.review.model;

public record UsageBudget(
        long maxTokens
) {

    public UsageBudget {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be > 0");
        }
    }
}
