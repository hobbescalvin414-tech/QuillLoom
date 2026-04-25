package io.quillloom;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.workflow.service.NovelTranslationWorkflowService;
import io.quillloom.domain.memory.ChapterMemorySnapshot;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.translation.TranslationRuntimeOptions;
import io.quillloom.domain.workflow.TranslationWorkflowStage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class BookWorkflowSmokeTest {

    private static final String BOOK_FILE_PROPERTY = "quillloom.test.book-workflow.file";
    private static final String PROJECT_ID_PROPERTY = "quillloom.test.book-workflow.project-id";

    @Autowired
    private NovelTranslationWorkflowService workflowService;

    @Test
    void shouldUseConfiguredProjectIdWhenBookWorkflowPropertyIsSet() {
        String previous = System.getProperty(PROJECT_ID_PROPERTY);
        try {
            System.setProperty(PROJECT_ID_PROPERTY, "book-smoke-fixed");

            assertEquals("book-smoke-fixed", resolveProjectId());
        } finally {
            if (previous == null) {
                System.clearProperty(PROJECT_ID_PROPERTY);
            } else {
                System.setProperty(PROJECT_ID_PROPERTY, previous);
            }
        }
    }

    @Test
    void shouldReadBookTextAndRunDraftWorkflow() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("quillloom.test.book-workflow.enabled"),
                "Skip real workflow smoke test unless explicitly enabled.");

        Path bookPath = resolveBookPath(Path.of("book"));
        String sourceText = Files.readString(bookPath, StandardCharsets.UTF_8).trim();

        assertFalse(sourceText.isBlank(), "Book source text must not be blank.");

        String projectId = resolveProjectId();
        PreprocessBookCommand command = new PreprocessBookCommand(
                projectId,
                bookPath.getFileName().toString(),
                sourceText,
                "en",
                "zh"
        );

        var state = workflowService.runDraftWorkflow(
                command,
                new ProjectMemorySnapshot(projectId, Map.of(), List.of(), List.of()),
                new ChapterMemorySnapshot(projectId + "-chapter-1", Map.of(), List.of(), List.of()),
                TranslationRuntimeOptions.defaults()
        );

        assertEquals(TranslationWorkflowStage.COMPILED, state.stage());
        assertNotNull(state.preprocessDossier());
        assertNotNull(state.draftCompilation());
        assertFalse(state.chunkDrafts().isEmpty(), "Chunk drafts should not be empty.");
        assertFalse(state.draftCompilation().mergedDraft().isBlank(), "Merged draft should not be blank.");

        System.out.println("[BookWorkflowSmokeTest] source=" + bookPath.toAbsolutePath());
        System.out.println("[BookWorkflowSmokeTest] chunkCount=" + state.chunkDrafts().size());
        System.out.println("[BookWorkflowSmokeTest] mergedDraftPreview=" + preview(state.draftCompilation().mergedDraft()));
    }

    private Path resolveBookPath(Path bookDir) throws IOException {
        assertTrue(Files.isDirectory(bookDir), "book directory must exist.");
        String configuredFile = System.getProperty(BOOK_FILE_PROPERTY, "").trim();
        if (!configuredFile.isEmpty()) {
            Path selectedBookPath = bookDir.resolve(configuredFile).normalize();
            assertTrue(selectedBookPath.startsWith(bookDir.normalize()),
                    "Book file must stay under book directory.");
            assertTrue(Files.isRegularFile(selectedBookPath),
                    "Configured book file must exist: " + selectedBookPath);
            assertTrue(selectedBookPath.getFileName().toString().toLowerCase().endsWith(".txt"),
                    "Configured book file must be a txt file.");
            System.out.println("[BookWorkflowSmokeTest] selectedByProperty="
                    + BOOK_FILE_PROPERTY + "=" + configuredFile);
            return selectedBookPath;
        }

        try (var stream = Files.list(bookDir)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".txt"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No txt file found under book directory."));
        }
    }

    private String resolveProjectId() {
        String configuredProjectId = System.getProperty(PROJECT_ID_PROPERTY, "").trim();
        if (!configuredProjectId.isEmpty()) {
            System.out.println("[BookWorkflowSmokeTest] selectedByProperty="
                    + PROJECT_ID_PROPERTY + "=" + configuredProjectId);
            return configuredProjectId;
        }
        return "book-smoke-" + System.currentTimeMillis();
    }

    private String preview(String text) {
        String normalized = text.replace(System.lineSeparator(), "\\n").trim();
        if (normalized.length() <= 200) {
            return normalized;
        }
        return normalized.substring(0, 200) + "...";
    }
}
