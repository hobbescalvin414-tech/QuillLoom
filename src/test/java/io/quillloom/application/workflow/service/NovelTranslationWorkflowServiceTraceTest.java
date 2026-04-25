package io.quillloom.application.workflow.service;

import io.quillloom.application.preprocess.assembler.PreprocessDossierAssembler;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.service.PreprocessApplicationService;
import io.quillloom.application.translation.assembler.DraftCompilationAssembler;
import io.quillloom.application.translation.assembler.TranslationTaskInputAssembler;
import io.quillloom.application.translation.service.TranslationApplicationService;
import io.quillloom.domain.memory.ChapterMemorySnapshot;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.TranslationRuntimeOptions;
import io.quillloom.support.BookAnalysisTestSupport;
import io.quillloom.support.PreprocessTestSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NovelTranslationWorkflowServiceTraceTest {

    @Test
    void shouldWriteTraceBundleWhenRunningDraftWorkflow() throws Exception {
        String projectId = "project-trace-workflow-" + System.currentTimeMillis();
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
                        "workflow trace run",
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

        workflowService.runDraftWorkflow(
                new PreprocessBookCommand(projectId, "sample", String.join("\n\n", "Alice met Bob in Paris. ".repeat(10), "They walked along the river. ".repeat(10)), "en", "zh"),
                new ProjectMemorySnapshot(projectId, Map.of(), List.of(), List.of()),
                new ChapterMemorySnapshot(projectId + "-chapter-1", Map.of(), List.of(), List.of()),
                TranslationRuntimeOptions.defaults()
        );

        Path traceRoot = Path.of("run-output", "workflow-trace");
        try (var stream = Files.list(traceRoot)) {
            Path runDir = stream.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().endsWith("-" + projectId))
                    .findFirst()
                    .orElseThrow();
            assertTrue(Files.exists(runDir.resolve("00-manifest.json")));
            assertTrue(Files.exists(runDir.resolve("00-run-overview.txt")));
            assertTrue(Files.exists(runDir.resolve("01-events.ndjson")));
            assertTrue(Files.exists(runDir.resolve("40-c0-readable.txt")));
            assertTrue(Files.exists(runDir.resolve("50-translation-input-readable.txt")));
        }

        Path draftRoot = Path.of("run-output", "book-sample");
        try (var stream = Files.list(draftRoot)) {
            Path runDir = stream.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().endsWith("-" + projectId))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow();
            assertTrue(Files.exists(runDir.resolve("draft.txt")));
            assertTrue(Files.exists(runDir.resolve("merged-draft.txt")));
            assertTrue(Files.exists(runDir.resolve("00-draft-overview.txt")));
        }
    }
}
