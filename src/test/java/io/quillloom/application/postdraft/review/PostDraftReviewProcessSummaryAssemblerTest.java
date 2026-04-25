package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.FocusReviewDiagnostics;
import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProblemType;
import io.quillloom.application.postdraft.review.model.ReviewProcessSummary;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewToolTrace;
import io.quillloom.application.postdraft.review.model.ReviewWorkingSet;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProcessSummaryAssembler;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PostDraftReviewProcessSummaryAssemblerTest {

    @Test
    void shouldMergeToolTracesAndDiagnosticsIntoSummary() {
        PostDraftReviewSession session = new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                ReviewWorkingSet.fromAnchor("chunk-1"),
                io.quillloom.application.postdraft.review.model.TranscriptStore.empty(),
                io.quillloom.application.postdraft.review.model.HistoryLog.empty(),
                io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle.fromLegacy(
                        List.of("ctx"),
                        List.of("seed-evidence"),
                        List.of("key-evidence"),
                        List.of("conflict-evidence"),
                        List.of("gap-evidence")
                ),
                io.quillloom.application.postdraft.review.model.ReviewVisitedObjects.from(Set.of("chunk:chunk-2")),
                List.of(new ReviewToolTrace("read_next_chunks", "read next", List.of("chunk-2"))),
                "operator-note",
                Set.of(ReviewProblemType.TRANSITION_CONTINUITY),
                ReviewStrategy.LIGHT_EDIT,
                new FocusReviewDiagnostics(1, 0, List.of("guardrail:missing_argument:count"))
        ).withRevisingState();
        PostDraftChunkRecord chunk = new PostDraftChunkRecord(
                "chunk-1",
                1,
                "block-1",
                "source text",
                "translated text",
                "commentary",
                List.of(),
                Map.of(),
                List.<TranslationCandidateUpdate>of(),
                null
        );

        ReviewProcessSummary summary = new PostDraftReviewProcessSummaryAssembler().assemble(
                session,
                chunk,
                ReviewStrategy.LIGHT_EDIT,
                session.problemTypes(),
                session.evidenceSummaries()
        );

        assertTrue(summary.evidenceSummaries().contains("key:key-evidence"));
        assertTrue(summary.evidenceSummaries().contains("conflict:conflict-evidence"));
        assertTrue(summary.evidenceSummaries().contains("gap:gap-evidence"));
        assertTrue(summary.evidenceSummaries().stream().anyMatch(text -> text.contains("tool=read_next_chunks")));
        assertTrue(summary.evidenceSummaries().stream().anyMatch(text -> text.contains("diagnostic:guardrail:missing_argument:count")));
        assertTrue(summary.processNote().contains("observationState=revising"));
        assertTrue(summary.processNote().contains("toolTraceCount=1"));
        assertTrue(summary.processNote().contains("localRejectionCount=1"));
    }
}
