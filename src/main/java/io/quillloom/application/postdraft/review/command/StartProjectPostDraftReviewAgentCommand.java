package io.quillloom.application.postdraft.review.command;

public record StartProjectPostDraftReviewAgentCommand(
        String projectId,
        String operatorNote
) {
    public StartProjectPostDraftReviewAgentCommand {
        projectId = requireText(projectId, "projectId");
        operatorNote = operatorNote == null ? "" : operatorNote;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
