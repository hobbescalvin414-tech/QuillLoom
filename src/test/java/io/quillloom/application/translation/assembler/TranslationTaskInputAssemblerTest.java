package io.quillloom.application.translation.assembler;

import io.quillloom.application.preprocess.assembler.PreprocessDossierAssembler;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.domain.memory.ChapterMemorySnapshot;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.translation.TranslationDecisionNote;
import io.quillloom.domain.translation.TranslationRuntimeOptions;
import io.quillloom.domain.translation.TranslationTaskInput;
import io.quillloom.infrastructure.preprocess.PreprocessBookAnalyzer;
import io.quillloom.support.BookAnalysisTestSupport;
import io.quillloom.support.PreprocessTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationTaskInputAssemblerTest {

    @Test
    void shouldAssembleSeparatedSourceMemoryAndRuntimeLayers() {
        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-1",
                "sample",
                """
                Alice met Bob in Paris.

                They walked along the river and talked about the old house.
                """,
                "en",
                "zh"
        );

        var globalAnalysis = createBookAnalyzer().analyze(command);
        var chunkBundle = PreprocessTestSupport.createChunkAnnotator().annotate(command, globalAnalysis);
        var knowledgeBundle = PreprocessTestSupport.createKnowledgeEnricher().enrich(command, globalAnalysis, chunkBundle);
        var dossier = new PreprocessDossierAssembler().assemble(command, globalAnalysis, chunkBundle, knowledgeBundle);

        ProjectMemorySnapshot projectMemory = new ProjectMemorySnapshot(
                "project-1",
                Map.of("Alice", "Aili"),
                List.of("keep tone restrained"),
                List.of("Paris is a city"),
                List.of(new TranslationCandidateUpdate("Bob", "Baobo", "common transliteration", true))
        );
        ChapterMemorySnapshot chapterMemory = new ChapterMemorySnapshot(
                "chapter-1",
                Map.of(),
                List.of("Bob name unresolved"),
                List.of("scene has entered Paris")
        );

        TranslationTaskInputAssembler assembler = new TranslationTaskInputAssembler();
        var input = assembler.assemble(dossier, dossier.chunkAnnotations().chunks().get(0), projectMemory, chapterMemory);

        assertEquals("project-1", input.sourceMaterial().project().projectId());
        assertEquals("Alice", input.sourceMaterial().chunk().entities().get(0));
        assertEquals("block-1", input.executionContextView().coarseBlockContext().currentBlockId());
        assertFalse(input.executionContextView().coarseBlockContext().currentBlockSummary().isBlank());
        assertEquals(1, input.executionContextView().coarseBlockContext().chunkIndexInCurrentBlock());
        assertTrue(input.executionContextView().coarseBlockContext().chunkCountInCurrentBlock() >= 1);
        assertTrue(input.executionContextView().coarseBlockContext().firstChunkInCurrentBlock());
        assertEquals("Aili", input.executionContextView().confirmedTerms().get("Alice"));
        assertEquals(1, input.executionContextView().candidateTermUpdates().size());
        assertEquals("Bob", input.executionContextView().candidateTermUpdates().get(0).sourceTerm());
        assertFalse(input.executionContextView().draftStageGlobalGlossary().hardEntries().isEmpty());
        assertEquals("Alice", input.executionContextView().draftStageGlobalGlossary().hardEntries().get(0).sourceTerm());
        assertTrue(input.executionContextView().globalAliasConsistencyTable().clusters().isEmpty());
        assertFalse(input.executionContextView().continuityNotes().isEmpty());
        assertTrue(input.runtimeOptions().allowKnowledgeCards());
        assertTrue(input.runtimeOptions().preserveParagraphBreaks());
        assertTrue(input.runtimeOptions().emitHandoffNotes());
        assertEquals(1, input.runtimeOptions().sourceContextWindowSize());
        assertEquals(2, input.runtimeOptions().summaryContextWindowSize());
    }

    @Test
    void shouldIncludeBilingualPreviousContextAndSourceOnlyNextContext() {
        String longSourceText = String.join("\n\n",
                "First paragraph sets the rainy Paris street and the distant bells while Erin walks toward the bridge. ".repeat(12),
                "Second paragraph places Erin on the bridge where she meets an old friend and mentions the northern house. ".repeat(12),
                "Third paragraph follows their silent walk away from the bridge as carriage sounds and church bells continue. ".repeat(12),
                "Fourth paragraph extends the walk into a quiet avenue where the conversation turns to family history. ".repeat(12),
                "Fifth paragraph shifts toward the church square where the carriage lanterns and wet stones dominate the scene. ".repeat(12)
        );

        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-2",
                "long-sample",
                longSourceText,
                "en",
                "zh"
        );

        var globalAnalysis = createBookAnalyzer().analyze(command);
        var chunkBundle = PreprocessTestSupport.createChunkAnnotator().annotate(command, globalAnalysis);
        var knowledgeBundle = PreprocessTestSupport.createKnowledgeEnricher().enrich(command, globalAnalysis, chunkBundle);
        var dossier = new PreprocessDossierAssembler().assemble(command, globalAnalysis, chunkBundle, knowledgeBundle);

        assertTrue(dossier.chunkAnnotations().chunks().size() >= 5);

        TranslationTaskInputAssembler assembler = new TranslationTaskInputAssembler();
        var middleChunk = dossier.chunkAnnotations().chunks().get(3);
        var runtimeOptions = new TranslationRuntimeOptions(true, true, true, 1, 2);
        var completedDrafts = List.of(
                createDraft(dossier.chunkAnnotations().chunks().get(2).chunk().chunkId(), "previous translated text")
        );
        var input = assembler.assemble(dossier, middleChunk, null, null, completedDrafts, runtimeOptions);

        assertEquals(1, input.executionContextView().localSourceContext().previousChunkSourceTexts().size());
        assertEquals(1, input.executionContextView().localSourceContext().previousChunkTranslatedTexts().size());
        assertEquals(1, input.executionContextView().localSourceContext().nextChunkSourceTexts().size());
        assertTrue(input.executionContextView().localSourceContext().previousChunkSummaries().size() >= 2);
        assertTrue(input.executionContextView().localSourceContext().nextChunkSummaries().size() >= 1);
        assertEquals(dossier.chunkAnnotations().chunks().get(2).chunk().sourceText(),
                input.executionContextView().localSourceContext().previousChunkSourceTexts().get(0));
        assertEquals("previous translated text",
                input.executionContextView().localSourceContext().previousChunkTranslatedTexts().get(0));
        assertEquals(middleChunk.chunk().coarseBlockId(), input.executionContextView().coarseBlockContext().currentBlockId());
        assertFalse(input.executionContextView().coarseBlockContext().currentBlockSummary().isBlank());
        assertTrue(input.executionContextView().coarseBlockContext().chunkIndexInCurrentBlock() >= 1);
        assertTrue(input.executionContextView().coarseBlockContext().chunkCountInCurrentBlock() >= input.executionContextView().coarseBlockContext().chunkIndexInCurrentBlock());
        if (input.executionContextView().coarseBlockContext().previousBlockId() != null) {
            assertFalse(input.executionContextView().coarseBlockContext().previousBlockSummary().isBlank());
        }
        if (input.executionContextView().coarseBlockContext().nextBlockId() != null) {
            assertFalse(input.executionContextView().coarseBlockContext().nextBlockSummary().isBlank());
        }
        assertEquals(dossier.chunkAnnotations().chunks().get(4).chunk().sourceText(),
                input.executionContextView().localSourceContext().nextChunkSourceTexts().get(0));
    }

    @Test
    void shouldUpdateBlockPositionInsideSameCoarseBlockWithoutChangingBlockSummary() {
        String longSourceText = String.join("\n\n",
                "Paragraph one keeps the same scene and expands the bridge description with repeated details. ".repeat(20),
                "Paragraph two keeps the same scene and follows the same bridge walk with repeated details. ".repeat(20),
                "Paragraph three keeps the same scene and extends the same bridge conversation with repeated details. ".repeat(20)
        );

        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-3",
                "same-block-sample",
                longSourceText,
                "en",
                "zh"
        );

        var globalAnalysis = createBookAnalyzer().analyze(command);
        var chunkBundle = PreprocessTestSupport.createChunkAnnotator().annotate(command, globalAnalysis);
        var knowledgeBundle = PreprocessTestSupport.createKnowledgeEnricher().enrich(command, globalAnalysis, chunkBundle);
        var dossier = new PreprocessDossierAssembler().assemble(command, globalAnalysis, chunkBundle, knowledgeBundle);

        assertTrue(dossier.chunkAnnotations().chunks().size() >= 2);

        TranslationTaskInputAssembler assembler = new TranslationTaskInputAssembler();
        var firstChunk = dossier.chunkAnnotations().chunks().get(0);
        var secondChunk = dossier.chunkAnnotations().chunks().get(1);

        assertEquals(firstChunk.chunk().coarseBlockId(), secondChunk.chunk().coarseBlockId());

        var firstInput = assembler.assemble(dossier, firstChunk, null, null);
        var secondInput = assembler.assemble(dossier, secondChunk, null, null);

        assertEquals(firstInput.executionContextView().coarseBlockContext().currentBlockId(),
                secondInput.executionContextView().coarseBlockContext().currentBlockId());
        assertEquals(firstInput.executionContextView().coarseBlockContext().currentBlockSummary(),
                secondInput.executionContextView().coarseBlockContext().currentBlockSummary());
        assertEquals(1, firstInput.executionContextView().coarseBlockContext().chunkIndexInCurrentBlock());
        assertEquals(2, secondInput.executionContextView().coarseBlockContext().chunkIndexInCurrentBlock());
        assertTrue(firstInput.executionContextView().coarseBlockContext().firstChunkInCurrentBlock());
        assertFalse(secondInput.executionContextView().coarseBlockContext().firstChunkInCurrentBlock());
    }

    @Test
    void shouldRejectSummaryWindowSmallerThanSourceWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationRuntimeOptions(true, true, true, 2, 1));
    }

    @Test
    void shouldExposeGlobalNamingTablesInExecutionContext() {
        TranslationTaskInput input = new TranslationTaskInputAssembler().assemble(
                createMinimalDossier(),
                createMinimalDossier().chunkAnnotations().chunks().get(0),
                new ProjectMemorySnapshot(
                        "project-global-naming",
                        Map.of("Louki", "露姬"),
                        List.of(),
                        List.of(),
                        List.of(new TranslationCandidateUpdate("Black Maria", "黑色马车", "候选", true))
                ),
                new ChapterMemorySnapshot("chapter-1", Map.of(), List.of(), List.of())
        );

        assertTrue(input.executionContextView().draftStageGlobalGlossary().hardEntries().stream()
                .anyMatch(entry -> entry.sourceTerm().equals("Louki") && entry.targetTerm().equals("露姬")));
        assertTrue(input.executionContextView().draftStageGlobalGlossary().softEntries().stream()
                .anyMatch(entry -> entry.sourceTerm().equals("Black Maria") && entry.targetTerm().equals("黑色马车")));
    }

    private PreprocessBookAnalyzer createBookAnalyzer() {
        return BookAnalysisTestSupport.createBookAnalyzer();
    }

    private io.quillloom.domain.preprocess.PreprocessDossier createMinimalDossier() {
        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-global-naming",
                "sample",
                "Louki, also called Jacqueline, waited for the Black Maria.",
                "en",
                "zh"
        );

        var globalAnalysis = createBookAnalyzer().analyze(command);
        var chunkBundle = PreprocessTestSupport.createChunkAnnotator().annotate(command, globalAnalysis);
        var knowledgeBundle = PreprocessTestSupport.createKnowledgeEnricher().enrich(command, globalAnalysis, chunkBundle);
        return new PreprocessDossierAssembler().assemble(command, globalAnalysis, chunkBundle, knowledgeBundle);
    }

    private ChunkTranslationDraft createDraft(String chunkId, String translatedText) {
        return new ChunkTranslationDraft(
                chunkId,
                translatedText,
                "commentary",
                List.of(new TranslationDecisionNote("resolved", "chunk", "note", "none")),
                Map.of(),
                List.of(new TranslationCandidateUpdate("Erin", "Ailin", "candidate", true)),
                new ChunkTransitionNote("prev", "next", false)
        );
    }
}
