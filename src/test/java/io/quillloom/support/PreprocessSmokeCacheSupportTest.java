package io.quillloom.support;

import io.quillloom.domain.book.BookProject;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.ChunkAnnotationBundle;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import io.quillloom.domain.preprocess.KnowledgeEnrichmentBundle;
import io.quillloom.domain.preprocess.PreprocessDossier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreprocessSmokeCacheSupportTest {

    private static final Path TEMP_DIR = Path.of("target", "test-cache", "preprocess-smoke-cache-test");

    @AfterEach
    void cleanup() throws Exception {
        if (!Files.exists(TEMP_DIR)) {
            return;
        }
        try (var walk = Files.walk(TEMP_DIR)) {
            walk.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ex) {
                            throw new IllegalStateException("Failed to clean test cache path: " + path, ex);
                        }
                    });
        }
    }

    @Test
    void shouldComputeThenReuseCompletedPreprocessCache() {
        AtomicInteger computeCount = new AtomicInteger();
        PreprocessSmokeCacheSupport support = new PreprocessSmokeCacheSupport(TEMP_DIR, "v1");

        PreprocessDossier first = support.loadOrCompute("hash-1", "fr", "zh", () -> {
            computeCount.incrementAndGet();
            return createDossier("project-1");
        }).dossier();

        PreprocessSmokeCacheSupport.CachedPreprocess second = support.loadOrCompute("hash-1", "fr", "zh", () -> {
            computeCount.incrementAndGet();
            return createDossier("project-2");
        });

        assertEquals(1, computeCount.get());
        assertEquals("project-1", first.project().projectId());
        assertTrue(second.cacheHit());
        assertEquals("project-1", second.dossier().project().projectId());
    }

    @Test
    void shouldRecomputeWhenCachedFileIsBroken() throws Exception {
        AtomicInteger computeCount = new AtomicInteger();
        PreprocessSmokeCacheSupport support = new PreprocessSmokeCacheSupport(TEMP_DIR, "v1");

        Path cacheFile = support.resolveCacheFile("hash-2", "fr", "zh");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "{broken-json");

        PreprocessSmokeCacheSupport.CachedPreprocess cached = support.loadOrCompute("hash-2", "fr", "zh", () -> {
            computeCount.incrementAndGet();
            return createDossier("project-3");
        });

        assertEquals(1, computeCount.get());
        assertFalse(cached.cacheHit());
        assertEquals("project-3", cached.dossier().project().projectId());
    }

    private PreprocessDossier createDossier(String projectId) {
        return new PreprocessDossier(
                new BookProject(projectId, "sample", "fr", "zh"),
                new GlobalAnalysisBundle(
                        new BookAnalysis("synopsis", "outline", "style", List.of(), List.of()),
                        List.of()
                ),
                new ChunkAnnotationBundle(List.of()),
                new KnowledgeEnrichmentBundle(ProjectKnowledgeBase.empty(projectId))
        );
    }
}
