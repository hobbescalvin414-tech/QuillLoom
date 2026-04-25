package io.quillloom.application.postdraft.review.model;

import java.util.Objects;

public record StoredReviewSession(
        String projectId,
        ProjectReviewRuntimeSession runtime
) {

    public StoredReviewSession {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public static StoredReviewSession from(ProjectReviewRuntimeSession runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return new StoredReviewSession(runtime.projectId(), runtime);
    }
}
