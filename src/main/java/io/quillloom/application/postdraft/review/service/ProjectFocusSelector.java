package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;

public interface ProjectFocusSelector {

    ProjectReviewRuntimeSession selectNext(ProjectReviewRuntimeSession session);
}
