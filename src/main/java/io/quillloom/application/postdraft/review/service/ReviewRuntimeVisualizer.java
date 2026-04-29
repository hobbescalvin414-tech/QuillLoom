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

    default void focusRoundStarted(ProjectReviewRuntimeSession runtime) {
    }

    default void decisionProduced(ProjectReviewRuntimeSession runtime,
                                  ReviewToolDecision decision) {
    }

    default void toolCalled(ProjectReviewRuntimeSession runtime, ReviewToolDecision decision) {
    }

    default void toolCompleted(ProjectReviewRuntimeSession beforeRuntime,
                               ReviewToolExecutionResult executionResult) {
    }

    default void repairTriggered(ProjectReviewRuntimeSession runtime,
                                 String repairKind,
                                 String detail) {
    }

    default void toolRejected(ProjectReviewRuntimeSession runtime,
                              String detail) {
    }

    default void localReplanTriggered(ProjectReviewRuntimeSession runtime,
                                      String detail) {
    }

    default void containableFailureCaptured(ProjectReviewRuntimeSession runtime,
                                            String failureCode,
                                            String diagnosticSummary) {
    }

    default void focusRoundFinished(ProjectReviewRuntimeSession runtime) {
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
