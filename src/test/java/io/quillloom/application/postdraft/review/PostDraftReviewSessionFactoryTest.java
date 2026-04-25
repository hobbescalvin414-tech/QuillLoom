package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.command.StartPostDraftReviewAgentCommand;
import io.quillloom.application.postdraft.review.model.FocusReviewDiagnostics;
import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.service.PostDraftReviewSessionFactory;
import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;
import io.quillloom.domain.postdraft.PostDraftBlockIndex;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import io.quillloom.domain.postdraft.PostDraftTermState;
import io.quillloom.domain.translation.ChunkTransitionNote;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostDraftReviewSessionFactoryTest {

    @Test
    void shouldCreateMinimalFocusSessionFromProjectIdAndChunkFocus() {
        StartPostDraftReviewAgentCommand command = new StartPostDraftReviewAgentCommand(
                "project-1",
                ReviewFocus.forChunk("chunk-3"),
                "优先检查衔接"
        );
        PostDraftReviewPackage reviewPackage = reviewPackageWithChunk("project-1", "chunk-3");

        PostDraftReviewSession session = new PostDraftReviewSessionFactory().create(command, reviewPackage);

        assertEquals("project-1", session.projectId());
        assertEquals("chunk-3", session.focus().chunkId());
        assertEquals(List.of("chunk-3"), session.workingSet().chunkIds());
        assertEquals("优先检查衔接", session.operatorNote());
        assertEquals(ReviewStrategy.KEEP, session.strategy());
        assertTrue(session.toolTraces().isEmpty());
        assertTrue(session.problemTypes().isEmpty());
        assertEquals(FocusReviewDiagnostics.empty(), session.diagnostics());
    }

    @Test
    void shouldCreateProjectFocusSessionWithoutStageMachineFields() {
        PostDraftChunkRecord chunk = reviewPackageWithChunk("project-1", "chunk-3").chunks().get(0);

        PostDraftReviewSession session = new PostDraftReviewSessionFactory().createProjectFocusSession(
                "project-1",
                "优先检查衔接",
                chunk,
                Set.of(),
                List.of("translatedTextLength=15")
        );

        assertEquals("chunk-3", session.focus().chunkId());
        assertEquals(List.of("chunk-3"), session.workingSet().chunkIds());
        assertEquals(List.of("translatedTextLength=15"), session.evidenceSummaries());
        assertEquals(FocusReviewDiagnostics.empty(), session.diagnostics());
    }

    @Test
    void shouldRejectBlankProjectIdInSession() {
        assertThrows(IllegalArgumentException.class, () -> new PostDraftReviewSession(
                " ",
                ReviewFocus.forChunk("chunk-3"),
                io.quillloom.application.postdraft.review.model.ReviewWorkingSet.fromAnchor("chunk-3"),
                io.quillloom.application.postdraft.review.model.TranscriptStore.empty(),
                io.quillloom.application.postdraft.review.model.HistoryLog.empty(),
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
    void shouldRejectProjectMismatchBetweenCommandAndReviewPackage() {
        StartPostDraftReviewAgentCommand command = new StartPostDraftReviewAgentCommand(
                "project-1",
                ReviewFocus.forChunk("chunk-3"),
                "优先检查衔接"
        );
        PostDraftReviewPackage reviewPackage = reviewPackageWithChunk("project-2", "chunk-3");

        assertThrows(IllegalArgumentException.class,
                () -> new PostDraftReviewSessionFactory().create(command, reviewPackage));
    }

    @Test
    void shouldRejectChunkFocusMissingFromReviewPackage() {
        StartPostDraftReviewAgentCommand command = new StartPostDraftReviewAgentCommand(
                "project-1",
                ReviewFocus.forChunk("chunk-9"),
                "优先检查衔接"
        );
        PostDraftReviewPackage reviewPackage = reviewPackageWithChunk("project-1", "chunk-3");

        assertThrows(IllegalArgumentException.class,
                () -> new PostDraftReviewSessionFactory().create(command, reviewPackage));
    }

    private static PostDraftReviewPackage reviewPackageWithChunk(String projectId, String chunkId) {
        PostDraftChunkRecord chunk = new PostDraftChunkRecord(
                chunkId,
                1,
                "block-1",
                "source text",
                "translated text",
                "commentary",
                List.of(),
                Map.of(),
                List.of(),
                new ChunkTransitionNote("before", "after", false)
        );
        return new PostDraftReviewPackage(
                projectId,
                "v1",
                "en",
                "zh",
                "digest-1",
                Instant.parse("2026-04-15T00:00:00Z"),
                List.of(chunk),
                List.of(new PostDraftBlockIndex("block-1", "block summary", List.of(chunkId))),
                new PostDraftTermState(Map.of(), List.of()),
                DraftStageGlobalGlossary.empty(),
                GlobalAliasConsistencyTable.empty(),
                "full merged draft text"
        );
    }
}
