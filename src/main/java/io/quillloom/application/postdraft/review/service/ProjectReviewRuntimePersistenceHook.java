package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;

public interface ProjectReviewRuntimePersistenceHook {

    static ProjectReviewRuntimePersistenceHook noop() {
        return (previousRuntime, currentRuntime) -> {
        };
    }

    void afterTransition(ProjectReviewRuntimeSession previousRuntime,
                         ProjectReviewRuntimeSession currentRuntime);
}
