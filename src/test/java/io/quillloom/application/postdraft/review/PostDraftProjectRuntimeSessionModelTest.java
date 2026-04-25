package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.HistoryLog;
import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome;
import io.quillloom.application.postdraft.review.model.ProjectIssueBacklog;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ProjectReviewStatus;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProcessSummary;
import io.quillloom.application.postdraft.review.model.ReviewProjectStopReason;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewWorkingSet;
import io.quillloom.application.postdraft.review.model.TranscriptStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostDraftProjectRuntimeSessionModelTest {

    @Test
    void shouldStartRuntimeWithActiveProjectStatusAndNoFocusSession() {
        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.start(
                "project-1",
                List.of("chunk-1", "chunk-2", "chunk-3")
        );

        assertEquals("project-1", runtime.projectId());
        assertEquals(List.of("chunk-1", "chunk-2", "chunk-3"), runtime.pendingChunkIds());
        assertTrue(runtime.completedChunkOutcomes().isEmpty());
        assertTrue(runtime.currentFocusSession().isEmpty());
        assertEquals(ProjectReviewStatus.ACTIVE, runtime.status());
        assertEquals(ReviewProjectStopReason.NONE, runtime.stopReason());
        assertEquals(TranscriptStore.empty(), runtime.transcriptStore());
        assertEquals(HistoryLog.empty(), runtime.historyLog());
    }

    @Test
    void shouldRejectDuplicateChunkIdsInsidePendingQueue() {
        assertThrows(IllegalArgumentException.class,
                () -> ProjectReviewRuntimeSession.start("project-1", List.of("chunk-1", "chunk-1")));
    }

    @Test
    void shouldKeepProjectRuntimeActiveWhileFocusSessionExists() {
        PostDraftReviewSession focusSession = focusSession("chunk-1");

        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.start(
                "project-1",
                List.of("chunk-1", "chunk-2")
        ).activateFocus(focusSession);

        assertEquals(ProjectReviewStatus.ACTIVE, runtime.status());
        assertEquals(ReviewProjectStopReason.NONE, runtime.stopReason());
        assertEquals("chunk-1", runtime.currentFocusSession().orElseThrow().focus().chunkId());
    }

    @Test
    void shouldDefensivelyCopyMutableRuntimeCollections() {
        ArrayList<String> pendingChunkIds = new ArrayList<>(List.of("chunk-1", "chunk-2"));
        ArrayList<String> processTrail = new ArrayList<>(List.of("focus=chunk-1"));
        ArrayList<String> transcriptEntries = new ArrayList<>(List.of("turn-1"));

        ProjectReviewRuntimeSession runtime = new ProjectReviewRuntimeSession(
                "project-1",
                pendingChunkIds,
                List.of(),
                java.util.Optional.of(focusSession("chunk-1")),
                new TranscriptStore(transcriptEntries, false),
                HistoryLog.empty(),
                processTrail,
                java.util.Optional.empty(),
                ProjectReviewStatus.ACTIVE,
                ReviewProjectStopReason.NONE
        );

        pendingChunkIds.add("chunk-3");
        processTrail.add("after");
        transcriptEntries.add("turn-2");

        assertEquals(List.of("chunk-1", "chunk-2"), runtime.pendingChunkIds());
        assertEquals(List.of("focus=chunk-1"), runtime.processTrail());
        assertEquals(List.of("turn-1"), runtime.transcriptStore().replay());
    }

    @Test
    void shouldRequireExplicitProjectCompletionAfterFinalWorkingSet() {
        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.start(
                "project-1",
                List.of("chunk-1")
        ).activateFocus(focusSession("chunk-1"));

        ProjectReviewRuntimeSession afterWorkingSet = runtime.completeWorkingSet(List.of(completedOutcome("chunk-1")));

        assertTrue(afterWorkingSet.pendingChunkIds().isEmpty());
        assertEquals(1, afterWorkingSet.completedChunkOutcomes().size());
        assertTrue(afterWorkingSet.currentFocusSession().isPresent());
        assertEquals(ProjectReviewStatus.ACTIVE, afterWorkingSet.status());
        assertEquals(ReviewProjectStopReason.NONE, afterWorkingSet.stopReason());

        ProjectReviewRuntimeSession completed = afterWorkingSet.completeProject();
        assertEquals(ProjectReviewStatus.COMPLETED, completed.status());
        assertEquals(ReviewProjectStopReason.PROJECT_COMPLETED, completed.stopReason());
    }

    @Test
    void shouldKeepProjectActiveAfterCompletingOneWorkingSetWithPendingChunksRemaining() {
        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.start(
                "project-1",
                List.of("chunk-1", "chunk-2")
        ).activateFocus(focusSession("chunk-1"));

        ProjectReviewRuntimeSession next = runtime.completeWorkingSet(List.of(completedOutcome("chunk-1")));

        assertEquals(List.of("chunk-2"), next.pendingChunkIds());
        assertEquals(ProjectReviewStatus.ACTIVE, next.status());
        assertEquals(ReviewProjectStopReason.NONE, next.stopReason());
        assertTrue(next.currentFocusSession().isEmpty());
    }

    @Test
    void shouldExposeProjectCompletionStateForPromptUse() {
        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.start(
                "project-1",
                List.of("chunk-2")
        ).withSelectedFocus("chunk-1");

        assertEquals(1, runtime.pendingChunkCount());
        assertEquals(0, runtime.completedChunkCount());
        assertTrue(!runtime.currentFocusChunkStillPending());
    }

    @Test
    void shouldAllowPendingEmptyAutoCompletionOnlyWithoutBlockingBacklog() {
        ProjectReviewRuntimeSession clearRuntime = ProjectReviewRuntimeSession.start("project-1", List.of());
        ProjectReviewRuntimeSession blockedRuntime = clearRuntime.withIssueBacklog(
                new ProjectIssueBacklog(List.of(new io.quillloom.application.postdraft.review.model.DeferredReviewIssue(
                        "issue-1",
                        "chunk-1",
                        "blocking failure"
                )))
        );

        assertTrue(clearRuntime.canAutoCompletePendingEmptyProject());
        assertTrue(!blockedRuntime.canAutoCompletePendingEmptyProject());
    }

    private static PostDraftReviewSession focusSession(String chunkId) {
        return new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk(chunkId),
                ReviewWorkingSet.fromAnchor(chunkId),
                TranscriptStore.empty(),
                HistoryLog.empty(),
                io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle.empty(),
                io.quillloom.application.postdraft.review.model.ReviewVisitedObjects.empty(),
                List.of(),
                "note",
                Set.of(),
                ReviewStrategy.KEEP,
                io.quillloom.application.postdraft.review.model.FocusReviewDiagnostics.empty()
        );
    }

    private static ProjectChunkReviewOutcome completedOutcome(String chunkId) {
        return new ProjectChunkReviewOutcome(
                chunkId,
                "final translation",
                ReviewStrategy.KEEP,
                new ReviewProcessSummary(
                        "project-1",
                        ReviewFocus.forChunk(chunkId),
                        ReviewStrategy.KEEP,
                        Set.of(),
                        List.of(),
                        "done"
                )
        );
    }
}
