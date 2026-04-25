package io.quillloom;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.workflow.service.NovelTranslationWorkflowService;
import io.quillloom.domain.memory.ChapterMemorySnapshot;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.preprocess.PreprocessDossier;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.DraftCompilation;
import io.quillloom.domain.translation.TranslationRuntimeOptions;
import io.quillloom.support.PreprocessSmokeCacheSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
@ActiveProfiles("dev")
class BookWorkflowFromC0SmokeTest {

    private static final Path BOOK_PATH = Path.of("book", "1.txt");
    private static final Path CACHE_ROOT = Path.of("target", "test-cache", "book-workflow-sample");

    @Autowired
    private NovelTranslationWorkflowService workflowService;

    @Test
    void shouldReuseCompletedPreprocessCacheAndContinueFromC0() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("quillloom.test.book-workflow-sample.enabled"),
                "Skip sample workflow smoke test unless explicitly enabled.");

        String sourceText = Files.readString(BOOK_PATH, StandardCharsets.UTF_8).trim();
        String sourceHash = sha256(sourceText);
        String projectId = "book-1-from-c0-" + System.currentTimeMillis();
        PreprocessBookCommand command = new PreprocessBookCommand(
                projectId,
                "book-1-from-c0",
                sourceText,
                "fr",
                "zh"
        );

        PreprocessSmokeCacheSupport cacheSupport = new PreprocessSmokeCacheSupport(CACHE_ROOT, "v1");
        PreprocessDossier dossier = cacheSupport.loadOrCompute(sourceHash, command.sourceLanguage(), command.targetLanguage(),
                () -> workflowService.runPreprocess(command)).dossier();

        List<ChunkTranslationDraft> drafts = workflowService.translateChunks(
                dossier,
                new ProjectMemorySnapshot(projectId, Map.of(), List.of(), List.of()),
                new ChapterMemorySnapshot(projectId + "-chapter-1", Map.of(), List.of(), List.of()),
                TranslationRuntimeOptions.defaults()
        );
        DraftCompilation compilation = workflowService.compileDrafts(projectId, drafts);

        assertFalse(drafts.isEmpty(), "Chunk drafts should not be empty.");
        assertFalse(compilation.mergedDraft().isBlank(), "Merged draft should not be blank.");
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Missing SHA-256 support.", ex);
        }
    }
}
