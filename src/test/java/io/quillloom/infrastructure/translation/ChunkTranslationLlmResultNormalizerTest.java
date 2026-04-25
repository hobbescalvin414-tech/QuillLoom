package io.quillloom.infrastructure.translation;

import io.quillloom.application.preprocess.assembler.PreprocessDossierAssembler;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.translation.assembler.TranslationTaskInputAssembler;
import io.quillloom.support.BookAnalysisTestSupport;
import io.quillloom.support.PreprocessTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkTranslationLlmResultNormalizerTest {

    @Test
    void shouldFallbackAndDeduplicateStructuredFields() {
        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-1",
                "sample",
                "Alice met Bob in Paris.",
                "en",
                "zh"
        );

        var globalAnalysis = BookAnalysisTestSupport.createBookAnalyzer().analyze(command);
        var chunkBundle = PreprocessTestSupport.createChunkAnnotator().annotate(command, globalAnalysis);
        var knowledgeBundle = PreprocessTestSupport.createKnowledgeEnricher().enrich(command, globalAnalysis, chunkBundle);
        var dossier = new PreprocessDossierAssembler().assemble(command, globalAnalysis, chunkBundle, knowledgeBundle);
        var input = new TranslationTaskInputAssembler().assemble(
                dossier,
                dossier.chunkAnnotations().chunks().get(0),
                null,
                null
        );

        ChunkTranslationLlmResultNormalizer normalizer = new ChunkTranslationLlmResultNormalizer();
        ChunkTranslationLlmResult normalized = normalizer.normalize(input, new ChunkTranslationLlmResult(
                "   ",
                "   ",
                List.of(
                        new ChunkTranslationDecisionNoteResult(" ", " ", "note-one", " "),
                        new ChunkTranslationDecisionNoteResult(" ", " ", "note-one", " ")
                ),
                List.of(
                        new ConfirmedTermUpdateResult("Paris", "Paris-zh"),
                        new ConfirmedTermUpdateResult("Paris", "Paris-zh")
                ),
                List.of(
                        new ChunkTranslationCandidateUpdateResult("old house", "old-house-zh", " ", true),
                        new ChunkTranslationCandidateUpdateResult("old house", "old-house-zh", " ", true)
                ),
                null
        ));

        assertEquals("Alice met Bob in Paris.", normalized.translatedText());
        assertFalse(normalized.translatorCommentary().isBlank());
        assertEquals(1, normalized.decisionNotes().size());
        assertEquals("note", normalized.decisionNotes().get(0).type());
        assertEquals(1, normalized.confirmedTermUpdates().size());
        assertEquals(1, normalized.candidateUpdates().size());
        assertFalse(normalized.candidateUpdates().get(0).rationale().isBlank());
        assertTrue(normalized.transitionNote().previousChunkConnection().isEmpty());
        assertFalse(normalized.transitionNote().boundaryAdjustmentSuggested());
    }
}
