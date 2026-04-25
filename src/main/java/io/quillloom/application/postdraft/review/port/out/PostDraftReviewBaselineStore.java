package io.quillloom.application.postdraft.review.port.out;

public interface PostDraftReviewBaselineStore {

    void createBaseline(String projectId);

    void restoreBaseline(String projectId);

    static PostDraftReviewBaselineStore noop() {
        return new PostDraftReviewBaselineStore() {
            @Override
            public void createBaseline(String projectId) {
            }

            @Override
            public void restoreBaseline(String projectId) {
            }
        };
    }
}
