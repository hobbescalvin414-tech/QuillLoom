package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ReviewAgentState;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewToolTrace;
import io.quillloom.application.postdraft.review.model.ReviewWorkingSet;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProcessSummaryAssembler;
import io.quillloom.application.postdraft.review.service.WorkingSetCompletionHandler;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftContinuationContext;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.translation.TranslationDecisionNote;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkingSetCompletionHandlerTest {

    @Test
    void shouldCreatePerChunkOutcomeFromFocusOnlyCompleteWorkingSet() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunk("chunk-1", "existing-1"),
                chunk("chunk-2", "existing-2")
        ));
        WorkingSetCompletionHandler handler = new WorkingSetCompletionHandler(
                reader,
                new PostDraftReviewProcessSummaryAssembler()
        );
        PostDraftReviewSession focusSession = new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                ReviewWorkingSet.fromAnchor("chunk-1").expandTo(List.of("chunk-1", "chunk-2")),
                io.quillloom.application.postdraft.review.model.ProjectIssueBacklog.empty(),
                io.quillloom.application.postdraft.review.model.TranscriptStore.empty(),
                io.quillloom.application.postdraft.review.model.HistoryLog.empty(),
                io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle.empty(),
                io.quillloom.application.postdraft.review.model.ReviewVisitedObjects.empty(),
                List.of(new ReviewToolTrace("draft_revision", "done", List.of("finalTranslation=revised-1"))),
                io.quillloom.application.postdraft.review.model.ReviewAgentConfig.defaultConfig(),
                "note",
                Set.of(),
                ReviewStrategy.LIGHT_EDIT,
                false,
                ReviewAgentState.REVISING,
                List.of(),
                io.quillloom.application.postdraft.review.model.FocusAutonomyState.initial(),
                io.quillloom.application.postdraft.review.model.ReviewAgentStopReason.NONE
        );
        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1", "chunk-2"))
                .withSelectedFocus("chunk-1")
                .withCurrentFocusSession(focusSession, 1, ReviewAgentState.INVESTIGATING);

        List<io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome> outcomes = handler.complete(
                runtime,
                List.of("chunk-1"),
                Map.of()
        );

        assertEquals(List.of("chunk-1"), outcomes.stream().map(io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome::chunkId).toList());
        assertEquals("revised-1", outcomes.get(0).finalTranslation());
    }

    @Test
    void shouldRejectCompletionWhenChunkIdsOmitAnchor() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunk("chunk-1", "existing-1"),
                chunk("chunk-2", "existing-2")
        ));
        WorkingSetCompletionHandler handler = new WorkingSetCompletionHandler(
                reader,
                new PostDraftReviewProcessSummaryAssembler()
        );
        ProjectReviewRuntimeSession runtime = runtimeWithWorkingSet("project-1", List.of("chunk-1", "chunk-2"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> handler.complete(runtime, List.of("chunk-2"), Map.of())
        );

        assertEquals("complete_working_set chunkIds must include anchorChunkId=chunk-1", error.getMessage());
    }

    @Test
    void shouldRejectCompletionWhenChunkIdsContainChunkOutsideWorkingSet() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunk("chunk-1", "existing-1"),
                chunk("chunk-2", "existing-2"),
                chunk("chunk-3", "existing-3")
        ));
        WorkingSetCompletionHandler handler = new WorkingSetCompletionHandler(
                reader,
                new PostDraftReviewProcessSummaryAssembler()
        );
        ProjectReviewRuntimeSession runtime = runtimeWithWorkingSet("project-1", List.of("chunk-1", "chunk-2"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> handler.complete(runtime, List.of("chunk-1", "chunk-3"), Map.of())
        );

        assertEquals(
                "complete_working_set chunkIds must stay within currentWorkingSet=[chunk-1, chunk-2], offendingChunkId=chunk-3",
                error.getMessage()
        );
    }

    @Test
    void shouldRejectSubmittingAnchorAndAnotherWorkingSetChunkTogether() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunk("chunk-1", "existing-1"),
                chunk("chunk-2", "existing-2")
        ));
        WorkingSetCompletionHandler handler = new WorkingSetCompletionHandler(
                reader,
                new PostDraftReviewProcessSummaryAssembler()
        );
        ProjectReviewRuntimeSession runtime = runtimeWithWorkingSet("project-1", List.of("chunk-1", "chunk-2"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> handler.complete(runtime, List.of("chunk-1", "chunk-2"), Map.of())
        );

        assertEquals(
                "complete_working_set currently allows only focusChunk=chunk-1; offendingChunkId=chunk-2",
                error.getMessage()
        );
    }

    private static ProjectReviewRuntimeSession runtimeWithWorkingSet(String projectId, List<String> workingSetChunkIds) {
        PostDraftReviewSession focusSession = new PostDraftReviewSession(
                projectId,
                ReviewFocus.forChunk("chunk-1"),
                ReviewWorkingSet.fromAnchor("chunk-1").expandTo(workingSetChunkIds),
                io.quillloom.application.postdraft.review.model.ProjectIssueBacklog.empty(),
                io.quillloom.application.postdraft.review.model.TranscriptStore.empty(),
                io.quillloom.application.postdraft.review.model.HistoryLog.empty(),
                io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle.empty(),
                io.quillloom.application.postdraft.review.model.ReviewVisitedObjects.empty(),
                List.of(new ReviewToolTrace("draft_revision", "done", List.of("finalTranslation=revised-1"))),
                io.quillloom.application.postdraft.review.model.ReviewAgentConfig.defaultConfig(),
                "note",
                Set.of(),
                ReviewStrategy.LIGHT_EDIT,
                false,
                ReviewAgentState.REVISING,
                List.of(),
                io.quillloom.application.postdraft.review.model.FocusAutonomyState.initial(),
                io.quillloom.application.postdraft.review.model.ReviewAgentStopReason.NONE
        );
        return ProjectReviewRuntimeSession.initialize(projectId, workingSetChunkIds)
                .withSelectedFocus("chunk-1")
                .withCurrentFocusSession(focusSession, 1, ReviewAgentState.INVESTIGATING);
    }

    private static PostDraftChunkRecord chunk(String chunkId, String translatedText) {
        return new PostDraftChunkRecord(
                chunkId,
                1,
                "block-1",
                "source text",
                translatedText,
                "commentary",
                List.of(),
                Map.of(),
                List.<TranslationCandidateUpdate>of(),
                null
        );
    }

    private static final class InMemoryReader implements io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader {
        private final List<PostDraftChunkRecord> chunks;

        private InMemoryReader(List<PostDraftChunkRecord> chunks) {
            this.chunks = List.copyOf(chunks);
        }

        @Override
        public PostDraftContinuationContext loadContinuationContext(String projectId, ReviewFocus focus) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PostDraftChunkRecord> readContinuousChunks(String projectId, String chunkId, io.quillloom.application.postdraft.review.model.ReviewReadDirection direction, int steps) {
            return chunks;
        }

        @Override
        public List<PostDraftChunkRecord> expandByBlock(String projectId, String chunkId) {
            return chunks;
        }

        @Override
        public List<TranslationDecisionNote> readDecisionNotes(String projectId, String chunkId) {
            return List.of();
        }

        @Override
        public Optional<ChunkTransitionNote> readTransitionNote(String projectId, String chunkId) {
            return Optional.empty();
        }

        @Override
        public List<KnowledgeCard> lookupKnowledgeCards(String projectId, String chunkId, List<String> queryTerms) {
            return List.of();
        }

        @Override
        public List<PostDraftChunkRecord> readAdjacentChunks(String projectId, String chunkId, int before, int after) {
            return chunks;
        }

        @Override
        public List<PostDraftChunkRecord> searchChunksByKeyword(String projectId, String keyword) {
            return chunks;
        }

        @Override
        public List<String> listChunkIdsByProject(String projectId) {
            return chunks.stream().map(PostDraftChunkRecord::chunkId).toList();
        }

        @Override
        public Optional<PostDraftChunkRecord> loadChunkById(String projectId, String chunkId) {
            return chunks.stream().filter(chunk -> chunk.chunkId().equals(chunkId)).findFirst();
        }

        @Override
        public Map<String, String> readConfirmedTerms(String projectId, List<String> sourceTerms) {
            return Map.of();
        }
    }
}
