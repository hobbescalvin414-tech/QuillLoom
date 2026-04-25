package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ReviewAgentState;
import io.quillloom.application.postdraft.review.service.SequenceProjectFocusSelector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectFocusSelectorTest {

    @Test
    void shouldSelectFirstPendingChunkInSequenceOrder() {
        ProjectReviewRuntimeSession session = ProjectReviewRuntimeSession.initialize(
                "project-1",
                List.of("chunk-1", "chunk-2")
        );

        ProjectReviewRuntimeSession selected = new SequenceProjectFocusSelector()
                .selectNext(session.withState(ReviewAgentState.SELECTING_FOCUS));

        assertEquals("chunk-1", selected.currentFocusChunkId().orElseThrow());
        assertEquals(ReviewAgentState.SELECTING_FOCUS, selected.state());
    }

    @Test
    void shouldEnterFinalizingWhenNoPendingChunkRemains() {
        ProjectReviewRuntimeSession session = ProjectReviewRuntimeSession.initialize("project-1", List.of())
                .withState(ReviewAgentState.SELECTING_FOCUS);

        ProjectReviewRuntimeSession selected = new SequenceProjectFocusSelector().selectNext(session);

        assertEquals(ReviewAgentState.SELECTING_FOCUS, selected.state());
        assertTrue(selected.currentFocusChunkId().isEmpty());
        assertTrue(selected.processTrail().contains("focusSelection=project_ready_for_completion"));
    }
}
