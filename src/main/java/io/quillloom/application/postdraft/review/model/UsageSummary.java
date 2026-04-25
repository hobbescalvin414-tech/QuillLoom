package io.quillloom.application.postdraft.review.model;

public record UsageSummary(
        long inputTokens,
        long outputTokens
) {

    public UsageSummary {
        if (inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens must be >= 0");
        }
        if (outputTokens < 0) {
            throw new IllegalArgumentException("outputTokens must be >= 0");
        }
    }

    public static UsageSummary empty() {
        return new UsageSummary(0, 0);
    }

    public UsageSummary addTurn(String prompt, String output) {
        return new UsageSummary(
                inputTokens + estimateTokens(prompt),
                outputTokens + estimateTokens(output)
        );
    }

    public UsageSummary add(UsageSummary other) {
        if (other == null) {
            return this;
        }
        return new UsageSummary(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens
        );
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    public boolean exceeds(UsageBudget budget) {
        return budget != null && totalTokens() > budget.maxTokens();
    }

    private static long estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String trimmed = text.trim();
        String[] parts = trimmed.split("\\s+");
        if (parts.length > 1) {
            return parts.length;
        }
        return Math.max(1, trimmed.length() / 4L);
    }
}
