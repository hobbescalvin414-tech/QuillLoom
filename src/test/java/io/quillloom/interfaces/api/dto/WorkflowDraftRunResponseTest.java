package io.quillloom.interfaces.api.dto;

import io.quillloom.domain.book.BookProject;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkAnnotationBundle;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import io.quillloom.domain.preprocess.KnowledgeEnrichmentBundle;
import io.quillloom.domain.preprocess.PreprocessDossier;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.DraftCompilation;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.workflow.NovelTranslationWorkflowState;
import io.quillloom.domain.workflow.TranslationWorkflowStage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowDraftRunResponseTest {

    @Test
    void shouldDeduplicateActiveGlossaryBySourceAndTargetKey() {
        WorkflowDraftRunResponse response = WorkflowDraftRunResponse.from(stateWithDrafts(
                draft("chunk-1", Map.of("Le Condé", "孔代咖啡馆")),
                draft("chunk-2", Map.of("le condé", "孔代咖啡馆"))
        ));

        assertEquals(1, response.activeGlossary().size());
        assertEquals("孔代咖啡馆", response.activeGlossary().get("Le Condé"));
    }

    @Test
    void shouldFailActiveGlossaryAggregationOnSameSourceKeyDifferentTargetKey() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> WorkflowDraftRunResponse.from(stateWithDrafts(
                draft("chunk-1", Map.of("Le Condé", "孔代咖啡馆")),
                draft("chunk-2", Map.of("le condé", "勒孔代咖啡馆"))
        )));

        assertTrue(exception.getMessage().contains("confirmed_term_conflict"));
        assertTrue(exception.getMessage().contains("sourceTerm=le condé"));
        assertTrue(exception.getMessage().contains("existing=孔代咖啡馆"));
        assertTrue(exception.getMessage().contains("incoming=勒孔代咖啡馆"));
        assertTrue(exception.getMessage().contains("chunkId=chunk-2"));
    }

    @Test
    void shouldDeduplicateCandidateGlossaryBySourceAndTargetKey() {
        WorkflowDraftRunResponse response = WorkflowDraftRunResponse.from(stateWithDrafts(
                draft("chunk-1", Map.of(), List.of(new TranslationCandidateUpdate(
                        "Le Conde",
                        "Cafe",
                        "first",
                        false
                ))),
                draft("chunk-2", Map.of(), List.of(new TranslationCandidateUpdate(
                        "le conde",
                        "cafe",
                        "second",
                        true
                )))
        ));

        assertEquals(1, response.candidateGlossary().size());
        WorkflowDraftRunResponse.CandidateGlossaryItem item = response.candidateGlossary().get(0);
        assertEquals("Le Conde", item.sourceTerm());
        assertEquals(List.of("Cafe"), item.candidateTranslations());
        assertTrue(item.requiresReview());
    }

    private static NovelTranslationWorkflowState stateWithDrafts(ChunkTranslationDraft firstDraft,
                                                                 ChunkTranslationDraft secondDraft) {
        ChunkAnnotation firstChunk = chunk("chunk-1", 1);
        ChunkAnnotation secondChunk = chunk("chunk-2", 2);
        PreprocessDossier dossier = new PreprocessDossier(
                new BookProject("project-1", "sample", "fr", "zh"),
                new GlobalAnalysisBundle(new BookAnalysis("synopsis", "", "", List.of(), List.of()), List.of()),
                new ChunkAnnotationBundle(List.of(firstChunk, secondChunk)),
                new KnowledgeEnrichmentBundle(ProjectKnowledgeBase.empty("project-1"))
        );
        List<ChunkTranslationDraft> drafts = List.of(firstDraft, secondDraft);
        return new NovelTranslationWorkflowState(
                "project-1",
                TranslationWorkflowStage.COMPILED,
                dossier,
                drafts,
                new DraftCompilation("project-1", drafts, "merged", List.of())
        );
    }

    private static ChunkAnnotation chunk(String chunkId, int sequence) {
        return new ChunkAnnotation(
                new ChunkDescriptor(chunkId, sequence, "block-1", 0, 10, "source " + chunkId),
                "summary",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
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
                "translated",
                "commentary",
                List.of(),
                confirmedTermUpdates,
                candidateUpdates,
                new ChunkTransitionNote("", "", false)
        );
    }
}
