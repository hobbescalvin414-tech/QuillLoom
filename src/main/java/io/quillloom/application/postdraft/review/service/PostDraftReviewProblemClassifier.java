package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.ReviewProblemType;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.util.LinkedHashSet;
import java.util.Set;

public class PostDraftReviewProblemClassifier {

    public Set<ReviewProblemType> classify(PostDraftChunkRecord chunk) {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk must not be null");
        }

        Set<ReviewProblemType> problems = new LinkedHashSet<>();
        if (!chunk.decisionNotes().isEmpty()) {
            problems.add(ReviewProblemType.UNRESOLVED_DECISION);
        }
        if (chunk.transitionNote() != null) {
            problems.add(ReviewProblemType.TRANSITION_CONTINUITY);
        }
        return Set.copyOf(problems);
    }
}
