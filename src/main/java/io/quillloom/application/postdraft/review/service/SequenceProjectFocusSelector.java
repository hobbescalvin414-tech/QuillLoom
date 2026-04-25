package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;

import java.util.Objects;

public class SequenceProjectFocusSelector implements ProjectFocusSelector {

    @Override
    public ProjectReviewRuntimeSession selectNext(ProjectReviewRuntimeSession session) {
        Objects.requireNonNull(session, "session");
        if (session.pendingChunkIds().isEmpty()) {
            return session.appendProcess("focusSelection=project_ready_for_completion");
        }
        return session.withSelectedFocus(session.pendingChunkIds().get(0));
    }
}
