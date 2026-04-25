package io.quillloom.application.postdraft;

import io.quillloom.application.postdraft.assembler.PostDraftReviewPackageAssembler;
import io.quillloom.application.postdraft.assembler.PostDraftContinuationContextAssembler;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.postdraft.PostDraftBlockIndex;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftContinuationContext;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import io.quillloom.domain.postdraft.PostDraftTermState;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostDraftContinuationAssemblyTest {

    @Test
    void shouldAssembleContinuationContextWithPackageAndKnowledgeBase() {
        PostDraftReviewPackage reviewPackage = new PostDraftReviewPackage(
                "project-1",
                "v1",
                "fr",
                "zh",
                "digest-1",
                Instant.parse("2026-04-14T10:15:30Z"),
                List.of(new PostDraftChunkRecord(
                        "chunk-1",
                        1,
                        "block-1",
                        "source text",
                        "translated text",
                        "commentary",
                        List.of(),
                        Map.of("Louki", "露姬"),
                        List.of(),
                        new ChunkTransitionNote("", "", false)
                )),
                List.of(new PostDraftBlockIndex("block-1", "夜行", List.of("chunk-1"))),
                new PostDraftTermState(Map.of("Louki", "露姬"), List.of()),
                DraftStageGlobalGlossary.empty(),
                GlobalAliasConsistencyTable.empty(),
                "merged text"
        );
        ProjectKnowledgeBase knowledgeBase = ProjectKnowledgeBase.empty("project-1");

        PostDraftContinuationContext context = new PostDraftContinuationContextAssembler()
                .assemble(reviewPackage, knowledgeBase);

        assertEquals("project-1", context.projectId());
        assertEquals("chunk-1", context.chunks().get(0).chunkId());
        assertSame(knowledgeBase, context.knowledgeBase());
    }

    @Test
    void shouldFailWhenPostDraftTermAggregationFindsConfirmedTermConflict() {
        PostDraftReviewPackageAssembler assembler = new PostDraftReviewPackageAssembler();

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> assembler.buildTermState(
                new ProjectMemorySnapshot("project-1", Map.of(), List.of(), List.of()),
                List.of(
                        draft("chunk-1", Map.of("Le Condé", "孔代咖啡馆")),
                        draft("chunk-2", Map.of("le condé", "勒孔代咖啡馆"))
                )
        ));

        assertTrue(exception.getMessage().contains("confirmed_term_conflict"));
        assertTrue(exception.getMessage().contains("sourceTerm=le condé"));
        assertTrue(exception.getMessage().contains("existing=孔代咖啡馆"));
        assertTrue(exception.getMessage().contains("incoming=勒孔代咖啡馆"));
        assertTrue(exception.getMessage().contains("chunkId=chunk-2"));
    }

    @Test
    void shouldAllowDuplicateConfirmedTermWithSameTargetDuringPostDraftAggregation() {
        PostDraftTermState termState = new PostDraftReviewPackageAssembler().buildTermState(
                new ProjectMemorySnapshot("project-1", Map.of(), List.of(), List.of()),
                List.of(
                        draft("chunk-1", Map.of("Le Condé", "孔代咖啡馆")),
                        draft("chunk-2", Map.of("le condé", "孔代咖啡馆"))
                )
        );

        assertEquals("孔代咖啡馆", termState.effectiveConfirmedTerms().get("Le Condé"));
        assertEquals(1, termState.effectiveConfirmedTerms().size());
    }

    @Test
    void shouldDeduplicateCandidateTermsByLocaleStablePairKeyDuringPostDraftAggregation() {
        PostDraftTermState termState = new PostDraftReviewPackageAssembler().buildTermState(
                new ProjectMemorySnapshot("project-1", Map.of(), List.of(), List.of()),
                List.of(
                        draft("chunk-1", Map.of(), List.of(new TranslationCandidateUpdate("Le Condé", "孔代咖啡馆", "first", true))),
                        draft("chunk-2", Map.of(), List.of(new TranslationCandidateUpdate("le condé", "孔代咖啡馆", "duplicate", true)))
                )
        );

        assertEquals(1, termState.effectiveCandidateTerms().size());
        assertEquals("Le Condé", termState.effectiveCandidateTerms().get(0).sourceTerm());
    }

    private static ChunkTranslationDraft draft(String chunkId,
                                               Map<String, String> confirmedTermUpdates) {
        return draft(chunkId, confirmedTermUpdates, List.of());
    }

    private static ChunkTranslationDraft draft(String chunkId,
                                               Map<String, String> confirmedTermUpdates,
                                               List<TranslationCandidateUpdate> candidateUpdates) {
        return new ChunkTranslationDraft(
                chunkId,
                "translated text",
                "commentary",
                List.of(),
                confirmedTermUpdates,
                candidateUpdates,
                new ChunkTransitionNote("", "", false)
        );
    }
}
