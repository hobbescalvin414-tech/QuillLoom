package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.FocusReviewDiagnostics;
import io.quillloom.application.postdraft.review.model.HistoryLog;
import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot;
import io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProblemType;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewToolTrace;
import io.quillloom.application.postdraft.review.model.ReviewWorkingSetContext;
import io.quillloom.application.postdraft.review.model.ReviewWorkingSet;
import io.quillloom.application.postdraft.review.model.TranscriptStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostDraftReviewSessionModelTest {

    @Test
    void shouldDefaultDiagnosticsAndCollectionsForMinimalFocusSession() {
        PostDraftReviewSession session = new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                ReviewWorkingSet.fromAnchor("chunk-1"),
                null,
                null,
                null,
                null,
                null,
                "note",
                null,
                null,
                null
        );

        assertEquals(ReviewStrategy.KEEP, session.strategy());
        assertEquals(TranscriptStore.empty(), session.transcriptStore());
        assertEquals(HistoryLog.empty(), session.historyLog());
        assertEquals(ReviewWorkingSetContext.empty(), session.workingSetContext());
        assertTrue(session.readInFocusChunkIds().isEmpty());
        assertTrue(session.verifiedInFocusChunkIds().isEmpty());
        assertTrue(session.toolTraces().isEmpty());
        assertTrue(session.problemTypes().isEmpty());
        assertTrue(session.diagnostics().localRejectionReasons().isEmpty());
    }

    @Test
    void shouldKeepCollectionsDefensiveForFocusSession() {
        ReviewWorkingSet workingSet = ReviewWorkingSet.fromAnchor("chunk-1").expandTo(List.of("chunk-1", "chunk-2"));
        ArrayList<String> transcriptEntries = new ArrayList<>(List.of("turn-1"));
        ArrayList<ReviewToolTrace> toolTraces = new ArrayList<>(List.of(
                new ReviewToolTrace("read_next_chunks", "read next", List.of("chunk-2"))
        ));
        HashSet<ReviewProblemType> problemTypes = new HashSet<>(Set.of(ReviewProblemType.UNRESOLVED_DECISION));

        PostDraftReviewSession session = new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                workingSet,
                new TranscriptStore(transcriptEntries, false),
                HistoryLog.empty(),
                io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle.empty(),
                io.quillloom.application.postdraft.review.model.ReviewVisitedObjects.empty(),
                toolTraces,
                "note",
                problemTypes,
                ReviewStrategy.LIGHT_EDIT,
                new FocusReviewDiagnostics(1, 0, List.of("missing_argument:count"))
        );

        transcriptEntries.add("turn-2");
        toolTraces.clear();
        problemTypes.clear();

        assertEquals(List.of("turn-1"), session.transcriptStore().replay());
        assertEquals(1, session.toolTraces().size());
        assertEquals(Set.of(ReviewProblemType.UNRESOLVED_DECISION), session.problemTypes());
        assertEquals(List.of("missing_argument:count"), session.diagnostics().localRejectionReasons());
    }

    @Test
    void shouldDefensivelyCopyWorkingSetContextSnapshots() {
        ArrayList<ReviewContextChunkSnapshot> snapshots = new ArrayList<>(List.of(
                new ReviewContextChunkSnapshot(
                        "chunk-1",
                        10,
                        "source-1",
                        "translated-1",
                        "commentary-1",
                        List.of("decision-1"),
                        List.of("Alice->艾丽丝"),
                        "transition-1",
                        true
                )
        ));

        PostDraftReviewSession session = new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                ReviewWorkingSet.fromAnchor("chunk-1"),
                TranscriptStore.empty(),
                HistoryLog.empty(),
                ReviewEvidenceBundle.empty(),
                ReviewWorkingSetContext.of(snapshots),
                io.quillloom.application.postdraft.review.model.ReviewVisitedObjects.empty(),
                List.of(),
                "note",
                Set.of(),
                ReviewStrategy.KEEP,
                FocusReviewDiagnostics.empty()
        );

        snapshots.clear();

        assertEquals(1, session.workingSetContext().snapshots().size());
        assertEquals("chunk-1", session.workingSetContext().snapshots().get(0).chunkId());
    }

    @Test
    void shouldKeepLegacyConstructorCompatibleWithEmptyWorkingSetContext() {
        PostDraftReviewSession session = new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                "note",
                List.of("read-context"),
                Set.of(ReviewProblemType.UNRESOLVED_DECISION),
                List.of("evidence"),
                ReviewStrategy.KEEP,
                false,
                null,
                List.of(),
                Set.of("term:alice"),
                List.of("key"),
                List.of("conflict"),
                List.of("gap")
        );

        assertEquals(ReviewWorkingSetContext.empty(), session.workingSetContext());
        assertEquals(List.of("read-context"), session.readContextSummaries());
    }

    @Test
    void shouldRejectWorkingSetWhoseAnchorDoesNotMatchFocus() {
        assertThrows(IllegalArgumentException.class, () -> new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                ReviewWorkingSet.fromAnchor("chunk-2"),
                TranscriptStore.empty(),
                HistoryLog.empty(),
                io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle.empty(),
                io.quillloom.application.postdraft.review.model.ReviewVisitedObjects.empty(),
                List.of(),
                "note",
                Set.of(),
                ReviewStrategy.KEEP,
                FocusReviewDiagnostics.empty()
        ));
    }

    @Test
    void shouldTrackDiagnosticsWithoutStageStateOrWaitingFlag() {
        PostDraftReviewSession session = new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                ReviewWorkingSet.fromAnchor("chunk-1"),
                TranscriptStore.empty(),
                HistoryLog.empty(),
                io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle.empty(),
                io.quillloom.application.postdraft.review.model.ReviewVisitedObjects.empty(),
                List.of(),
                "note",
                Set.of(),
                ReviewStrategy.KEEP,
                new FocusReviewDiagnostics(2, 1, List.of("guardrail:missing_argument:chunkIds"))
        );

        assertEquals(2, session.diagnostics().revisionAttemptCount());
        assertEquals(1, session.diagnostics().selfCheckFailureCount());
        assertEquals(List.of("guardrail:missing_argument:chunkIds"), session.diagnostics().localRejectionReasons());
    }
}
