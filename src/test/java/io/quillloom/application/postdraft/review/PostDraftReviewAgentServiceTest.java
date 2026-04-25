package io.quillloom.application.postdraft.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quillloom.application.postdraft.review.command.StartProjectPostDraftReviewAgentCommand;
import io.quillloom.application.postdraft.review.command.StartPostDraftReviewAgentCommand;
import io.quillloom.application.postdraft.review.model.EvidenceSufficiency;
import io.quillloom.application.postdraft.review.model.HumanReviewRequest;
import io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult;
import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome;
import io.quillloom.application.postdraft.review.model.ProjectIssueBacklog;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ProjectReviewStatus;
import io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProcessSummary;
import io.quillloom.application.postdraft.review.model.ReviewReadDirection;
import io.quillloom.application.postdraft.review.model.ReviewProjectStopReason;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewToolTrace;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionMode;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.application.postdraft.review.port.out.HumanInTheLoopGateway;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewBaselineStore;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentWriter;
import io.quillloom.application.postdraft.review.port.out.ReviewSessionStore;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;
import io.quillloom.application.postdraft.review.service.DefaultProjectReviewRuntimePersistenceHook;
import io.quillloom.application.postdraft.review.service.PostDraftReviewAgentService;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProblemClassifier;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProcessSummaryAssembler;
import io.quillloom.application.postdraft.review.service.PostDraftReviewSessionFactory;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;
import io.quillloom.domain.postdraft.PostDraftBlockIndex;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftContinuationContext;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import io.quillloom.domain.postdraft.PostDraftTermState;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.translation.TranslationDecisionNote;
import io.quillloom.infrastructure.postdraft.review.FileReviewSessionStore;
import io.quillloom.infrastructure.postdraft.review.FilePostDraftReviewBaselineStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostDraftReviewAgentServiceTest {

    @Test
    void shouldFailFastWhenGenerationPortIsMissing() {
        PostDraftReviewAgentService service = new PostDraftReviewAgentService(
                new StaticReader(reviewPackageWithChunks("project-1", List.of(chunk("chunk-1", "translated-1")))),
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new io.quillloom.application.postdraft.review.service.PostDraftReviewStrategyResolver(),
                new PostDraftReviewProcessSummaryAssembler(),
                new RecordingHumanGateway(),
                new RecordingWriter()
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.reviewProject(new StartProjectPostDraftReviewAgentCommand("project-1", "note"))
        );

        assertTrue(ex.getMessage().contains("ReviewAgentStructuredGenerationPort"));
    }

    @Test
    void shouldProduceFinalTranslationAndProcessSummary() {
        TestEnvironment environment = newEnvironment(
                reviewPackageWithChunks("project-1", List.of(chunk("chunk-1", "old translation"))),
                new SequenceGenerationPort(
                        new ReviewToolDecision("evaluate_focus", Map.of(), "assess strategy"),
                        new ReviewToolDecision("draft_revision", Map.of(), "revise anchor"),
                        new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-1")), "done"),
                        new ReviewToolDecision("complete_project", Map.of(), "finished")
                )
        );

        PostDraftReviewAgentResult result = environment.service.review(
                new StartPostDraftReviewAgentCommand("project-1", ReviewFocus.forChunk("chunk-1"), "check continuity")
        );

        assertEquals("structured revised translation", result.finalTranslatedText());
        assertTrue(result.humanReviewRequest().isEmpty());
        assertEquals(1, environment.writer.completedCalls);
        assertEquals(0, environment.humanGateway.submittedCalls);
        assertTrue(result.processSummary().processNote().contains("completedChunkCount=1"));
    }

    @Test
    void shouldAssembleProjectLevelFinalOutput() {
        TestEnvironment environment = newEnvironment(
                reviewPackageWithChunks(
                        "project-1",
                        List.of(
                                chunk("chunk-1", "translated-1"),
                                chunk("chunk-2", "translated-2"),
                                chunk("chunk-3", "translated-3")
                        )
                ),
                new SequenceGenerationPort(
                        new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-1")), "done-1"),
                        new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-2")), "done-2"),
                        new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-3")), "done-3"),
                        new ReviewToolDecision("complete_project", Map.of(), "project complete")
                )
        );

        PostDraftReviewAgentResult result = environment.service.reviewProject(
                new StartProjectPostDraftReviewAgentCommand("project-1", "project note")
        );

        assertEquals("translated-1\n\ntranslated-2\n\ntranslated-3", result.finalMergedTranslatedText());
        assertEquals(3, result.completedChunkResults().size());
        assertTrue(result.processSummary().processNote().contains("completedChunkCount=3"));
        assertTrue(result.humanReviewRequest().isEmpty());
    }

    @Test
    void shouldPauseProjectWhenToolRequestsHumanReview() {
        RecordingHumanGateway humanGateway = new RecordingHumanGateway(request -> new HumanReviewRequest(
                request.projectId(),
                request.focus(),
                request.processSummary(),
                request.requestNote(),
                request.questionForHuman() + " [accepted]",
                request.requestReason() + "_accepted",
                request.waitingState(),
                request.resumeHint(),
                request.completedChunkCount(),
                request.pendingChunkCount()
        ));
        TestEnvironment environment = newEnvironment(
                reviewPackageWithChunks(
                        "project-1",
                        List.of(
                                chunk("chunk-1", "translated-1"),
                                chunk("chunk-2", "translated-2")
                        )
                ),
                new SequenceGenerationPort(
                        new ReviewToolDecision("request_human_review", Map.of(), "need human")
                ),
                humanGateway
        );

        PostDraftReviewAgentResult result = environment.service.reviewProject(
                new StartProjectPostDraftReviewAgentCommand("project-1", "project note")
        );

        assertTrue(result.humanReviewRequest().isPresent());
        assertEquals("project_waiting_human_accepted", result.humanReviewRequest().orElseThrow().requestReason());
        assertTrue(result.humanReviewRequest().orElseThrow().questionForHuman().endsWith("[accepted]"));
        assertEquals(1, environment.humanGateway.submittedCalls);
    }

    @Test
    void shouldResumeProjectFromStoredWaitingHumanSession() throws Exception {
        SequenceGenerationPort generationPort = new SequenceGenerationPort(
                new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-2")), "done"),
                new ReviewToolDecision("complete_project", Map.of(), "finished")
        );
        Path tempDir = Path.of("target", "test-review-agent-service-resume");
        Files.createDirectories(tempDir);
        FileReviewSessionStore reviewSessionStore = new FileReviewSessionStore(tempDir);
        reviewSessionStore.save(waitingHumanRuntime("project-1"));
        PostDraftReviewAgentService service = new PostDraftReviewAgentService(
                new StaticReader(reviewPackageWithChunks(
                        "project-1",
                        List.of(chunk("chunk-1", "translated-1"), chunk("chunk-2", "translated-2"))
                )),
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new PostDraftReviewProcessSummaryAssembler(),
                new RecordingHumanGateway(),
                new RecordingWriter(),
                generationPort,
                reviewSessionStore
        );

        Method resumeProject = PostDraftReviewAgentService.class.getMethod("resumeProject", String.class, String.class);
        PostDraftReviewAgentResult result = (PostDraftReviewAgentResult) resumeProject.invoke(
                service,
                "project-1",
                "Louki 缁熶竴璇戜负闇插К"
        );

        assertTrue(result.completedChunkResults().size() > 0 || result.humanReviewRequest().isPresent());
    }

    @Test
    void shouldResumeProjectFromLegacyWaitingHumanSessionWithoutBoundaryAndMarkerFields() throws Exception {
        SequenceGenerationPort generationPort = new SequenceGenerationPort(
                new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-2")), "done"),
                new ReviewToolDecision("complete_project", Map.of(), "finished")
        );
        Path tempDir = Path.of("target", "test-review-agent-service-resume-legacy");
        Files.createDirectories(tempDir);
        FileReviewSessionStore reviewSessionStore = new FileReviewSessionStore(tempDir);
        writeLegacySessionJson(tempDir.resolve("project-legacy-resume.json"), waitingHumanRuntime("project-legacy-resume"));
        PostDraftReviewAgentService service = new PostDraftReviewAgentService(
                new StaticReader(reviewPackageWithChunks(
                        "project-legacy-resume",
                        List.of(chunk("chunk-1", "translated-1"), chunk("chunk-2", "translated-2"))
                )),
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new PostDraftReviewProcessSummaryAssembler(),
                new RecordingHumanGateway(),
                new RecordingWriter(),
                generationPort,
                reviewSessionStore
        );

        PostDraftReviewAgentResult result = service.resumeProject("project-legacy-resume", "Louki 缁熶竴璇戜负闇插К");

        assertTrue(result.humanReviewRequest().isEmpty());
        assertEquals(2, result.completedChunkResults().size());
        assertTrue(reviewSessionStore.load("project-legacy-resume").isEmpty());
    }

    private static void writeLegacySessionJson(Path target, ProjectReviewRuntimeSession runtime) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(MapperFeature.AUTO_DETECT_IS_GETTERS);
        ObjectNode root = (ObjectNode) mapper.readTree(mapper.writeValueAsString(io.quillloom.application.postdraft.review.model.StoredReviewSession.from(runtime)));
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

    @Test
    void shouldFailFastWhenLegacyWriterIsUsedForProjectLevelWriteback() {
        PostDraftReviewAgentWriter legacyWriter = new RecordingWriter();

        UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class,
                () -> legacyWriter.writeCompletedChunks("project-1", List.of())
        );

        assertTrue(ex.getMessage().contains("project-level"));
    }

    @Test
    void shouldConstructPersistenceHookWithApplicationWriterPort() throws Exception {
        Constructor<DefaultProjectReviewRuntimePersistenceHook> constructor =
                DefaultProjectReviewRuntimePersistenceHook.class.getConstructor(
                        PostDraftReviewAgentWriter.class,
                        ReviewSessionStore.class
                );

        RecordingWriter writer = new RecordingWriter();
        ReviewSessionStore sessionStore = ReviewSessionStore.noop();
        DefaultProjectReviewRuntimePersistenceHook hook = constructor.newInstance(writer, sessionStore);

        assertSame(hook.getClass(), DefaultProjectReviewRuntimePersistenceHook.class);
    }

    @Test
    void shouldExposeActiveRuntimeWhileProjectReviewIsRunning() throws Exception {
        CountDownLatch enteredDecision = new CountDownLatch(1);
        CountDownLatch releaseDecision = new CountDownLatch(1);
        SequenceGenerationPort generationPort = new BlockingSequenceGenerationPort(
                enteredDecision,
                releaseDecision,
                new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-1")), "done"),
                new ReviewToolDecision("complete_project", Map.of(), "finished")
        );
        TestEnvironment environment = newEnvironment(
                reviewPackageWithChunks("project-1", List.of(chunk("chunk-1", "translated-1"))),
                generationPort
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PostDraftReviewAgentResult> future = executor.submit(() ->
                    environment.service.reviewProject(new StartProjectPostDraftReviewAgentCommand("project-1", "project note"))
            );

            assertTrue(enteredDecision.await(5, TimeUnit.SECONDS));
            Optional<ProjectReviewRuntimeSession> activeRuntime = environment.service.findActiveRuntime("project-1");
            assertTrue(activeRuntime.isPresent());
            assertEquals(ProjectReviewStatus.ACTIVE, activeRuntime.orElseThrow().status());

            releaseDecision.countDown();
            future.get(5, TimeUnit.SECONDS);
            assertTrue(environment.service.findActiveRuntime("project-1").isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRestoreProjectPackageFromBaselineAndDeleteSession() throws Exception {
        io.quillloom.infrastructure.postdraft.InMemoryPostDraftReviewPackageRepository repository =
                new io.quillloom.infrastructure.postdraft.InMemoryPostDraftReviewPackageRepository();
        PostDraftReviewPackage baselinePackage = reviewPackageWithChunks(
                "project-1",
                List.of(chunk("chunk-1", "draft-1"), chunk("chunk-2", "draft-2"))
        );
        repository.save(baselinePackage);
        Path baselineRoot = Path.of("target", "test-review-agent-baseline-store");
        Path sessionRoot = Path.of("target", "test-review-agent-baseline-session");
        Files.createDirectories(baselineRoot);
        Files.createDirectories(sessionRoot);
        FileReviewSessionStore reviewSessionStore = new FileReviewSessionStore(sessionRoot, new ObjectMapper().findAndRegisterModules());
        PostDraftReviewBaselineStore baselineStore = new FilePostDraftReviewBaselineStore(
                baselineRoot,
                repository,
                new ObjectMapper().findAndRegisterModules()
        );
        PostDraftReviewAgentService service = new PostDraftReviewAgentService(
                new StaticReader(baselinePackage),
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new PostDraftReviewProcessSummaryAssembler(),
                new RecordingHumanGateway(),
                new RecordingWriter(),
                null,
                reviewSessionStore,
                baselineStore
        );

        service.createProjectReviewBaseline("project-1");

        PostDraftReviewPackage dirtyPackage = reviewPackageWithChunks(
                "project-1",
                List.of(
                        new PostDraftChunkRecord("chunk-1", 1, "block-1", "source text", "draft-1", "revised-1", "commentary", List.of(), Map.of(), List.of(), null),
                        new PostDraftChunkRecord("chunk-2", 1, "block-1", "source text", "draft-2", "revised-2", "commentary", List.of(), Map.of(), List.of(), null)
                )
        );
        repository.save(new PostDraftReviewPackage(
                dirtyPackage.projectId(),
                dirtyPackage.packageVersion(),
                dirtyPackage.sourceLanguage(),
                dirtyPackage.targetLanguage(),
                dirtyPackage.sourceDocumentDigest(),
                dirtyPackage.createdAt(),
                dirtyPackage.chunks(),
                dirtyPackage.blockIndexes(),
                dirtyPackage.termState(),
                dirtyPackage.glossarySnapshot(),
                dirtyPackage.aliasSnapshot(),
                "merged-dirty"
        ));
        reviewSessionStore.save(waitingHumanRuntime("project-1"));

        service.resetProjectFromBaseline("project-1");

        PostDraftReviewPackage restored = repository.load("project-1").orElseThrow();
        assertEquals("draft-1", restored.chunks().get(0).translatedText());
        assertEquals(null, restored.chunks().get(0).revisedTranslatedText());
        assertEquals("draft-2", restored.chunks().get(1).translatedText());
        assertEquals(null, restored.chunks().get(1).revisedTranslatedText());
        assertEquals(baselinePackage.mergedDraftText(), restored.mergedDraftText());
        assertTrue(reviewSessionStore.load("project-1").isEmpty());
    }

    private static TestEnvironment newEnvironment(PostDraftReviewPackage reviewPackage,
                                                  SequenceGenerationPort generationPort) {
        return newEnvironment(reviewPackage, generationPort, new RecordingHumanGateway());
    }

    private static TestEnvironment newEnvironment(PostDraftReviewPackage reviewPackage,
                                                  SequenceGenerationPort generationPort,
                                                  RecordingHumanGateway humanGateway) {
        RecordingWriter writer = new RecordingWriter();
        return new TestEnvironment(
                new PostDraftReviewAgentService(
                        new StaticReader(reviewPackage),
                        new PostDraftReviewSessionFactory(),
                        new PostDraftReviewProblemClassifier(),
                        new PostDraftReviewProcessSummaryAssembler(),
                        humanGateway,
                        writer,
                        generationPort
                ),
                writer,
                humanGateway
        );
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

    private record TestEnvironment(PostDraftReviewAgentService service,
                                   RecordingWriter writer,
                                   RecordingHumanGateway humanGateway) {
    }

    private static final class RecordingWriter implements PostDraftReviewAgentWriter {
        private int completedCalls;
        private int humanCalls;

        @Override
        public PostDraftReviewAgentResult writeCompleted(String finalTranslatedText, ReviewProcessSummary processSummary) {
            completedCalls++;
            return new PostDraftReviewAgentResult(finalTranslatedText, processSummary, Optional.empty());
        }

        @Override
        public PostDraftReviewAgentResult writeHumanRequired(HumanReviewRequest request) {
            humanCalls++;
            return new PostDraftReviewAgentResult("", request.processSummary(), Optional.of(request));
        }
    }

    private static final class RecordingHumanGateway implements HumanInTheLoopGateway {
        private int submittedCalls;
        private HumanReviewRequest lastSubmittedRequest;
        private final UnaryOperator<HumanReviewRequest> responseTransformer;

        private RecordingHumanGateway() {
            this(UnaryOperator.identity());
        }

        private RecordingHumanGateway(UnaryOperator<HumanReviewRequest> responseTransformer) {
            this.responseTransformer = responseTransformer;
        }

        @Override
        public HumanReviewRequest submit(HumanReviewRequest request) {
            submittedCalls++;
            lastSubmittedRequest = responseTransformer.apply(request);
            return lastSubmittedRequest;
        }
    }

    private static ProjectReviewRuntimeSession waitingHumanRuntime(String projectId) {
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

    private static PostDraftReviewPackage reviewPackageWithChunks(String projectId, List<PostDraftChunkRecord> chunks) {
        return new PostDraftReviewPackage(
                projectId,
                "v1",
                "en",
                "zh",
                "digest-1",
                Instant.parse("2026-04-15T00:00:00Z"),
                chunks,
                List.of(new PostDraftBlockIndex(
                        "block-1",
                        "block summary",
                        chunks.stream().map(PostDraftChunkRecord::chunkId).toList()
                )),
                new PostDraftTermState(Map.of(), List.of()),
                DraftStageGlobalGlossary.empty(),
                GlobalAliasConsistencyTable.empty(),
                "full merged draft text"
        );
    }

    private static class SequenceGenerationPort implements ReviewAgentStructuredGenerationPort {
        private final ArrayDeque<ReviewToolDecision> decisions;

        private SequenceGenerationPort(ReviewToolDecision... decisions) {
            this.decisions = new ArrayDeque<>(List.of(decisions));
        }

        @Override
        public ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
            return decisions.removeFirst();
        }

        @Override
        public ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt) {
            return new ReviewAgentEvaluation(
                    ReviewStrategy.DEEP_EDIT,
                    "need revision",
                    EvidenceSufficiency.SUFFICIENT,
                    false
            );
        }

        @Override
        public RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt) {
            return new RevisionDraft(
                    "structured revised translation",
                    RevisionMode.DEEP_EDIT,
                    List.of("port rationale"),
                    List.of()
            );
        }

        @Override
        public RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt) {
            return new RevisionSelfCheckResult(true, "", List.of());
        }
    }

    private static final class BlockingSequenceGenerationPort extends SequenceGenerationPort {
        private final CountDownLatch enteredDecision;
        private final CountDownLatch releaseDecision;

        private BlockingSequenceGenerationPort(CountDownLatch enteredDecision,
                                               CountDownLatch releaseDecision,
                                               ReviewToolDecision... decisions) {
            super(decisions);
            this.enteredDecision = enteredDecision;
            this.releaseDecision = releaseDecision;
        }

        @Override
        public ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
            enteredDecision.countDown();
            try {
                assertTrue(releaseDecision.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to release decision generation", ex);
            }
            return super.generateNextToolDecision(systemPrompt, userPrompt);
        }
    }

    private static final class StaticReader implements PostDraftReviewAgentReader {
        private final PostDraftContinuationContext context;

        private StaticReader(PostDraftReviewPackage reviewPackage) {
            this.context = new PostDraftContinuationContext(
                    reviewPackage.projectId(),
                    reviewPackage.chunks(),
                    reviewPackage.blockIndexes(),
                    reviewPackage.termState(),
                    reviewPackage.glossarySnapshot(),
                    reviewPackage.aliasSnapshot(),
                    reviewPackage.mergedDraftText(),
                    ProjectKnowledgeBase.empty(reviewPackage.projectId())
            );
        }

        @Override
        public PostDraftContinuationContext loadContinuationContext(String projectId, ReviewFocus focus) {
            return context;
        }

        @Override
        public List<PostDraftChunkRecord> readContinuousChunks(String projectId, String chunkId, ReviewReadDirection direction, int steps) {
            return context.chunks();
        }

        @Override
        public List<PostDraftChunkRecord> expandByBlock(String projectId, String chunkId) {
            return context.chunks();
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
            int index = listChunkIdsByProject(projectId).indexOf(chunkId);
            int from = Math.max(0, index - before);
            int to = Math.min(context.chunks().size(), index + after + 1);
            return context.chunks().subList(from, to);
        }

        @Override
        public List<PostDraftChunkRecord> searchChunksByKeyword(String projectId, String keyword) {
            return context.chunks();
        }

        @Override
        public List<String> listChunkIdsByProject(String projectId) {
            return context.chunks().stream()
                    .map(PostDraftChunkRecord::chunkId)
                    .toList();
        }

        @Override
        public Optional<PostDraftChunkRecord> loadChunkById(String projectId, String chunkId) {
            return context.chunks().stream()
                    .filter(chunk -> chunkId.equals(chunk.chunkId()))
                    .findFirst();
        }

        @Override
        public Map<String, String> readConfirmedTerms(String projectId, List<String> sourceTerms) {
            if (sourceTerms == null) {
                return Map.of();
            }
            return context.termState().effectiveConfirmedTerms().entrySet().stream()
                    .filter(entry -> sourceTerms.contains(entry.getKey()))
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (left, right) -> left,
                            java.util.LinkedHashMap::new
                    ));
        }
    }
}
