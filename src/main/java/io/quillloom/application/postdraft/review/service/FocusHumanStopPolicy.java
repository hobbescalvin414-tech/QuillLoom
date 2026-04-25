package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;

public class FocusHumanStopPolicy {

    private final int maxRevisionAttempts;
    private final int maxSelfCheckFailures;

    public FocusHumanStopPolicy(int maxRevisionAttempts, int maxSelfCheckFailures) {
        if (maxRevisionAttempts <= 0) {
            throw new IllegalArgumentException("maxRevisionAttempts must be > 0");
        }
        if (maxSelfCheckFailures <= 0) {
            throw new IllegalArgumentException("maxSelfCheckFailures must be > 0");
        }
        this.maxRevisionAttempts = maxRevisionAttempts;
        this.maxSelfCheckFailures = maxSelfCheckFailures;
    }

    public boolean shouldEscalate(PostDraftReviewSession session, RevisionSelfCheckResult selfCheckResult) {
        if (!session.conflictingEvidenceSummaries().isEmpty()) {
            return true;
        }
        if ("hard_boundary_conflict".equals(selfCheckResult.stopReason())) {
            return true;
        }
        return "self_check_budget_exhausted".equals(selfCheckResult.stopReason());
    }
}
