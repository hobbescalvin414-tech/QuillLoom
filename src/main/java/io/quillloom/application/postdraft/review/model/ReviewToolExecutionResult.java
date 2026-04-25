package io.quillloom.application.postdraft.review.model;

public record ReviewToolExecutionResult(
        ReviewToolCall toolCall,
        ProjectReviewRuntimeSession nextRuntime,
        boolean success,
        String summary,
        ReviewGuardrailRejection rejection
) {

    public ReviewToolExecutionResult {
        if (toolCall == null) {
            throw new IllegalArgumentException("toolCall must not be null");
        }
        if (nextRuntime == null) {
            throw new IllegalArgumentException("nextRuntime must not be null");
        }
        summary = summary == null ? "" : summary.trim();
        rejection = rejection == null ? ReviewGuardrailRejection.none() : rejection;
        if (success && rejection.rejected()) {
            throw new IllegalArgumentException("successful execution must not carry rejection");
        }
        if (!success && !rejection.rejected()) {
            throw new IllegalArgumentException("failed execution must carry rejection");
        }
    }

    public static ReviewToolExecutionResult success(ReviewToolCall toolCall,
                                                    ProjectReviewRuntimeSession nextRuntime,
                                                    String summary) {
        return new ReviewToolExecutionResult(toolCall, nextRuntime, true, summary, ReviewGuardrailRejection.none());
    }

    public static ReviewToolExecutionResult rejected(ReviewToolCall toolCall,
                                                     ProjectReviewRuntimeSession nextRuntime,
                                                     ReviewGuardrailRejection rejection) {
        return new ReviewToolExecutionResult(toolCall, nextRuntime, false, "", rejection);
    }
}
