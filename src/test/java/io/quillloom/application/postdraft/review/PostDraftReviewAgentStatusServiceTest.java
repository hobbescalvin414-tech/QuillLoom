package io.quillloom.application.postdraft.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quillloom.application.postdraft.review.model.PostDraftReviewProjectStatusView;
import io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome;
import io.quillloom.application.postdraft.review.model.ProjectIssueBacklog;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ProjectReviewStatus;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProcessSummary;
import io.quillloom.application.postdraft.review.model.ReviewProjectStopReason;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.StoredReviewSession;
import io.quillloom.application.postdraft.review.port.out.HumanInTheLoopGateway;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentWriter;
import io.quillloom.application.postdraft.review.port.out.ReviewSessionStore;
import io.quillloom.application.postdraft.review.service.PostDraftReviewAgentService;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProblemClassifier;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProcessSummaryAssembler;
import io.quillloom.application.postdraft.review.service.PostDraftReviewSessionFactory;
import io.quillloom.application.postdraft.review.service.PostDraftReviewAgentStatusService;
import io.quillloom.infrastructure.postdraft.review.FileReviewSessionStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostDraftReviewAgentStatusServiceTest {

    @Test
    void shouldPreferActiveRuntimeOverStoredSession() throws Exception {
        Path tempDir = Path.of("target", "test-review-agent-status-active");
        Files.createDirectories(tempDir);
        FileReviewSessionStore store = new FileReviewSessionStore(tempDir);
        store.save(waitingHumanRuntime("project-1"));

        ProjectReviewRuntimeSession activeRuntime = ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1"));
        FakeStatusAwareService service = new FakeStatusAwareService(Optional.of(activeRuntime));
        PostDraftReviewAgentStatusService statusService = new PostDraftReviewAgentStatusService(service, store);

        Optional<PostDraftReviewProjectStatusView> status = statusService.loadStatus("project-1");

        assertTrue(status.isPresent());
        assertEquals(ProjectReviewStatus.ACTIVE.name(), status.orElseThrow().status());
    }

    @Test
    void shouldReadWaitingHumanStatusFromStoredSessionWhenNoActiveRuntime() throws Exception {
        Path tempDir = Path.of("target", "test-review-agent-status-waiting");
        Files.createDirectories(tempDir);
        FileReviewSessionStore store = new FileReviewSessionStore(tempDir);
        store.save(waitingHumanRuntime("project-1"));

        FakeStatusAwareService service = new FakeStatusAwareService(Optional.empty());
        PostDraftReviewAgentStatusService statusService = new PostDraftReviewAgentStatusService(service, store);

        Optional<PostDraftReviewProjectStatusView> status = statusService.loadStatus("project-1");

        assertTrue(status.isPresent());
        assertEquals(ProjectReviewStatus.WAITING_HUMAN.name(), status.orElseThrow().status());
        assertTrue(status.orElseThrow().waitingHuman());
        assertEquals("请确认 Louki 的统一译名。", status.orElseThrow().latestHumanQuestion());
    }

    @Test
    void shouldReadStatusFromLegacyWaitingHumanSessionWithoutBoundaryAndMarkerFields() throws Exception {
        Path tempDir = Path.of("target", "test-review-agent-status-legacy-waiting");
        Files.createDirectories(tempDir);
        FileReviewSessionStore store = new FileReviewSessionStore(tempDir);
        writeLegacySessionJson(tempDir.resolve("project-legacy-status.json"), waitingHumanRuntime("project-legacy-status"));

        FakeStatusAwareService service = new FakeStatusAwareService(Optional.empty());
        PostDraftReviewAgentStatusService statusService = new PostDraftReviewAgentStatusService(service, store);

        Optional<PostDraftReviewProjectStatusView> status = statusService.loadStatus("project-legacy-status");

        assertTrue(status.isPresent());
        assertEquals(ProjectReviewStatus.WAITING_HUMAN.name(), status.orElseThrow().status());
        assertEquals("chunk-2", status.orElseThrow().currentChunkId());
    }

    @Test
    void shouldReadStatusFromLegacyFailedSessionWithoutBoundaryAndMarkerFields() throws Exception {
        Path tempDir = Path.of("target", "test-review-agent-status-legacy-failed");
        Files.createDirectories(tempDir);
        FileReviewSessionStore store = new FileReviewSessionStore(tempDir);
        writeLegacySessionJson(tempDir.resolve("project-legacy-failed.json"),
                ProjectReviewRuntimeSession.initialize("project-legacy-failed", List.of("chunk-2"))
                        .withSelectedFocus("chunk-2")
                        .failLlmCall("legacy llm failure"));

        FakeStatusAwareService service = new FakeStatusAwareService(Optional.empty());
        PostDraftReviewAgentStatusService statusService = new PostDraftReviewAgentStatusService(service, store);

        Optional<PostDraftReviewProjectStatusView> status = statusService.loadStatus("project-legacy-failed");

        assertTrue(status.isPresent());
        assertEquals(ProjectReviewStatus.FAILED.name(), status.orElseThrow().status());
        assertEquals(ReviewProjectStopReason.LLM_CALL_FAILED.name(), status.orElseThrow().stopReason());
    }

    private static void writeLegacySessionJson(Path target, ProjectReviewRuntimeSession runtime) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(MapperFeature.AUTO_DETECT_IS_GETTERS);
        ObjectNode root = (ObjectNode) mapper.readTree(mapper.writeValueAsString(StoredReviewSession.from(runtime)));
        JsonNode runtimeNode = root.path("runtime");
        if (runtimeNode instanceof ObjectNode runtimeObject) {
            JsonNode humanReviewNode = runtimeObject.path("humanReviewRequest");
            if (humanReviewNode instanceof ObjectNode humanReviewObject) {
                humanReviewObject.remove("questionForHuman");
            }
            JsonNode focusSessionNode = runtimeObject.path("currentFocusSession");
            if (focusSessionNode instanceof ObjectNode focusSessionObject) {
                focusSessionObject.remove("boundaryWindow");
                focusSessionObject.remove("readInFocusChunkIds");
                focusSessionObject.remove("verifiedInFocusChunkIds");
            }
        }
        Files.writeString(target, mapper.writeValueAsString(root));
    }

    private static ProjectReviewRuntimeSession waitingHumanRuntime(String projectId) {
        ReviewFocus focus = ReviewFocus.forChunk("chunk-2");
        ProjectChunkReviewOutcome completedOutcome = new ProjectChunkReviewOutcome(
                "chunk-1",
                "translated-1",
                ReviewStrategy.LIGHT_EDIT,
                new ReviewProcessSummary(
                        projectId,
                        ReviewFocus.forChunk("chunk-1"),
                        ReviewStrategy.LIGHT_EDIT,
                        Set.of(),
                        List.of("done"),
                        "completed"
                )
        );
        io.quillloom.application.postdraft.review.model.HumanReviewRequest request =
                new io.quillloom.application.postdraft.review.model.HumanReviewRequest(
                        projectId,
                        focus,
                        new ReviewProcessSummary(projectId, focus, ReviewStrategy.REQUIRE_HUMAN_REVIEW, Set.of(), List.of("need-help"), "paused"),
                        "请确认 Louki 的统一译名。",
                        "请确认 Louki 的统一译名。",
                        "project_waiting_human",
                        io.quillloom.application.postdraft.review.model.ReviewAgentState.WAITING_HUMAN,
                        "",
                        1,
                        1
                );
        return new ProjectReviewRuntimeSession(
                projectId,
                List.of("chunk-2"),
                List.of(completedOutcome),
                Optional.of("chunk-2"),
                Optional.empty(),
                io.quillloom.application.postdraft.review.model.TranscriptStore.empty(),
                io.quillloom.application.postdraft.review.model.HistoryLog.empty(),
                List.of(),
                Optional.of(request),
                ProjectReviewStatus.WAITING_HUMAN,
                ReviewProjectStopReason.HUMAN_REVIEW_REQUIRED,
                ProjectIssueBacklog.empty(),
                2
        );
    }

    private static final class FakeStatusAwareService extends PostDraftReviewAgentService {
        private final Optional<ProjectReviewRuntimeSession> activeRuntime;

        private FakeStatusAwareService(Optional<ProjectReviewRuntimeSession> activeRuntime) {
            super(
                    new NoOpReader(),
                    new PostDraftReviewSessionFactory(),
                    new PostDraftReviewProblemClassifier(),
                    new PostDraftReviewProcessSummaryAssembler(),
                    request -> request,
                    new NoOpWriter(),
                    null,
                    ReviewSessionStore.noop()
            );
            this.activeRuntime = activeRuntime;
        }

        @Override
        public Optional<ProjectReviewRuntimeSession> findActiveRuntime(String projectId) {
            return activeRuntime;
        }
    }

    private static final class NoOpWriter implements PostDraftReviewAgentWriter {
        @Override
        public io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult writeCompleted(String finalTranslatedText, ReviewProcessSummary processSummary) {
            return new io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult(finalTranslatedText, processSummary, Optional.empty());
        }

        @Override
        public io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult writeHumanRequired(io.quillloom.application.postdraft.review.model.HumanReviewRequest request) {
            return new io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult("", request.processSummary(), Optional.of(request));
        }
    }

    private static final class NoOpReader implements PostDraftReviewAgentReader {
        @Override
        public io.quillloom.domain.postdraft.PostDraftContinuationContext loadContinuationContext(String projectId, ReviewFocus focus) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<io.quillloom.domain.postdraft.PostDraftChunkRecord> readContinuousChunks(String projectId, String chunkId, io.quillloom.application.postdraft.review.model.ReviewReadDirection direction, int steps) {
            return List.of();
        }

        @Override
        public List<io.quillloom.domain.postdraft.PostDraftChunkRecord> expandByBlock(String projectId, String chunkId) {
            return List.of();
        }

        @Override
        public List<io.quillloom.domain.translation.TranslationDecisionNote> readDecisionNotes(String projectId, String chunkId) {
            return List.of();
        }

        @Override
        public Optional<io.quillloom.domain.translation.ChunkTransitionNote> readTransitionNote(String projectId, String chunkId) {
            return Optional.empty();
        }

        @Override
        public List<io.quillloom.domain.knowledge.KnowledgeCard> lookupKnowledgeCards(String projectId, String chunkId, List<String> queryTerms) {
            return List.of();
        }

        @Override
        public List<io.quillloom.domain.postdraft.PostDraftChunkRecord> readAdjacentChunks(String projectId, String chunkId, int before, int after) {
            return List.of();
        }

        @Override
        public List<io.quillloom.domain.postdraft.PostDraftChunkRecord> searchChunksByKeyword(String projectId, String keyword) {
            return List.of();
        }

        @Override
        public List<String> listChunkIdsByProject(String projectId) {
            return List.of();
        }

        @Override
        public Optional<io.quillloom.domain.postdraft.PostDraftChunkRecord> loadChunkById(String projectId, String chunkId) {
            return Optional.empty();
        }

        @Override
        public Map<String, String> readConfirmedTerms(String projectId, List<String> sourceTerms) {
            return Map.of();
        }
    }
}
