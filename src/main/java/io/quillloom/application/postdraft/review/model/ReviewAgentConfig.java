package io.quillloom.application.postdraft.review.model;

public record ReviewAgentConfig(
        int maxTurns,
        UsageBudget usageBudget,
        int compactAfterTurns,
        int compactKeepLast,
        int compactKeepLastEvidence
) {
    private static final ReviewAgentConfig DEFAULT = new ReviewAgentConfig(
            12,
            new UsageBudget(12_000),
            10,
            8,
            12
    );

    public ReviewAgentConfig {
        if (maxTurns <= 0) {
            throw new IllegalArgumentException("maxTurns must be > 0");
        }
        if (usageBudget == null) {
            throw new IllegalArgumentException("usageBudget must not be null");
        }
        if (compactAfterTurns < 0) {
            throw new IllegalArgumentException("compactAfterTurns must be >= 0");
        }
        if (compactKeepLast < 0) {
            throw new IllegalArgumentException("compactKeepLast must be >= 0");
        }
        if (compactKeepLastEvidence < 0) {
            throw new IllegalArgumentException("compactKeepLastEvidence must be >= 0");
        }
    }

    public static ReviewAgentConfig defaultConfig() {
        return DEFAULT;
    }
}
