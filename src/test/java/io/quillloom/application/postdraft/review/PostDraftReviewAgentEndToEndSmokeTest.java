package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.command.StartProjectPostDraftReviewAgentCommand;
import io.quillloom.application.postdraft.review.model.EvidenceSufficiency;
import io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult;
import io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewReadDirection;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.ReviewToolExecutionResult;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionMode;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.application.postdraft.review.port.out.HumanInTheLoopGateway;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentTermWriter;
import io.quillloom.application.postdraft.review.service.DefaultProjectReviewRuntimePersistenceHook;
import io.quillloom.application.postdraft.review.service.PostDraftReviewAgentService;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProblemClassifier;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProcessSummaryAssembler;
import io.quillloom.application.postdraft.review.service.PostDraftReviewSessionFactory;
import io.quillloom.application.postdraft.review.service.ReviewRuntimeVisualizer;
import io.quillloom.application.postdraft.review.support.ScriptedReviewAgentGenerationPort;
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
import io.quillloom.infrastructure.postdraft.InMemoryPostDraftReviewPackageRepository;
import io.quillloom.infrastructure.postdraft.review.FileReviewSessionStore;
import io.quillloom.infrastructure.postdraft.review.InMemoryHumanInTheLoopGateway;
import io.quillloom.infrastructure.postdraft.review.PostgresPostDraftReviewAgentWriter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostDraftReviewAgentEndToEndSmokeTest {

    @Test
    void shouldPauseResumeAndFinishProjectWithScriptedGenerationPort() throws Exception {
        InMemoryPostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
        PostDraftReviewPackage reviewPackage = reviewPackageWithChunks(
                "project-1",
                List.of(chunk("chunk-1", "draft-1"), chunk("chunk-2", "draft-2"))
        );
        repository.save(reviewPackage);
        Path sessionRoot = Path.of("target", "test-review-agent-e2e-smoke");
        Files.createDirectories(sessionRoot);
        FileReviewSessionStore sessionStore = new FileReviewSessionStore(sessionRoot);
        PostgresPostDraftReviewAgentWriter writer = new PostgresPostDraftReviewAgentWriter(repository);
        HumanInTheLoopGateway humanGateway = new InMemoryHumanInTheLoopGateway();
        StaticReader reader = new StaticReader(reviewPackage);

        ScriptedReviewAgentGenerationPort firstPassPort = new ScriptedReviewAgentGenerationPort(
                List.of(
                        new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-1")), "done-1"),
                        new ReviewToolDecision("request_human_review", Map.of(), "need help")
                ),
                List.of(),
                List.of(),
                List.of()
        );
        PostDraftReviewAgentService firstPassService = newService(reader, writer, humanGateway, firstPassPort, sessionStore);

        PostDraftReviewAgentResult firstPass = firstPassService.reviewProject(
                new StartProjectPostDraftReviewAgentCommand("project-1", "operator note")
        );

        assertTrue(firstPass.humanReviewRequest().isPresent());
        assertEquals("draft-1", repository.load("project-1").orElseThrow().chunks().get(0).translatedText());
        assertEquals("draft-1", repository.load("project-1").orElseThrow().chunks().get(0).revisedTranslatedText());
        assertTrue(sessionStore.load("project-1").isPresent());

        ScriptedReviewAgentGenerationPort resumePort = new ScriptedReviewAgentGenerationPort(
                List.of(
                        new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-2")), "done-2"),
                        new ReviewToolDecision("complete_project", Map.of(), "finish")
                ),
                List.of(),
                List.of(),
                List.of()
        );
        PostDraftReviewAgentService resumeService = newService(reader, writer, humanGateway, resumePort, sessionStore);

        PostDraftReviewAgentResult resumed = resumeService.resumeProject("project-1", "Louki 统一译为露姬");

        assertEquals("draft-1\n\ndraft-2", resumed.finalMergedTranslatedText());
        assertEquals("draft-1\n\ndraft-2", repository.load("project-1").orElseThrow().mergedDraftText());
        assertTrue(sessionStore.load("project-1").isEmpty());
    }

    @Test
    void shouldFailWithNoProgressWithoutPersistingSession() throws Exception {
        InMemoryPostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
        PostDraftReviewPackage reviewPackage = reviewPackageWithChunks(
                "project-2",
                List.of(chunk("chunk-1", "draft-1"))
        );
        repository.save(reviewPackage);
        Path sessionRoot = Path.of("target", "test-review-agent-no-progress");
        Files.createDirectories(sessionRoot);
        FileReviewSessionStore sessionStore = new FileReviewSessionStore(sessionRoot);
        PostgresPostDraftReviewAgentWriter writer = new PostgresPostDraftReviewAgentWriter(repository);
        StaticReader reader = new StaticReader(reviewPackage);
        // NO_PROGRESS 的领域阈值仍然是 3 次同类 guardrail 拒绝。
        // 这里之所以要给 6 个 scripted 决策，是因为 provider 每轮会先消耗
        // “原始非法输出 + repair 后仍非法”这 2 个决策，最终才形成 1 次真正进入 executor 的拒绝。
        ScriptedReviewAgentGenerationPort generationPort = new ScriptedReviewAgentGenerationPort(
                List.of(
                        new ReviewToolDecision("nonexistent_tool", Map.of(), "reject-1"),
                        new ReviewToolDecision("nonexistent_tool", Map.of(), "reject-2"),
                        new ReviewToolDecision("nonexistent_tool", Map.of(), "reject-3"),
                        new ReviewToolDecision("nonexistent_tool", Map.of(), "reject-4"),
                        new ReviewToolDecision("nonexistent_tool", Map.of(), "reject-5"),
                        new ReviewToolDecision("nonexistent_tool", Map.of(), "reject-6")
                ),
                List.of(),
                List.of(),
                List.of()
        );
        PostDraftReviewAgentService service = newService(reader, writer, new InMemoryHumanInTheLoopGateway(), generationPort, sessionStore);

        PostDraftReviewAgentResult result = service.reviewProject(
                new StartProjectPostDraftReviewAgentCommand("project-2", "operator note")
        );

        assertTrue(result.humanReviewRequest().isEmpty());
        assertTrue(result.processSummary().processNote().contains("stopReason=no_progress"));
        assertTrue(sessionStore.load("project-2").isPresent());
    }

    @Test
    void shouldEscalateFromKeepBeforeRevisingConfirmedTermConflict() throws Exception {
        InMemoryPostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
        PostDraftReviewPackage reviewPackage = reviewPackageWithChunks(
                "project-term-conflict",
                List.of(chunk("chunk-1", "孔代咖啡馆里灯光昏暗。")),
                Map.of("Le Condé", "勒孔代咖啡馆")
        );
        repository.save(reviewPackage);
        Path sessionRoot = Path.of("target", "test-review-agent-term-conflict");
        Files.createDirectories(sessionRoot);
        FileReviewSessionStore sessionStore = new FileReviewSessionStore(sessionRoot);
        PostgresPostDraftReviewAgentWriter writer = new PostgresPostDraftReviewAgentWriter(repository);
        StaticReader reader = new StaticReader(reviewPackage);

        ScriptedReviewAgentGenerationPort generationPort = new ScriptedReviewAgentGenerationPort(
                List.of(
                        new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Le Condé")), "核对稳定译名"),
                        new ReviewToolDecision("evaluate_focus", Map.of(), "chunk 译文与项目级 confirmed terms 不一致，需升级"),
                        new ReviewToolDecision("draft_revision", Map.of(), "按稳定译名修订"),
                        new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-1")), "完成当前 chunk"),
                        new ReviewToolDecision("complete_project", Map.of(), "完成项目")
                ),
                List.of(new ReviewAgentEvaluation(
                        ReviewStrategy.LIGHT_EDIT,
                        "项目级 confirmed term 要求 Le Condé 译为勒孔代咖啡馆，当前 chunk 使用孔代咖啡馆，需要轻量修订。",
                        EvidenceSufficiency.SUFFICIENT,
                        false
                )),
                List.of(new RevisionDraft("勒孔代咖啡馆里灯光昏暗。", RevisionMode.LIGHT_EDIT, List.of(), List.of())),
                List.of(new RevisionSelfCheckResult(true, "术语已统一", List.of()))
        );
        PostDraftReviewAgentService service = newService(
                reader,
                writer,
                new InMemoryHumanInTheLoopGateway(),
                generationPort,
                sessionStore
        );

        PostDraftReviewAgentResult result = service.reviewProject(
                new StartProjectPostDraftReviewAgentCommand("project-term-conflict", "operator note")
        );

        assertTrue(result.humanReviewRequest().isEmpty());
        assertEquals(1, reader.readConfirmedTermRequests.size());
        assertEquals(List.of("Le Condé"), reader.readConfirmedTermRequests.get(0));
        assertEquals(
                "勒孔代咖啡馆里灯光昏暗。",
                repository.load("project-term-conflict").orElseThrow().chunks().get(0).revisedTranslatedText()
        );
    }

    @Test
    void shouldRecoverAfterDuplicateReadConfirmedTermsRejection() throws Exception {
        InMemoryPostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
        PostDraftReviewPackage reviewPackage = reviewPackageWithChunks(
                "project-duplicate-read-recovery",
                List.of(chunk("chunk-1", "孔代咖啡馆里灯光昏暗。")),
                Map.of("Le Conde", "孔代咖啡馆")
        );
        repository.save(reviewPackage);
        Path sessionRoot = Path.of("target", "test-review-agent-duplicate-read-recovery");
        Files.createDirectories(sessionRoot);
        FileReviewSessionStore sessionStore = new FileReviewSessionStore(sessionRoot);
        PostgresPostDraftReviewAgentWriter writer = new PostgresPostDraftReviewAgentWriter(repository);
        StaticReader reader = new StaticReader(reviewPackage);
        CapturingVisualizer visualizer = new CapturingVisualizer();

        ScriptedReviewAgentGenerationPort generationPort = new ScriptedReviewAgentGenerationPort(
                List.of(
                        new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Le Conde")), "lookup term"),
                        new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of(" le conde ")), "lookup again"),
                        new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-1")), "evidence is enough"),
                        new ReviewToolDecision("complete_project", Map.of(), "finish")
                ),
                List.of(),
                List.of(),
                List.of()
        );
        PostDraftReviewAgentService service = newService(
                reader,
                writer,
                new InMemoryHumanInTheLoopGateway(),
                generationPort,
                sessionStore,
                visualizer
        );

        PostDraftReviewAgentResult result = service.reviewProject(
                new StartProjectPostDraftReviewAgentCommand("project-duplicate-read-recovery", "operator note")
        );

        assertTrue(result.humanReviewRequest().isEmpty());
        assertEquals(1, reader.readConfirmedTermRequests.size());
        assertTrue(visualizer.completedSummaries.stream()
                .anyMatch(summary -> summary.contains("tool_result read_confirmed_terms")
                        && summary.contains("confirmedTerm=Le Conde->孔代咖啡馆")));
        assertTrue(visualizer.rejectionReasons.stream()
                .anyMatch(reason -> reason.contains("redundant_successful_tool_call")));
        assertEquals("孔代咖啡馆里灯光昏暗。", repository.load("project-duplicate-read-recovery").orElseThrow()
                .chunks().get(0).revisedTranslatedText());
        assertTrue(sessionStore.load("project-duplicate-read-recovery").isEmpty());
    }

    @Test
    void shouldCompleteAfterRevisionSelfCheckRetryPasses() throws Exception {
        InMemoryPostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
        PostDraftReviewPackage reviewPackage = reviewPackageWithChunks(
                "project-term-self-check-retry",
                List.of(chunk("chunk-1", "勒孔代咖啡馆里灯光昏暗。")),
                Map.of("Le Condé", "孔代咖啡馆")
        );
        repository.save(reviewPackage);
        Path sessionRoot = Path.of("target", "test-review-agent-term-self-check-retry");
        Files.createDirectories(sessionRoot);
        FileReviewSessionStore sessionStore = new FileReviewSessionStore(sessionRoot);
        PostgresPostDraftReviewAgentWriter writer = new PostgresPostDraftReviewAgentWriter(repository);
        StaticReader reader = new StaticReader(reviewPackage);

        ScriptedReviewAgentGenerationPort generationPort = new ScriptedReviewAgentGenerationPort(
                List.of(
                        new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Le Condé")), "核对稳定译名"),
                        new ReviewToolDecision("evaluate_focus", Map.of(), "chunk 译文与项目级 confirmed terms 不一致，需升级"),
                        new ReviewToolDecision("draft_revision", Map.of(), "按稳定译名修订"),
                        new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-1")), "完成当前 chunk"),
                        new ReviewToolDecision("complete_project", Map.of(), "完成项目")
                ),
                List.of(new ReviewAgentEvaluation(
                        ReviewStrategy.LIGHT_EDIT,
                        "项目级 confirmed term 要求 Le Condé 译为孔代咖啡馆，当前 chunk 使用勒孔代咖啡馆，需要轻量修订。",
                        EvidenceSufficiency.SUFFICIENT,
                        false
                )),
                List.of(new RevisionDraft("孔代咖啡馆里灯光昏暗。", RevisionMode.LIGHT_EDIT, List.of("修正 Le Condé 译名"), List.of())),
                List.of(
                        new RevisionSelfCheckResult(false, "confirmed_term_mismatch", List.of("Le Condé must be 孔代咖啡馆")),
                        new RevisionSelfCheckResult(true, "", List.of())
                )
        );
        PostDraftReviewAgentService service = newService(
                reader,
                writer,
                new InMemoryHumanInTheLoopGateway(),
                generationPort,
                sessionStore
        );

        PostDraftReviewAgentResult result = service.reviewProject(
                new StartProjectPostDraftReviewAgentCommand("project-term-self-check-retry", "operator note")
        );

        assertTrue(result.humanReviewRequest().isEmpty());
        assertEquals(
                "孔代咖啡馆里灯光昏暗。",
                repository.load("project-term-self-check-retry").orElseThrow().chunks().get(0).revisedTranslatedText()
        );
    }

    private PostDraftReviewAgentService newService(PostDraftReviewAgentReader reader,
                                                   PostgresPostDraftReviewAgentWriter writer,
                                                   HumanInTheLoopGateway humanGateway,
                                                   ScriptedReviewAgentGenerationPort generationPort,
                                                   FileReviewSessionStore sessionStore) {
        return newService(reader, writer, humanGateway, generationPort, sessionStore, ReviewRuntimeVisualizer.noop());
    }

    private PostDraftReviewAgentService newService(PostDraftReviewAgentReader reader,
                                                   PostgresPostDraftReviewAgentWriter writer,
                                                   HumanInTheLoopGateway humanGateway,
                                                   ScriptedReviewAgentGenerationPort generationPort,
                                                   FileReviewSessionStore sessionStore,
                                                   ReviewRuntimeVisualizer visualizer) {
        return new PostDraftReviewAgentService(
                reader,
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new PostDraftReviewProcessSummaryAssembler(),
                humanGateway,
                writer,
                PostDraftReviewAgentTermWriter.noop(),
                generationPort,
                sessionStore,
                visualizer,
                new DefaultProjectReviewRuntimePersistenceHook(writer, sessionStore)
        );
    }

    private PostDraftReviewPackage reviewPackageWithChunks(String projectId, List<PostDraftChunkRecord> chunks) {
        return reviewPackageWithChunks(projectId, chunks, Map.of());
    }

    private PostDraftReviewPackage reviewPackageWithChunks(String projectId,
                                                           List<PostDraftChunkRecord> chunks,
                                                           Map<String, String> confirmedTerms) {
        return new PostDraftReviewPackage(
                projectId,
                "v1",
                "en",
                "zh",
                "digest-1",
                Instant.parse("2026-04-18T00:00:00Z"),
                chunks,
                List.of(new PostDraftBlockIndex("block-1", "summary", chunks.stream().map(PostDraftChunkRecord::chunkId).toList())),
                new PostDraftTermState(confirmedTerms, List.of()),
                DraftStageGlobalGlossary.empty(),
                GlobalAliasConsistencyTable.empty(),
                ""
        );
    }

    private PostDraftChunkRecord chunk(String chunkId, String translatedText) {
        return new PostDraftChunkRecord(
                chunkId,
                1,
                "block-1",
                "source-" + chunkId,
                translatedText,
                null,
                "commentary",
                List.of(),
                Map.of(),
                List.<TranslationCandidateUpdate>of(),
                null
        );
    }

    private static final class StaticReader implements PostDraftReviewAgentReader {
        private final PostDraftContinuationContext context;
        private final java.util.ArrayList<List<String>> readConfirmedTermRequests = new java.util.ArrayList<>();

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
            return context.chunks();
        }

        @Override
        public List<PostDraftChunkRecord> searchChunksByKeyword(String projectId, String keyword) {
            return context.chunks();
        }

        @Override
        public List<String> listChunkIdsByProject(String projectId) {
            return context.chunks().stream().map(PostDraftChunkRecord::chunkId).toList();
        }

        @Override
        public Optional<PostDraftChunkRecord> loadChunkById(String projectId, String chunkId) {
            return context.chunks().stream().filter(chunk -> chunk.chunkId().equals(chunkId)).findFirst();
        }

        @Override
        public Map<String, String> readConfirmedTerms(String projectId, List<String> sourceTerms) {
            readConfirmedTermRequests.add(sourceTerms == null ? List.of() : List.copyOf(sourceTerms));
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

    private static final class CapturingVisualizer implements ReviewRuntimeVisualizer {
        private final java.util.ArrayList<String> completedSummaries = new java.util.ArrayList<>();
        private final java.util.ArrayList<String> rejectionReasons = new java.util.ArrayList<>();

        @Override
        public void toolCompleted(io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession beforeRuntime,
                                  ReviewToolExecutionResult executionResult) {
            if (executionResult.success()) {
                completedSummaries.add(executionResult.summary());
            } else {
                rejectionReasons.add(executionResult.rejection().rejectionReason());
            }
        }
    }
}
