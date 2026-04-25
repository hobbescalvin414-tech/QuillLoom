package io.quillloom.application.postdraft.review.command;

import io.quillloom.application.postdraft.review.model.ReviewFocus;

import java.util.Objects;

public record StartPostDraftReviewAgentCommand(
        String projectId,
        ReviewFocus focus,
        String operatorNote
) {
    public StartPostDraftReviewAgentCommand {
        projectId = requireText(projectId, "projectId");
        focus = Objects.requireNonNull(focus, "focus");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
