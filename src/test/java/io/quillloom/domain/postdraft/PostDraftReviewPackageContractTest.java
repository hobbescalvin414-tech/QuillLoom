package io.quillloom.domain.postdraft;

import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.translation.TranslationDecisionNote;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PostDraftReviewPackageContractTest {

    @Test
    void shouldPreserveChunkNavigationAndTermState() {
        PostDraftChunkRecord chunk = new PostDraftChunkRecord(
                "chunk-2",
                2,
                "block-1",
                "source text",
                "translated text",
                "revised text",
                "commentary",
                List.of(new TranslationDecisionNote("risk", "focus", "issue", "action")),
                Map.of("Louki", "露姬"),
                List.of(new TranslationCandidateUpdate("Black Maria", "黑色马车", "候选", true)),
                new ChunkTransitionNote("before", "after", false)
        );
        PostDraftTermState termState = new PostDraftTermState(
                Map.of("Louki", "露姬"),
                List.of(new TranslationCandidateUpdate("Black Maria", "黑色马车", "候选", true))
        );

        PostDraftReviewPackage reviewPackage = new PostDraftReviewPackage(
                "project-1",
                "v1",
                "fr",
                "zh",
                "digest-1",
                Instant.parse("2026-04-14T10:15:30Z"),
                List.of(chunk),
                List.of(new PostDraftBlockIndex("block-1", "街道夜行", List.of("chunk-2"))),
                termState,
                DraftStageGlobalGlossary.empty(),
                GlobalAliasConsistencyTable.empty(),
                "merged text"
        );

        assertEquals("project-1", reviewPackage.projectId());
        assertEquals("chunk-2", reviewPackage.chunks().get(0).chunkId());
        assertEquals(2, reviewPackage.chunks().get(0).sequence());
        assertEquals("block-1", reviewPackage.chunks().get(0).blockId());
        assertEquals("source text", reviewPackage.chunks().get(0).sourceText());
        assertEquals("translated text", reviewPackage.chunks().get(0).translatedText());
        assertEquals("revised text", reviewPackage.chunks().get(0).revisedTranslatedText());
        assertEquals("revised text", reviewPackage.chunks().get(0).effectiveTranslatedText());
        assertSame(termState, reviewPackage.termState());
    }

    @Test
    void shouldFallbackToDraftWhenRevisedTranslationIsBlank() {
        PostDraftChunkRecord chunk = new PostDraftChunkRecord(
                "chunk-3",
                3,
                "block-2",
                "source",
                "draft text",
                "   ",
                "commentary",
                List.of(),
                Map.of(),
                List.of(),
                null
        );

        assertEquals("draft text", chunk.effectiveTranslatedText());
    }
}
