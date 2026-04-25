package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.FocusAutonomyState;
import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewAgentState;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.application.postdraft.review.service.FocusHumanStopPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusHumanStopPolicyTest {

    @Test
    void shouldEscalateOnlyAfterExplicitStopPolicyMatched() {
        FocusHumanStopPolicy policy = new FocusHumanStopPolicy(2, 2);

        assertFalse(policy.shouldEscalate(
                sessionWithAutonomy(1, 0, 1),
                new RevisionSelfCheckResult(false, "still_incorrect", List.of("灞€閮ㄨ瘧鍚嶆湭鏀舵暃"))
        ));

        assertTrue(policy.shouldEscalate(
                sessionWithAutonomy(1, 1, 1),
                new RevisionSelfCheckResult(false, "self_check_budget_exhausted", List.of("杩炵画澶辫触"))
        ));
    }

    @Test
    void shouldNotEscalateOnlyBecauseRetryCountersReachedThresholdWithoutExplicitEscalationReason() {
        FocusHumanStopPolicy policy = new FocusHumanStopPolicy(2, 2);

        assertFalse(policy.shouldEscalate(
                sessionWithAutonomy(1, 2, 2),
                new RevisionSelfCheckResult(false, "still_incorrect", List.of("local retry should continue"))
        ));
    }

    private static PostDraftReviewSession sessionWithAutonomy(int investigationTurns,
                                                              int revisionAttempts,
                                                              int selfCheckFailures) {
        return new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                "operator-note",
                List.of(),
                Set.of(),
                List.of("seed-evidence"),
                ReviewStrategy.DEEP_EDIT,
                false,
                ReviewAgentState.REVISING,
                List.of(),
                Set.of(),
                List.of("key-evidence"),
                List.of(),
                List.of(),
                new FocusAutonomyState(
                        investigationTurns,
                        revisionAttempts,
                        selfCheckFailures,
                        List.of()
                )
        );
    }
}
