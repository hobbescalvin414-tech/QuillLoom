package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.ReviewToolExecutionResult;

public interface ReviewRuntimeVisualizer {

    static ReviewRuntimeVisualizer noop() {
        return NoOpHolder.INSTANCE;
    }

    default void projectStarted(ProjectReviewRuntimeSession runtime) {
    }

    default void focusSelected(ProjectReviewRuntimeSession runtime) {
    }

    default void toolCalled(ProjectReviewRuntimeSession runtime, ReviewToolDecision decision) {
    }

    default void toolCompleted(ProjectReviewRuntimeSession beforeRuntime,
                               ReviewToolExecutionResult executionResult) {
    }

    default void projectFinished(ProjectReviewRuntimeSession runtime) {
    }

    final class NoOpHolder {
        private static final ReviewRuntimeVisualizer INSTANCE = new ReviewRuntimeVisualizer() {
        };

        private NoOpHolder() {
        }
    }
}
