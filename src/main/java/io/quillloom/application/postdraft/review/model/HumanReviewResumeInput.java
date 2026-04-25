package io.quillloom.application.postdraft.review.model;

import java.util.Objects;

public record HumanReviewResumeInput(
        HumanReviewResumeDecision decision,
        String humanNote
) {

    public HumanReviewResumeInput {
        Objects.requireNonNull(decision, "decision");
        humanNote = humanNote == null ? "" : humanNote.trim();
    }
}
