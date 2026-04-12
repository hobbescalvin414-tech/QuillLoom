package io.quillloom.infrastructure.translation;

import io.quillloom.application.preprocess.assembler.PreprocessDossierAssembler;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.translation.assembler.TranslationTaskInputAssembler;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.support.BookAnalysisTestSupport;
import io.quillloom.support.PreprocessTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkTranslationResultValidatorTest {

    @Test
    void shouldOnlyAllowAppendingNewConfirmedTerms() {
        var input = createInput();

        ChunkTranslationResultValidator validator = new ChunkTranslationResultValidator();
        ChunkTranslationLlmResult validated = validator.validate(input, new ChunkTranslationLlmResult(
                "translated",
                "commentary",
                List.of(),
                List.of(
                        new ConfirmedTermUpdateResult("Paris", "Paris-other"),
                        new ConfirmedTermUpdateResult("Harbor Master", "Harbor-Master-zh")
                ),
                List.of(),
                new ChunkTranslationTransitionNoteResult("", "", false)
        ));

        assertEquals(1, validated.confirmedTermUpdates().size());
        assertEquals("Harbor Master", validated.confirmedTermUpdates().get(0).sourceTerm());
        assertFalse(validated.confirmedTermUpdates().stream().anyMatch(item -> item.sourceTerm().equals("Paris")));
        assertEquals(1, validated.decisionNotes().size());
        assertEquals("confirmed-term-conflict", validated.decisionNotes().get(0).type());
    }

    @Test
    void shouldKeepCandidateUpdatesWhenConfirmedTermConflictsWithActiveGlossary() {
        var input = createInput();

        ChunkTranslationResultValidator validator = new ChunkTranslationResultValidator();
        ChunkTranslationLlmResult validated = validator.validate(input, new ChunkTranslationLlmResult(
                "translated",
                "commentary",
                List.of(),
                List.of(new ConfirmedTermUpdateResult("Paris", "Paris-other")),
                List.of(new ChunkTranslationCandidateUpdateResult(
                        "Paris",
                        "Paris-other",
                        "candidate for later review",
                        true
                )),
                new ChunkTranslationTransitionNoteResult("", "", false)
        ));

        assertEquals(0, validated.confirmedTermUpdates().size());
        assertEquals(1, validated.candidateUpdates().size());
        assertEquals("Paris", validated.candidateUpdates().get(0).sourceTerm());
        assertEquals("Paris-other", validated.candidateUpdates().get(0).candidateTranslation());
        assertEquals(1, validated.decisionNotes().size());
        assertEquals("confirmed-term-conflict", validated.decisionNotes().get(0).type());
    }

    @Test
    void shouldNormalizeDecisionNotesAndStripInvalidTransitionUsage() {
        var input = createInput();

        ChunkTranslationResultValidator validator = new ChunkTranslationResultValidator();
        ChunkTranslationLlmResult validated = validator.validate(input, new ChunkTranslationLlmResult(
                "translated",
                "commentary",
                List.of(
                        new ChunkTranslationDecisionNoteResult("todo", "anchor-1", "duplicate note", "keep current wording"),
                        new ChunkTranslationDecisionNoteResult("todo", "anchor-1", "duplicate note", "keep current wording"),
                        new ChunkTranslationDecisionNoteResult("risk", "anchor-2", "   ", "n/a")
                ),
                List.of(),
                List.of(),
                new ChunkTranslationTransitionNoteResult("candidate term should not be here", "re-split chunk please", true)
        ));

        assertEquals(1, validated.decisionNotes().size());
        assertEquals("issue", validated.decisionNotes().get(0).type());
        assertEquals("duplicate note", validated.decisionNotes().get(0).description());
        assertEquals("", validated.transitionNote().previousChunkConnection());
        assertEquals("", validated.transitionNote().nextChunkConnection());
        assertFalse(validated.transitionNote().boundaryAdjustmentSuggested());
    }

    @Test
    void shouldKeepTranslatorCommentaryAsProcessingCommentOnly() {
        var input = createInput();

        ChunkTranslationResultValidator validator = new ChunkTranslationResultValidator();
        ChunkTranslationLlmResult validated = validator.validate(input, new ChunkTranslationLlmResult(
                "translated",
                "keep this line\nDecisionNotes: remove this line\ncandidateUpdates: remove this too",
                List.of(),
                List.of(),
                List.of(),
                new ChunkTranslationTransitionNoteResult("", "", false)
        ));

        assertEquals("keep this line", validated.translatorCommentary());
    }

    @Test
    void shouldRecordDeterministicTextBoundaryNotesWithoutThrowing() {
        var input = createInput();

        ChunkTranslationResultValidator validator = new ChunkTranslationResultValidator();
        ChunkTranslationLlmResult validated = validator.validate(input, new ChunkTranslationLlmResult(
                "孔代咖啡馆（Le Condé）——巴黎左岸一家边缘文化据点——",
                "commentary",
                List.of(),
                List.of(),
                List.of(),
                new ChunkTranslationTransitionNoteResult("", "", false)
        ));

        assertTrue(validated.translatedText().contains("孔代咖啡馆"));
        assertTrue(validated.decisionNotes().stream()
                .anyMatch(note -> note.type().equals("text-boundary-warning")));
    }

    @Test
    void shouldAddGlossaryComplianceWarningsToDecisionNotes() {
        var input = createInput();

        ChunkTranslationResultValidator validator = new ChunkTranslationResultValidator();
        ChunkTranslationLlmResult validated = validator.validate(input, new ChunkTranslationLlmResult(
                "Paris灯火通明，而Louki站在门口没有回头。",
                "commentary",
                List.of(),
                List.of(),
                List.of(),
                new ChunkTranslationTransitionNoteResult("", "", false)
        ));

        assertTrue(validated.decisionNotes().stream()
                .anyMatch(note -> note.type().equals("glossary-compliance-warning")));
    }

    private io.quillloom.domain.translation.TranslationTaskInput createInput() {
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
        return new TranslationTaskInputAssembler().assemble(
                dossier,
                dossier.chunkAnnotations().chunks().get(0),
                new ProjectMemorySnapshot("project-1", Map.of("Paris", "Paris-zh"), List.of(), List.of()),
                null
        );
    }
}
