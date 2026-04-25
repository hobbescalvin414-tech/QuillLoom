package io.quillloom;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.workflow.service.NovelTranslationWorkflowService;
import io.quillloom.domain.memory.ChapterMemorySnapshot;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.translation.TranslationRuntimeOptions;
import io.quillloom.domain.workflow.NovelTranslationWorkflowState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class BookWorkflowSampleSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(BookWorkflowSampleSmokeTest.class);
    private static final Path BOOK_PATH = Path.of("book", "1.txt");
    private static final Path TRACE_ROOT = Path.of("run-output", "workflow-trace");
    private static final Path DRAFT_ROOT = Path.of("run-output", "book-sample");
    private static final List<String> REQUIRED_TRACE_FILES = List.of(
            "00-manifest.json",
            "00-run-overview.txt",
            "01-events.ndjson",
            "10-coarse-blocks.json",
            "10-coarse-blocks.txt",
            "20-chunk-segmentation.json",
            "20-chunk-segmentation.txt",
            "30-chunk-annotations.json",
            "30-chunk-annotations.txt",
            "40-c0-knowledge.json",
            "40-c0-knowledge.txt",
            "40-c0-readable.txt",
            "50-translation-inputs.json",
            "50-translation-inputs.txt",
            "50-translation-input-readable.txt",
            "60-chunk-drafts.json",
            "60-chunk-drafts.txt",
            "60-draft-readable.txt"
    );
    private static final List<String> REQUIRED_DRAFT_FILES = List.of(
            "00-draft-overview.txt",
            "draft.txt",
            "merged-draft.txt",
            "chunk-drafts.txt",
            "run-summary.txt",
            "source-sample.txt"
    );

    @Autowired
    private NovelTranslationWorkflowService workflowService;

    @Test
    void shouldRunFullWorkflowAgainstBook1AndWriteTraceArtifacts() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("quillloom.test.book-workflow-sample.enabled"),
                "Skip sample workflow smoke test unless explicitly enabled.");

        assertTrue(Files.isRegularFile(BOOK_PATH), "book/1.txt must exist.");
        String sourceText = Files.readString(BOOK_PATH, StandardCharsets.UTF_8).trim();
        assertFalse(sourceText.isBlank(), "book/1.txt must not be blank.");

        String projectId = "book-1-full-workflow-" + System.currentTimeMillis();
        PreprocessBookCommand command = new PreprocessBookCommand(
                projectId,
                "book-1-full-workflow",
                sourceText,
                "fr",
                "zh"
        );

        log.info("[book-1] start source={} chars={} projectId={}", BOOK_PATH.toAbsolutePath(), sourceText.length(), projectId);

        NovelTranslationWorkflowState state = workflowService.runDraftWorkflow(
                command,
                new ProjectMemorySnapshot(projectId, Map.of(), List.of(), List.of()),
                new ChapterMemorySnapshot(projectId + "-chapter-1", Map.of(), List.of(), List.of()),
                TranslationRuntimeOptions.defaults()
        );

        assertFalse(state.chunkDrafts().isEmpty(), "Chunk drafts should not be empty.");
        assertFalse(state.draftCompilation().mergedDraft().isBlank(), "Merged draft should not be blank.");
        assertEquals(state.chunkDrafts().size(), state.draftCompilation().chunkDrafts().size());

        Path runDir = findLatestTraceDirForProject(projectId);
        for (String fileName : REQUIRED_TRACE_FILES) {
            assertTrue(Files.exists(runDir.resolve(fileName)), "Missing trace artifact: " + fileName);
        }

        Path draftDir = findLatestDraftDirForProject(projectId);
        for (String fileName : REQUIRED_DRAFT_FILES) {
            assertTrue(Files.exists(draftDir.resolve(fileName)), "Missing draft artifact: " + fileName);
        }
        String mergedDraftText = Files.readString(draftDir.resolve("draft.txt"), StandardCharsets.UTF_8);
        assertFalse(mergedDraftText.isBlank(), "Merged draft file should not be blank.");

        String eventsText = Files.readString(runDir.resolve("01-events.ndjson"), StandardCharsets.UTF_8);
        assertTrue(eventsText.contains("chunk_annotation_completed"), "Trace should contain chunk annotation events.");
        assertTrue(eventsText.contains("knowledge_card_created") || eventsText.contains("knowledge_queries_planned"), "Trace should contain C0 events.");
        assertTrue(eventsText.contains("translation_input_assembled"), "Trace should contain translation input assembly events.");
        assertTrue(eventsText.contains("chunk_translation_completed"), "Trace should contain chunk translation events.");

        log.info("[book-1] done chunkCount={} mergedChars={} traceDir={}",
                state.chunkDrafts().size(),
                state.draftCompilation().mergedDraft().length(),
                runDir.toAbsolutePath());
    }

    private Path findLatestTraceDirForProject(String projectId) throws IOException {
        assertTrue(Files.isDirectory(TRACE_ROOT), "Trace root must exist after workflow run.");
        try (var stream = Files.list(TRACE_ROOT)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().endsWith("-" + projectId))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new IllegalStateException("No trace directory found for projectId=" + projectId));
        }
    }

    private Path findLatestDraftDirForProject(String projectId) throws IOException {
        assertTrue(Files.isDirectory(DRAFT_ROOT), "Draft output root must exist after workflow run.");
        try (var stream = Files.list(DRAFT_ROOT)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().endsWith("-" + projectId))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new IllegalStateException("No draft output directory found for projectId=" + projectId));
        }
    }
}
