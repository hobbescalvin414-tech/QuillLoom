package io.quillloom.infrastructure.postdraft.review;

import io.quillloom.application.postdraft.port.out.PostDraftReviewPackageRepository;
import io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProcessSummary;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;
import io.quillloom.domain.postdraft.PostDraftBlockIndex;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import io.quillloom.domain.postdraft.PostDraftTermState;
import io.quillloom.infrastructure.postdraft.InMemoryPostDraftReviewPackageRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresPostDraftReviewAgentWriterTest {

    @Test
    void shouldWriteCompletedChunkTranslationsBackToReviewPackage() throws Exception {
        PostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
        repository.save(samplePackage("project-1", "old-1", "old-2"));

        Class<?> writerClass = Class.forName("io.quillloom.infrastructure.postdraft.review.PostgresPostDraftReviewAgentWriter");
        Constructor<?> constructor = writerClass.getConstructor(PostDraftReviewPackageRepository.class);
        Object writer = constructor.newInstance(repository);
        Method writeCompletedChunks = writerClass.getMethod("writeCompletedChunks", String.class, List.class);

        writeCompletedChunks.invoke(writer, "project-1", List.of(
                outcome("project-1", "chunk-1", "new-1"),
                outcome("project-1", "chunk-2", "new-2")
        ));

        PostDraftReviewPackage updated = repository.load("project-1").orElseThrow();
        assertEquals("old-1", chunkById(updated, "chunk-1").translatedText());
        assertEquals("old-2", chunkById(updated, "chunk-2").translatedText());
        assertEquals("new-1", chunkById(updated, "chunk-1").revisedTranslatedText());
        assertEquals("new-2", chunkById(updated, "chunk-2").revisedTranslatedText());
    }

    @Test
    void shouldWriteMergedDraftTextBackToReviewPackage() throws Exception {
        PostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
        repository.save(samplePackage("project-1", "old-1", "old-2"));

        Class<?> writerClass = Class.forName("io.quillloom.infrastructure.postdraft.review.PostgresPostDraftReviewAgentWriter");
        Constructor<?> constructor = writerClass.getConstructor(PostDraftReviewPackageRepository.class);
        Object writer = constructor.newInstance(repository);
        Method writeMergedDraftText = writerClass.getMethod("writeMergedDraftText", String.class, String.class);

        writeMergedDraftText.invoke(writer, "project-1", "merged-new");

        PostDraftReviewPackage updated = repository.load("project-1").orElseThrow();
        assertEquals("merged-new", updated.mergedDraftText());
    }

    @Test
    void shouldAssembleMergedDraftFromRevisedAndDraftTextsInSequenceOrder() throws Exception {
        PostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
        repository.save(unorderedPackage("project-1"));

        Class<?> writerClass = Class.forName("io.quillloom.infrastructure.postdraft.review.PostgresPostDraftReviewAgentWriter");
        Constructor<?> constructor = writerClass.getConstructor(PostDraftReviewPackageRepository.class);
        Object writer = constructor.newInstance(repository);
        Method writeMergedDraftFromProjectChunks = writerClass.getMethod("writeMergedDraftFromProjectChunks", String.class);

        writeMergedDraftFromProjectChunks.invoke(writer, "project-1");

        PostDraftReviewPackage updated = repository.load("project-1").orElseThrow();
        assertEquals("draft-1\n\nrevised-2\n\ndraft-3", updated.mergedDraftText());
    }

    @Test
    void shouldResetProjectRevisionsWithoutTouchingDraftTexts() throws Exception {
        PostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
        repository.save(packageWithRevisions("project-1"));

        Class<?> writerClass = Class.forName("io.quillloom.infrastructure.postdraft.review.PostgresPostDraftReviewAgentWriter");
        Constructor<?> constructor = writerClass.getConstructor(PostDraftReviewPackageRepository.class);
        Object writer = constructor.newInstance(repository);
        Method resetProjectRevisions = writerClass.getMethod("resetProjectRevisions", String.class);

        resetProjectRevisions.invoke(writer, "project-1");

        PostDraftReviewPackage updated = repository.load("project-1").orElseThrow();
        assertEquals("draft-1", chunkById(updated, "chunk-1").translatedText());
        assertEquals("draft-2", chunkById(updated, "chunk-2").translatedText());
        assertEquals(null, chunkById(updated, "chunk-1").revisedTranslatedText());
        assertEquals(null, chunkById(updated, "chunk-2").revisedTranslatedText());
        assertEquals("", updated.mergedDraftText());
    }

    private ProjectChunkReviewOutcome outcome(String projectId, String chunkId, String translation) {
        return new ProjectChunkReviewOutcome(
                chunkId,
                translation,
                ReviewStrategy.DEEP_EDIT,
                new ReviewProcessSummary(
                        projectId,
                        ReviewFocus.forChunk(chunkId),
                        ReviewStrategy.DEEP_EDIT,
                        Set.of(),
                        List.of("evidence"),
                        "process"
                )
        );
    }

    private PostDraftReviewPackage samplePackage(String projectId, String chunk1Translation, String chunk2Translation) {
        return new PostDraftReviewPackage(
                projectId,
                "v1",
                "en",
                "zh",
                "digest-1",
                Instant.parse("2026-04-18T00:00:00Z"),
                List.of(
                        chunk("chunk-1", 1, chunk1Translation, null),
                        chunk("chunk-2", 2, chunk2Translation, null)
                ),
                List.of(new PostDraftBlockIndex("block-1", "summary", List.of("chunk-1", "chunk-2"))),
                new PostDraftTermState(Map.of(), List.of()),
                DraftStageGlobalGlossary.empty(),
                GlobalAliasConsistencyTable.empty(),
                "merged-old"
        );
    }

    private PostDraftReviewPackage unorderedPackage(String projectId) {
        return new PostDraftReviewPackage(
                projectId,
                "v1",
                "en",
                "zh",
                "digest-1",
                Instant.parse("2026-04-18T00:00:00Z"),
                List.of(
                        chunk("chunk-3", 3, "draft-3", null),
                        chunk("chunk-1", 1, "draft-1", null),
                        chunk("chunk-2", 2, "draft-2", "revised-2")
                ),
                List.of(new PostDraftBlockIndex("block-1", "summary", List.of("chunk-1", "chunk-2", "chunk-3"))),
                new PostDraftTermState(Map.of(), List.of()),
                DraftStageGlobalGlossary.empty(),
                GlobalAliasConsistencyTable.empty(),
                ""
        );
    }

    private PostDraftReviewPackage packageWithRevisions(String projectId) {
        return new PostDraftReviewPackage(
                projectId,
                "v1",
                "en",
                "zh",
                "digest-1",
                Instant.parse("2026-04-18T00:00:00Z"),
                List.of(
                        chunk("chunk-1", 1, "draft-1", "revised-1"),
                        chunk("chunk-2", 2, "draft-2", "revised-2")
                ),
                List.of(new PostDraftBlockIndex("block-1", "summary", List.of("chunk-1", "chunk-2"))),
                new PostDraftTermState(Map.of("Louki", "露姬"), List.of()),
                DraftStageGlobalGlossary.empty(),
                GlobalAliasConsistencyTable.empty(),
                "merged-old"
        );
    }

    private PostDraftChunkRecord chunk(String chunkId, int sequence, String translatedText, String revisedTranslatedText) {
        return new PostDraftChunkRecord(
                chunkId,
                sequence,
                "block-1",
                "source",
                translatedText,
                revisedTranslatedText,
                "commentary",
                List.of(),
                Map.of(),
                List.of(),
                null
        );
    }

    private PostDraftChunkRecord chunkById(PostDraftReviewPackage reviewPackage, String chunkId) {
        return reviewPackage.chunks().stream()
                .filter(chunk -> chunk.chunkId().equals(chunkId))
                .findFirst()
                .orElseThrow();
    }
}
