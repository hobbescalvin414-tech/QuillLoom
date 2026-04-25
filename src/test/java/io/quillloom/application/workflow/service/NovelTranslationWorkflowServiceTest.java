package io.quillloom.application.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quillloom.application.preprocess.assembler.PreprocessDossierAssembler;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.postdraft.assembler.PostDraftContinuationContextAssembler;
import io.quillloom.application.postdraft.assembler.PostDraftReviewPackageAssembler;
import io.quillloom.application.preprocess.service.PreprocessApplicationService;
import io.quillloom.application.translation.assembler.DraftCompilationAssembler;
import io.quillloom.application.translation.assembler.TranslationTaskInputAssembler;
import io.quillloom.application.translation.model.TranslationDraftRunResult;
import io.quillloom.application.translation.service.TranslationApplicationService;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.memory.ChapterMemorySnapshot;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.postdraft.PostDraftContinuationContext;
import io.quillloom.infrastructure.postdraft.InMemoryPostDraftReviewPackageRepository;
import io.quillloom.infrastructure.preprocess.InMemoryProjectKnowledgeBaseRepository;
import io.quillloom.infrastructure.workflow.trace.WorkflowDraftArtifactWriter;
import io.quillloom.infrastructure.workflow.trace.WorkflowTraceArtifactWriter;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.translation.TranslationDecisionNote;
import io.quillloom.domain.translation.TranslationRuntimeOptions;
import io.quillloom.domain.workflow.TranslationWorkflowStage;
import io.quillloom.support.BookAnalysisTestSupport;
import io.quillloom.support.PreprocessTestSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NovelTranslationWorkflowServiceTest {

    @Test
    void shouldAdvanceWorkflowStateAcrossPreprocessDraftAndCompileStages() {
        PreprocessApplicationService preprocessService = new PreprocessApplicationService(
                BookAnalysisTestSupport.createBookAnalyzer(),
                PreprocessTestSupport.createChunkAnnotator(),
                PreprocessTestSupport.createKnowledgeEnricher(),
                new PreprocessDossierAssembler()
        );
        TranslationApplicationService translationService = new TranslationApplicationService(
                new TranslationTaskInputAssembler(),
                input -> new ChunkTranslationDraft(
                        input.sourceMaterial().chunk().chunk().chunkId(),
                        "draft-text",
                        "keep names consistent",
                        List.of(new TranslationDecisionNote("unresolved", "chunk-1", "name still pending", "keep current rendering")),
                        Map.of("Alice", "Alice-zh"),
                        List.of(new TranslationCandidateUpdate("Bob", "Bob-zh", "common transliteration", true)),
                        new ChunkTransitionNote("entering city scene", "turning into riverside dialogue", true)
                )
        );
        NovelTranslationWorkflowService workflowService = new NovelTranslationWorkflowService(
                preprocessService,
                translationService,
                new DraftCompilationAssembler()
        );

        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-1",
                "sample-novel",
                "Alice met Bob in Paris.\n\nThey walked along the river and talked about the old house.",
                "en",
                "zh"
        );

        var initialized = workflowService.start("project-1");
        var preprocessed = workflowService.runPreprocess(command, initialized);
        var drafted = workflowService.recordChunkDrafts(preprocessed, List.of(
                new ChunkTranslationDraft(
                        "chunk-1",
                        "draft-text",
                        "keep names consistent",
                        List.of(new TranslationDecisionNote("unresolved", "chunk-1", "name still pending", "keep current rendering")),
                        Map.of("Alice", "Alice-zh"),
                        List.of(new TranslationCandidateUpdate("Bob", "Bob-zh", "common transliteration", true)),
                        new ChunkTransitionNote("entering city scene", "turning into riverside dialogue", true)
                )
        ));
        var compiled = workflowService.compileDrafts(drafted);

        assertEquals(TranslationWorkflowStage.INITIALIZED, initialized.stage());
        assertEquals(TranslationWorkflowStage.PREPROCESSED, preprocessed.stage());
        assertEquals(TranslationWorkflowStage.DRAFTED, drafted.stage());
        assertEquals(TranslationWorkflowStage.COMPILED, compiled.stage());
        assertEquals("draft-text", compiled.draftCompilation().mergedDraft());
        assertFalse(drafted.hasFallbackDrafts());
    }

    @Test
    void shouldExposeFallbackChunksAtWorkflowLevel() {
        PreprocessApplicationService preprocessService = new PreprocessApplicationService(
                BookAnalysisTestSupport.createBookAnalyzer(),
                PreprocessTestSupport.createChunkAnnotator(),
                PreprocessTestSupport.createKnowledgeEnricher(),
                new PreprocessDossierAssembler()
        );
        TranslationApplicationService translationService = new TranslationApplicationService(
                new TranslationTaskInputAssembler(),
                input -> new ChunkTranslationDraft(
                        input.sourceMaterial().chunk().chunk().chunkId(),
                        "draft-" + input.sourceMaterial().chunk().chunk().sequence(),
                        "sequential execution",
                        input.sourceMaterial().chunk().chunk().sequence() == 1
                                ? List.of(new TranslationDecisionNote(
                                "revision-round-fallback",
                                "current-chunk",
                                "round two failed and system kept round one",
                                "flag for later review"
                        ))
                                : List.of(),
                        Map.of(),
                        List.of(),
                        new ChunkTransitionNote("", "", false)
                )
        );
        NovelTranslationWorkflowService workflowService = new NovelTranslationWorkflowService(
                preprocessService,
                translationService,
                new DraftCompilationAssembler()
        );

        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-2",
                "sample-novel",
                String.join("\n\n",
                        "First paragraph sets the rainy Paris street and the distant bells while Erin walks toward the bridge. ".repeat(12),
                        "Second paragraph places Erin on the bridge where she meets an old friend and mentions the northern house. ".repeat(12),
                        "Third paragraph follows their silent walk away from the bridge as carriage sounds and church bells continue. ".repeat(12)
                ),
                "en",
                "zh"
        );

        var initialized = workflowService.start("project-2");
        var preprocessed = workflowService.runPreprocess(command, initialized);
        var drafted = workflowService.draftAllChunks(preprocessed, null, null, TranslationRuntimeOptions.defaults());

        assertEquals(TranslationWorkflowStage.DRAFTED, drafted.stage());
        assertTrue(drafted.chunkDrafts().size() >= 2);
        assertTrue(drafted.hasFallbackDrafts());
        assertEquals(List.of(drafted.chunkDrafts().get(0).chunkId()), drafted.fallbackChunkIds());
    }

    @Test
    void shouldRunPreprocessDraftAndCompileInOneWorkflowCall() {
        PreprocessApplicationService preprocessService = new PreprocessApplicationService(
                BookAnalysisTestSupport.createBookAnalyzer(),
                PreprocessTestSupport.createChunkAnnotator(),
                PreprocessTestSupport.createKnowledgeEnricher(),
                new PreprocessDossierAssembler()
        );
        TranslationApplicationService translationService = new TranslationApplicationService(
                new TranslationTaskInputAssembler(),
                input -> new ChunkTranslationDraft(
                        input.sourceMaterial().chunk().chunk().chunkId(),
                        "run-" + input.sourceMaterial().chunk().chunk().sequence(),
                        "full workflow run",
                        List.of(),
                        Map.of(),
                        List.of(),
                        new ChunkTransitionNote("", "", false)
                )
        );
        NovelTranslationWorkflowService workflowService = new NovelTranslationWorkflowService(
                preprocessService,
                translationService,
                new DraftCompilationAssembler()
        );

        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-3",
                "sample-novel",
                String.join("\n\n",
                        "Alice met Bob in Paris and they paused by the old bridge. ".repeat(10),
                        "They continued walking while discussing the house by the river. ".repeat(10)
                ),
                "en",
                "zh"
        );

        var state = workflowService.runDraftWorkflow(
                command,
                new ProjectMemorySnapshot("project-3", Map.of(), List.of(), List.of()),
                new ChapterMemorySnapshot("project-3-chapter-1", Map.of(), List.of(), List.of()),
                TranslationRuntimeOptions.defaults()
        );

        assertEquals(TranslationWorkflowStage.COMPILED, state.stage());
        assertTrue(state.chunkDrafts().size() >= 1);
        assertTrue(state.draftCompilation().mergedDraft().contains("run-1"));
    }

    @Test
    void shouldSavePostDraftReviewPackageAfterDraftWorkflow() {
        PreprocessApplicationService preprocessService = new PreprocessApplicationService(
                BookAnalysisTestSupport.createBookAnalyzer(),
                PreprocessTestSupport.createChunkAnnotator(),
                PreprocessTestSupport.createKnowledgeEnricher(),
                new PreprocessDossierAssembler()
        );
        TranslationApplicationService translationService = new TranslationApplicationService(
                new TranslationTaskInputAssembler(),
                input -> new ChunkTranslationDraft(
                        input.sourceMaterial().chunk().chunk().chunkId(),
                        "draft-text",
                        "keep names consistent",
                        List.of(new TranslationDecisionNote("risk", "chunk-1", "needs review", "read next chunk")),
                        Map.of("Louki", "露姬"),
                        List.of(new TranslationCandidateUpdate("Black Maria", "黑色马车", "候选", true)),
                        new ChunkTransitionNote("before", "after", false)
                )
        );
        InMemoryProjectKnowledgeBaseRepository knowledgeBaseRepository = new InMemoryProjectKnowledgeBaseRepository();
        InMemoryPostDraftReviewPackageRepository reviewPackageRepository = new InMemoryPostDraftReviewPackageRepository();
        NovelTranslationWorkflowService workflowService = new NovelTranslationWorkflowService(
                preprocessService,
                translationService,
                new DraftCompilationAssembler(),
                new PostDraftReviewPackageAssembler(),
                new PostDraftContinuationContextAssembler(),
                knowledgeBaseRepository,
                reviewPackageRepository
        );

        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-save-package",
                "sample-novel",
                "Louki waited by the dark street.\n\nShe heard the Black Maria approach.",
                "fr",
                "zh"
        );
        ProjectMemorySnapshot projectMemory = new ProjectMemorySnapshot(
                "project-save-package",
                Map.of("Louki", "露姬"),
                List.of(),
                List.of(),
                List.of()
        );
        ChapterMemorySnapshot chapterMemory = new ChapterMemorySnapshot("chapter-1", Map.of(), List.of(), List.of());

        var state = workflowService.runDraftWorkflow(command, projectMemory, chapterMemory, TranslationRuntimeOptions.defaults());
        var reviewPackage = reviewPackageRepository.load("project-save-package").orElseThrow();

        assertEquals("project-save-package", reviewPackage.projectId());
        assertTrue(reviewPackage.chunks().size() >= 1);
        assertEquals("露姬", reviewPackage.termState().effectiveConfirmedTerms().get("Louki"));
        assertEquals(TranslationWorkflowStage.COMPILED, state.stage());
    }

    @Test
    void shouldSavePostDraftReviewPackageFromFinalProjectMemory() {
        PreprocessApplicationService preprocessService = new PreprocessApplicationService(
                BookAnalysisTestSupport.createBookAnalyzer(),
                PreprocessTestSupport.createChunkAnnotator(),
                PreprocessTestSupport.createKnowledgeEnricher(),
                new PreprocessDossierAssembler()
        );
        InMemoryPostDraftReviewPackageRepository reviewPackageRepository = new InMemoryPostDraftReviewPackageRepository();
        NovelTranslationWorkflowService workflowService = new NovelTranslationWorkflowService(
                preprocessService,
                new FinalMemoryOnlyTranslationService(),
                new DraftCompilationAssembler(),
                new PostDraftReviewPackageAssembler(),
                new PostDraftContinuationContextAssembler(),
                new InMemoryProjectKnowledgeBaseRepository(),
                reviewPackageRepository
        );

        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-final-memory-package",
                "sample-novel",
                "A waited by the door.\n\nA returned later.",
                "en",
                "zh"
        );

        var state = workflowService.runDraftWorkflow(
                command,
                new ProjectMemorySnapshot("project-final-memory-package", Map.of(), List.of(), List.of()),
                new ChapterMemorySnapshot("chapter-1", Map.of(), List.of(), List.of()),
                TranslationRuntimeOptions.defaults()
        );
        var reviewPackage = reviewPackageRepository.load("project-final-memory-package").orElseThrow();

        assertEquals("甲", state.finalProjectMemory().confirmedTerms().get("A"));
        assertEquals("甲", reviewPackage.termState().effectiveConfirmedTerms().get("A"));
    }

    @Test
    void shouldLoadPostDraftContinuationContextByProjectId() {
        PreprocessApplicationService preprocessService = new PreprocessApplicationService(
                BookAnalysisTestSupport.createBookAnalyzer(),
                PreprocessTestSupport.createChunkAnnotator(),
                PreprocessTestSupport.createKnowledgeEnricher(),
                new PreprocessDossierAssembler()
        );
        TranslationApplicationService translationService = new TranslationApplicationService(
                new TranslationTaskInputAssembler(),
                input -> new ChunkTranslationDraft(
                        input.sourceMaterial().chunk().chunk().chunkId(),
                        "draft-text",
                        "commentary",
                        List.of(),
                        Map.of("Louki", "露姬"),
                        List.of(),
                        new ChunkTransitionNote("", "", false)
                )
        );
        InMemoryProjectKnowledgeBaseRepository knowledgeBaseRepository = new InMemoryProjectKnowledgeBaseRepository();
        InMemoryPostDraftReviewPackageRepository reviewPackageRepository = new InMemoryPostDraftReviewPackageRepository();
        NovelTranslationWorkflowService workflowService = new NovelTranslationWorkflowService(
                preprocessService,
                translationService,
                new DraftCompilationAssembler(),
                new PostDraftReviewPackageAssembler(),
                new PostDraftContinuationContextAssembler(),
                knowledgeBaseRepository,
                reviewPackageRepository
        );

        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-load-context",
                "sample-novel",
                "Louki waited by the dark street.\n\nShe heard the Black Maria approach.",
                "fr",
                "zh"
        );
        ProjectMemorySnapshot projectMemory = new ProjectMemorySnapshot(
                "project-load-context",
                Map.of("Louki", "露姬"),
                List.of(),
                List.of(),
                List.of()
        );
        ChapterMemorySnapshot chapterMemory = new ChapterMemorySnapshot("chapter-1", Map.of(), List.of(), List.of());

        workflowService.runDraftWorkflow(command, projectMemory, chapterMemory, TranslationRuntimeOptions.defaults());
        knowledgeBaseRepository.save(ProjectKnowledgeBase.empty("project-load-context"));

        PostDraftContinuationContext context = workflowService.loadPostDraftContinuationContext("project-load-context");

        assertEquals("project-load-context", context.projectId());
        assertTrue(context.chunks().size() >= 1);
        assertEquals("project-load-context", context.knowledgeBase().projectId());
    }

    @Test
    void shouldPreserveBusinessExceptionWhenTraceFlushFails() {
        IllegalStateException businessException = new IllegalStateException("business failure");
        NovelTranslationWorkflowService workflowService = new NovelTranslationWorkflowService(
                new FailingPreprocessApplicationService(businessException),
                new TranslationApplicationService(
                        new TranslationTaskInputAssembler(),
                        input -> {
                            throw new IllegalStateException("should not translate");
                        }
                ),
                new DraftCompilationAssembler(),
                new PostDraftReviewPackageAssembler(),
                new PostDraftContinuationContextAssembler(),
                new InMemoryProjectKnowledgeBaseRepository(),
                new InMemoryPostDraftReviewPackageRepository(),
                new WorkflowTraceRecorder(),
                new FailingTraceArtifactWriter(),
                new WorkflowDraftArtifactWriter(Path.of("target", "unused-draft-writer"))
        );

        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-flush-failure",
                "sample",
                "source text",
                "en",
                "zh"
        );

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> workflowService.runDraftWorkflow(
                command,
                new ProjectMemorySnapshot("project-flush-failure", Map.of(), List.of(), List.of()),
                new ChapterMemorySnapshot("chapter-1", Map.of(), List.of(), List.of()),
                TranslationRuntimeOptions.defaults()
        ));

        assertSame(businessException, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0].getMessage().contains("Failed to write workflow trace artifacts"));
        assertTrue(thrown.getSuppressed()[0].getCause().getMessage().contains("trace flush failed"));
    }

    private static final class FinalMemoryOnlyTranslationService extends TranslationApplicationService {

        private FinalMemoryOnlyTranslationService() {
            super(new TranslationTaskInputAssembler(), input -> {
                throw new UnsupportedOperationException("test service should not delegate chunk translation");
            });
        }

        @Override
        public List<ChunkTranslationDraft> translateChunks(io.quillloom.domain.preprocess.PreprocessDossier dossier,
                                                           ProjectMemorySnapshot projectMemory,
                                                           ChapterMemorySnapshot chapterMemory,
                                                           TranslationRuntimeOptions runtimeOptions) {
            return List.of(draftWithoutTerm(dossier.chunkAnnotations().chunks().get(0).chunk().chunkId()));
        }

        @Override
        public TranslationDraftRunResult translateChunksWithMemory(io.quillloom.domain.preprocess.PreprocessDossier dossier,
                                                                   ProjectMemorySnapshot projectMemory,
                                                                   ChapterMemorySnapshot chapterMemory,
                                                                   TranslationRuntimeOptions runtimeOptions) {
            return new TranslationDraftRunResult(
                    List.of(draftWithoutTerm(dossier.chunkAnnotations().chunks().get(0).chunk().chunkId())),
                    new ProjectMemorySnapshot(dossier.project().projectId(), Map.of("A", "甲"), List.of(), List.of())
            );
        }

        private static ChunkTranslationDraft draftWithoutTerm(String chunkId) {
            return new ChunkTranslationDraft(
                    chunkId,
                    "甲在门口等。",
                    "final memory carries confirmed term",
                    List.of(),
                    Map.of(),
                    List.of(),
                    new ChunkTransitionNote("", "", false)
            );
        }
    }

    private static final class FailingPreprocessApplicationService extends PreprocessApplicationService {

        private final RuntimeException exception;

        private FailingPreprocessApplicationService(RuntimeException exception) {
            super(null, null, null, null);
            this.exception = exception;
        }

        @Override
        public io.quillloom.domain.preprocess.PreprocessDossier preprocess(PreprocessBookCommand command) {
            throw exception;
        }
    }

    private static final class FailingTraceArtifactWriter extends WorkflowTraceArtifactWriter {

        private FailingTraceArtifactWriter() {
            super(new ObjectMapper(), Path.of("target", "unused-trace-writer"));
        }

        @Override
        public Path write(io.quillloom.application.workflow.trace.WorkflowTraceSession session) throws IOException {
            throw new IOException("trace flush failed");
        }
    }
}
