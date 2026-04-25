package io.quillloom.application.postdraft.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quillloom.application.postdraft.review.model.HumanReviewRequest;
import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ProjectReviewStatus;
import io.quillloom.application.postdraft.review.model.ProjectIssueBacklog;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProcessSummary;
import io.quillloom.application.postdraft.review.model.ReviewProjectStopReason;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewToolTrace;
import io.quillloom.application.postdraft.review.port.out.ReviewSessionStore;
import io.quillloom.infrastructure.postdraft.review.FileReviewSessionStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileReviewSessionStoreTest {

    @Test
    void shouldPersistSessionToLocalJsonFile() throws Exception {
        Path tempDir = Path.of("target", "test-review-session-store");
        Files.createDirectories(tempDir);
        ReviewSessionStore store = new FileReviewSessionStore(tempDir);
        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.initialize(
                "project-1",
                List.of("chunk-1", "chunk-2")
        );

        store.save(runtime);

        Path savedFile = tempDir.resolve("project-1.json");
        assertTrue(Files.exists(savedFile));
        assertTrue(Files.readString(savedFile).contains("\"projectId\":\"project-1\""));
        assertEquals("project-1", store.load("project-1").orElseThrow().projectId());
        Files.deleteIfExists(savedFile);
        Files.deleteIfExists(tempDir);
    }

    @Test
    void shouldPersistFullRuntimePayloadForWaitingHumanSession() throws Exception {
        Path tempDir = Path.of("target", "test-review-session-store-full-runtime");
        Files.createDirectories(tempDir);
        ReviewSessionStore store = new FileReviewSessionStore(tempDir);
        ProjectReviewRuntimeSession runtime = waitingHumanRuntime("project-1");

        store.save(runtime);

        Path savedFile = tempDir.resolve("project-1.json");
        String json = Files.readString(savedFile);
        assertTrue(json.contains("\"runtime\""), "stored session should contain full runtime payload");
        assertTrue(json.contains("\"status\":\"WAITING_HUMAN\""), "stored session should preserve WAITING_HUMAN");
        assertTrue(json.contains("\"humanReviewRequest\""), "stored session should preserve human review request");
        assertTrue(json.contains("\"completedChunkOutcomes\""), "stored session should preserve completed chunk outcomes");
        assertTrue(json.contains("\"currentFocusSession\""), "stored session should preserve current focus session");
    }

    @Test
    void shouldLoadLegacyWaitingHumanSessionWithoutBoundaryAndMarkerFields() throws Exception {
        Path tempDir = Path.of("target", "test-review-session-store-legacy-waiting");
        Files.createDirectories(tempDir);
        FileReviewSessionStore store = new FileReviewSessionStore(tempDir);
        writeLegacySessionJson(tempDir.resolve("project-legacy-waiting.json"), waitingHumanRuntime("project-legacy-waiting"));

        ProjectReviewRuntimeSession loaded = store.load("project-legacy-waiting").orElseThrow().runtime();

        assertEquals(ProjectReviewStatus.WAITING_HUMAN, loaded.status());
        PostDraftReviewSession focusSession = loaded.currentFocusSession().orElseThrow();
        assertTrue(focusSession.boundaryWindow().snapshots().isEmpty());
        assertTrue(focusSession.readInFocusChunkIds().isEmpty());
        assertTrue(focusSession.verifiedInFocusChunkIds().isEmpty());
    }

    @Test
    void shouldLoadLegacyFailedRuntimesWithoutBoundaryAndMarkerFields() throws Exception {
        Path tempDir = Path.of("target", "test-review-session-store-legacy-failed");
        Files.createDirectories(tempDir);
        FileReviewSessionStore store = new FileReviewSessionStore(tempDir);

        writeLegacySessionJson(tempDir.resolve("project-failed.json"), failedRuntime("project-failed", ReviewProjectStopReason.FAILED));
        writeLegacySessionJson(tempDir.resolve("project-no-progress.json"), failedRuntime("project-no-progress", ReviewProjectStopReason.NO_PROGRESS));
        writeLegacySessionJson(tempDir.resolve("project-llm.json"), failedRuntime("project-llm", ReviewProjectStopReason.LLM_CALL_FAILED));
        writeLegacySessionJson(tempDir.resolve("project-wall-clock.json"), failedRuntime("project-wall-clock", ReviewProjectStopReason.WALL_CLOCK_TIMEOUT));

        assertEquals(ReviewProjectStopReason.FAILED, store.load("project-failed").orElseThrow().runtime().stopReason());
        assertEquals(ReviewProjectStopReason.NO_PROGRESS, store.load("project-no-progress").orElseThrow().runtime().stopReason());
        assertEquals(ReviewProjectStopReason.LLM_CALL_FAILED, store.load("project-llm").orElseThrow().runtime().stopReason());
        assertEquals(ReviewProjectStopReason.WALL_CLOCK_TIMEOUT, store.load("project-wall-clock").orElseThrow().runtime().stopReason());
        assertTrue(store.load("project-llm").orElseThrow().runtime().currentFocusSession().orElseThrow().boundaryWindow().snapshots().isEmpty());
    }

    private void writeLegacySessionJson(Path target, ProjectReviewRuntimeSession runtime) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(MapperFeature.AUTO_DETECT_IS_GETTERS);
        ObjectNode root = (ObjectNode) mapper.readTree(mapper.writeValueAsString(io.quillloom.application.postdraft.review.model.StoredReviewSession.from(runtime)));
        JsonNode runtimeNode = root.path("runtime");
        if (runtimeNode instanceof ObjectNode runtimeObject) {
            JsonNode focusSessionNode = runtimeObject.path("currentFocusSession");
            if (focusSessionNode instanceof ObjectNode focusSessionObject) {
                focusSessionObject.remove("boundaryWindow");
                focusSessionObject.remove("readInFocusChunkIds");
                focusSessionObject.remove("verifiedInFocusChunkIds");
            }
        }
        Files.writeString(target, mapper.writeValueAsString(root));
    }

    private ProjectReviewRuntimeSession waitingHumanRuntime(String projectId) {
        ReviewFocus focus = ReviewFocus.forChunk("chunk-2");
        PostDraftReviewSession focusSession = PostDraftReviewSession.investigating(
                        projectId,
                        focus,
                        "operator-note",
                        Set.of(),
                        List.of("evidence-1")
                )
                .appendTranscript("focus-transcript")
                .appendHistory("focus-history", "detail")
                .appendToolTrace(new ReviewToolTrace("request_human_review", "need help", List.of("waiting")));
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
        HumanReviewRequest request = new HumanReviewRequest(
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
                Optional.of(focusSession.withWaitingHumanState()),
                io.quillloom.application.postdraft.review.model.TranscriptStore.empty().append("project-transcript"),
                io.quillloom.application.postdraft.review.model.HistoryLog.empty().add("project-history", "detail"),
                List.of("waitingHuman=project_waiting_human"),
                Optional.of(request),
                ProjectReviewStatus.WAITING_HUMAN,
                ReviewProjectStopReason.HUMAN_REVIEW_REQUIRED,
                ProjectIssueBacklog.empty(),
                2
        );
    }

    private ProjectReviewRuntimeSession failedRuntime(String projectId, ReviewProjectStopReason stopReason) {
        ReviewFocus focus = ReviewFocus.forChunk("chunk-2");
        PostDraftReviewSession focusSession = PostDraftReviewSession.investigating(
                projectId,
                focus,
                "operator-note",
                Set.of(),
                List.of("evidence-1")
        );
        ProjectReviewRuntimeSession base = new ProjectReviewRuntimeSession(
                projectId,
                List.of("chunk-2"),
                List.of(),
                Optional.of("chunk-2"),
                Optional.of(focusSession),
                io.quillloom.application.postdraft.review.model.TranscriptStore.empty(),
                io.quillloom.application.postdraft.review.model.HistoryLog.empty(),
                List.of(),
                Optional.empty(),
                ProjectReviewStatus.ACTIVE,
                ReviewProjectStopReason.NONE,
                ProjectIssueBacklog.empty(),
                1
        );
        return switch (stopReason) {
            case FAILED -> base.failProject("failed");
            case NO_PROGRESS -> base.failNoProgress("no-progress");
            case LLM_CALL_FAILED -> base.failLlmCall("llm");
            case WALL_CLOCK_TIMEOUT -> base.failWallClockTimeout("timeout");
            default -> throw new IllegalArgumentException("Unsupported stopReason=" + stopReason);
        };
    }
}
