package io.quillloom.application.postdraft.review.model;

public record PostDraftReviewActionBudget(int maxExpandableReadActions) {

    public PostDraftReviewActionBudget {
        if (maxExpandableReadActions < 0) {
            throw new IllegalArgumentException("maxExpandableReadActions must be >= 0");
        }
    }
}
