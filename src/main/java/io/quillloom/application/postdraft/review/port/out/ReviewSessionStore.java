package io.quillloom.application.postdraft.review.port.out;

import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.StoredReviewSession;

import java.util.Optional;

public interface ReviewSessionStore {

    void save(ProjectReviewRuntimeSession runtime);

    Optional<StoredReviewSession> load(String projectId);

    void delete(String projectId);

    static ReviewSessionStore noop() {
        return new ReviewSessionStore() {
            @Override
            public void save(ProjectReviewRuntimeSession runtime) {
            }

            @Override
            public Optional<StoredReviewSession> load(String projectId) {
                return Optional.empty();
            }

            @Override
            public void delete(String projectId) {
            }
        };
    }
}
