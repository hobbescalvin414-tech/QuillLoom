package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewAgentState;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusAutonomyStateModelTest {

    @Test
    void shouldDefaultFocusAutonomyStateWhenSessionCreated() {
        PostDraftReviewSession session = new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                "operator-note",
                List.of(),
                Set.of(),
                List.of(),
                ReviewStrategy.KEEP,
                false,
                ReviewAgentState.INITIALIZING,
                List.of(),
                Set.of(),
                List.of(),
                List.of(),
                List.of(),
                null
        );

        assertEquals(0, session.autonomyState().investigationTurns());
        assertEquals(0, session.autonomyState().revisionAttempts());
        assertEquals(0, session.autonomyState().selfCheckFailures());
        assertTrue(session.autonomyState().localFailureReasons().isEmpty());
    }
}
