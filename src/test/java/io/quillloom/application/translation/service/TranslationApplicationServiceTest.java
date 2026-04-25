package io.quillloom.application.translation.service;

import io.quillloom.application.preprocess.assembler.PreprocessDossierAssembler;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.translation.assembler.TranslationTaskInputAssembler;
import io.quillloom.application.translation.model.ConfirmedTermConflict;
import io.quillloom.application.translation.port.out.ConfirmedTermConflictRepairingChunkTranslator;
import io.quillloom.application.translation.model.TranslationDraftRunResult;
import io.quillloom.application.translation.port.out.ChunkTranslator;
import io.quillloom.domain.book.BookProject;
import io.quillloom.domain.knowledge.CandidateTerm;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.memory.ChapterMemorySnapshot;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.ChunkAnnotationBundle;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.CoarseChunkBlock;
import io.quillloom.domain.preprocess.CoarseChunkPlan;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import io.quillloom.domain.preprocess.KnowledgeEnrichmentBundle;
import io.quillloom.domain.preprocess.PersonAliasHint;
import io.quillloom.domain.preprocess.PreprocessDossier;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.translation.TranslationDecisionNote;
import io.quillloom.domain.translation.TranslationRuntimeOptions;
import io.quillloom.domain.translation.TranslationTaskInput;
import io.quillloom.support.BookAnalysisTestSupport;
import io.quillloom.support.PreprocessTestSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationApplicationServiceTest {

    @Test
    void shouldAssembleStableInputThenDelegateSingleRoundChunkTranslation() {
        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-translation",
                "sample",
                "Alice met Bob in Paris.\n\nThey walked along the river.",
                "en",
                "zh"
        );

        var globalAnalysis = BookAnalysisTestSupport.createBookAnalyzer().analyze(command);
        var chunkBundle = PreprocessTestSupport.createChunkAnnotator().annotate(command, globalAnalysis);
        var knowledgeBundle = PreprocessTestSupport.createKnowledgeEnricher().enrich(command, globalAnalysis, chunkBundle);
        var dossier = new PreprocessDossierAssembler().assemble(command, globalAnalysis, chunkBundle, knowledgeBundle);
        var chunk = dossier.chunkAnnotations().chunks().get(0);

        ProjectMemorySnapshot projectMemory = new ProjectMemorySnapshot(
                "project-translation",
                Map.of("Alice", "Alice-zh"),
                List.of("keep voice restrained"),
                List.of("Paris should stay Paris-zh"),
                List.of(new TranslationCandidateUpdate("Bob", "Bob-zh", "common transliteration", true))
        );
        ChapterMemorySnapshot chapterMemory = new ChapterMemorySnapshot(
                "chapter-1",
                Map.of(),
                List.of(),
                List.of("scene moves into riverside dialogue")
        );

        RecordingChunkTranslator translator = new RecordingChunkTranslator();
        TranslationApplicationService service = new TranslationApplicationService(
                new TranslationTaskInputAssembler(),
                translator
        );

        ChunkTranslationDraft draft = service.translateChunk(
                dossier,
                chunk,
                projectMemory,
                chapterMemory,
                List.of(),
                TranslationRuntimeOptions.defaults()
        );

        assertSame(draft, translator.resultToReturn);
        assertEquals("Alice-zh met Bob-zh in Paris-zh.", draft.translatedText());
        assertEquals("Alice-zh", translator.capturedInput.executionContextView().confirmedTerms().get("Alice"));
        assertEquals("Bob", translator.capturedInput.executionContextView().candidateTermUpdates().get(0).sourceTerm());
        assertEquals(chunk.chunk().chunkId(), translator.capturedInput.sourceMaterial().chunk().chunk().chunkId());
    }

    @Test
    void shouldKeepAliasTableReadOnlyAndOnlyUseConfirmedCandidateUpdatesForOutOfTableItems() {
        PreprocessDossier dossier = createAliasAwareDossier();

        ProjectMemorySnapshot projectMemory = new ProjectMemorySnapshot(
                "project-translation-3",
                Map.of("Louki", "露姬"),
                List.of(),
                List.of(),
                List.of(new TranslationCandidateUpdate("Black Maria", "黑色马车", "已有候选", true))
        );

        AliasAwareChunkTranslator translator = new AliasAwareChunkTranslator();
        TranslationApplicationService service = new TranslationApplicationService(
                new TranslationTaskInputAssembler(),
                translator
        );

        service.translateChunk(
                dossier,
                dossier.chunkAnnotations().chunks().get(0),
                projectMemory,
                new ChapterMemorySnapshot("chapter-1", Map.of(), List.of(), List.of()),
                List.of(),
                TranslationRuntimeOptions.defaults()
        );

        assertTrue(translator.capturedInput.executionContextView().globalAliasConsistencyTable().clusters().stream()
                .anyMatch(cluster -> cluster.surfaceForms().contains("Louki") && cluster.surfaceForms().contains("Jacqueline")));
        assertFalse(translator.capturedInput.executionContextView().draftStageGlobalGlossary().softEntries().stream()
                .anyMatch(entry -> entry.sourceTerm().equals("Louki")));
        assertTrue(translator.resultToReturn.confirmedTermUpdates().containsKey("Night Watchman"));
        assertFalse(translator.resultToReturn.confirmedTermUpdates().containsKey("Louki"));
    }

    @Test
    void shouldTranslateChunksSequentiallyAndCarryForwardAllowedStableMemoryOnly() {
        String longSourceText = String.join("\n\n",
                "First paragraph sets the rainy Paris street and the distant bells while Erin walks toward the bridge. ".repeat(12),
                "Second paragraph places Erin on the bridge where she meets an old friend and mentions the northern house. ".repeat(12),
                "Third paragraph follows their silent walk away from the bridge as carriage sounds and church bells continue. ".repeat(12),
                "Fourth paragraph extends the walk into a quiet avenue where the conversation turns to family history. ".repeat(12),
                "Fifth paragraph shifts toward the church square where the carriage lanterns and wet stones dominate the scene. ".repeat(12)
        );

        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-translation-2",
                "sample",
                longSourceText,
                "en",
                "zh"
        );

        var globalAnalysis = BookAnalysisTestSupport.createBookAnalyzer().analyze(command);
        var chunkBundle = PreprocessTestSupport.createChunkAnnotator().annotate(command, globalAnalysis);
        var knowledgeBundle = PreprocessTestSupport.createKnowledgeEnricher().enrich(command, globalAnalysis, chunkBundle);
        var dossier = new PreprocessDossierAssembler().assemble(command, globalAnalysis, chunkBundle, knowledgeBundle);

        assertTrue(dossier.chunkAnnotations().chunks().size() >= 3);

        ProjectMemorySnapshot projectMemory = new ProjectMemorySnapshot(
                "project-translation-2",
                Map.of("Erin", "Erin-zh"),
                List.of(),
                List.of()
        );
        ChapterMemorySnapshot chapterMemory = new ChapterMemorySnapshot(
                "chapter-1",
                Map.of(),
                List.of(),
                List.of("initial continuity note")
        );

        SequencedChunkTranslator translator = new SequencedChunkTranslator();
        TranslationApplicationService service = new TranslationApplicationService(
                new TranslationTaskInputAssembler(),
                translator
        );

        List<ChunkTranslationDraft> drafts = service.translateChunks(dossier, projectMemory, chapterMemory, TranslationRuntimeOptions.defaults());

        assertEquals(dossier.chunkAnnotations().chunks().size(), drafts.size());
        assertEquals(dossier.chunkAnnotations().chunks().size(), translator.previousTranslatedContextSizes.size());
        assertEquals(0, translator.previousTranslatedContextSizes.get(0));
        assertTrue(translator.previousTranslatedContextSizes.get(1) >= 1);
        assertTrue(translator.previousTranslatedContextSizes.get(2) >= 1);
        assertEquals("draft-1", drafts.get(0).translatedText());
        assertEquals("draft-2", drafts.get(1).translatedText());

        assertEquals("Erin-zh", translator.confirmedTermsByRound.get(0).get("Erin"));
        assertTrue(translator.confirmedTermsByRound.get(1).containsKey("Harbor Master"));
        assertEquals("Harbor-Master-zh", translator.confirmedTermsByRound.get(1).get("Harbor Master"));
        assertEquals(0, translator.candidateTermsByRound.get(0).size());
        assertEquals(1, translator.candidateTermsByRound.get(1).size());
        assertEquals("North House", translator.candidateTermsByRound.get(1).get(0).sourceTerm());
        assertEquals(1, translator.continuityNotesByRound.get(0).size());
        assertEquals("initial continuity note", translator.continuityNotesByRound.get(0).get(0));
        assertTrue(translator.continuityNotesByRound.get(1).contains("prompt next chunk with harbor tone"));
        assertTrue(translator.continuityNotesByRound.get(1).contains("previous chunk may need boundary follow-up"));
        assertFalse(translator.confirmedTermsByRound.get(1).containsKey("North House"));
    }

    @Test
    void shouldReturnFinalProjectMemoryAfterSequentialDraftRun() {
        PreprocessDossier dossier = createTwoChunkDossier("project-final-memory", "A appears.", "A returns.");
        ProjectMemorySnapshot projectMemory = new ProjectMemorySnapshot(
                "project-final-memory",
                Map.of(),
                List.of(),
                List.of()
        );
        ChapterMemorySnapshot chapterMemory = new ChapterMemorySnapshot("chapter-1", Map.of(), List.of(), List.of());

        SequencedTermChunkTranslator translator = new SequencedTermChunkTranslator("A", "甲");
        TranslationApplicationService service = new TranslationApplicationService(
                new TranslationTaskInputAssembler(),
                translator
        );

        TranslationDraftRunResult result = service.translateChunksWithMemory(
                dossier,
                projectMemory,
                chapterMemory,
                TranslationRuntimeOptions.defaults()
        );

        assertEquals(2, result.drafts().size());
        assertEquals("甲", translator.confirmedTermsByRound.get(1).get("A"));
        assertEquals("甲", result.finalProjectMemory().confirmedTerms().get("A"));
    }

    @Test
    void shouldRepairCurrentChunkWhenDraftConfirmedTermConflictsWithProjectMemory() {
        PreprocessDossier dossier = createTwoChunkDossier("project-term-conflict", "Le Condé appears.", "Next scene.");
        ProjectMemorySnapshot projectMemory = new ProjectMemorySnapshot(
                "project-term-conflict",
                Map.of("Le Condé", "孔代咖啡馆"),
                List.of(),
                List.of()
        );
        RepairingConflictChunkTranslator translator = RepairingConflictChunkTranslator.repairingTo(
                "孔代咖啡馆里灯光昏暗。",
                Map.of("Le Condé", "孔代咖啡馆")
        );
        TranslationApplicationService service = new TranslationApplicationService(
                new TranslationTaskInputAssembler(),
                translator
        );

        TranslationDraftRunResult result = service.translateChunksWithMemory(
                dossier,
                projectMemory,
                new ChapterMemorySnapshot("chapter-1", Map.of(), List.of(), List.of()),
                TranslationRuntimeOptions.defaults()
        );

        assertEquals(1, translator.repairConflicts.size());
        ConfirmedTermConflict conflict = translator.repairConflicts.get(0);
        assertEquals("le condé", conflict.sourceKey());
        assertEquals("Le Condé", conflict.existingSourceTerm());
        assertEquals("孔代咖啡馆", conflict.existingTargetTerm());
        assertEquals("le condé", conflict.incomingSourceTerm());
        assertEquals("勒孔代咖啡馆", conflict.incomingTargetTerm());
        assertEquals("chunk-1", conflict.evidenceChunkId());
        assertTrue(translator.repairPreviousDrafts.get(0).translatedText().contains("勒孔代咖啡馆"));
        assertEquals("孔代咖啡馆里灯光昏暗。", result.drafts().get(0).translatedText());
        assertEquals("孔代咖啡馆", result.finalProjectMemory().confirmedTerms().get("Le Condé"));
    }

    @Test
    void shouldAllowIdenticalConfirmedTermDuplicateDuringProjectMemoryEvolution() {
        PreprocessDossier dossier = createTwoChunkDossier("project-term-duplicate", "Le Condé appears.", "Le Condé returns.");
        ProjectMemorySnapshot projectMemory = new ProjectMemorySnapshot(
                "project-term-duplicate",
                Map.of("Le Condé", "孔代咖啡馆"),
                List.of(),
                List.of()
        );
        TranslationApplicationService service = new TranslationApplicationService(
                new TranslationTaskInputAssembler(),
                new FixedConfirmedTermChunkTranslator("Le Condé", "孔代咖啡馆")
        );

        TranslationDraftRunResult result = service.translateChunksWithMemory(
                dossier,
                projectMemory,
                new ChapterMemorySnapshot("chapter-1", Map.of(), List.of(), List.of()),
                TranslationRuntimeOptions.defaults()
        );

        assertEquals("孔代咖啡馆", result.finalProjectMemory().confirmedTerms().get("Le Condé"));
        assertTrue(result.finalProjectMemory().confirmedTerms().containsKey("Le Condé"));
        assertFalse(result.finalProjectMemory().confirmedTerms().containsKey("le condé"));
    }

    @Test
    void shouldExhaustConfirmedTermConflictRepairAfterThreeAttempts() {
        PreprocessDossier dossier = createTwoChunkDossier("project-term-conflict-exhausted", "Le Condé appears.", "Next scene.");
        ProjectMemorySnapshot projectMemory = new ProjectMemorySnapshot(
                "project-term-conflict-exhausted",
                Map.of("Le Condé", "孔代咖啡馆"),
                List.of(),
                List.of()
        );
        RepairingConflictChunkTranslator translator = RepairingConflictChunkTranslator.alwaysConflicting();
        TranslationApplicationService service = new TranslationApplicationService(
                new TranslationTaskInputAssembler(),
                translator
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.translateChunksWithMemory(
                dossier,
                projectMemory,
                new ChapterMemorySnapshot("chapter-1", Map.of(), List.of(), List.of()),
                TranslationRuntimeOptions.defaults()
        ));

        assertTrue(exception.getMessage().contains("confirmed_term_conflict_repair_exhausted"));
        assertEquals(3, translator.repairConflicts.size());
        assertEquals(List.of(1, 2, 3), translator.repairAttempts);
    }

    @Test
    void shouldDeduplicateCandidateTermsByLocaleStablePairKey() {
        PreprocessDossier dossier = createTwoChunkDossier("project-candidate-dedup", "Le Condé appears.", "Le Condé returns.");
        ProjectMemorySnapshot projectMemory = new ProjectMemorySnapshot(
                "project-candidate-dedup",
                Map.of(),
                List.of(),
                List.of()
        );
        TranslationApplicationService service = new TranslationApplicationService(
                new TranslationTaskInputAssembler(),
                new SequencedCandidateChunkTranslator()
        );

        TranslationDraftRunResult result = service.translateChunksWithMemory(
                dossier,
                projectMemory,
                new ChapterMemorySnapshot("chapter-1", Map.of(), List.of(), List.of()),
                TranslationRuntimeOptions.defaults()
        );

        assertEquals(1, result.finalProjectMemory().candidateTermUpdates().size());
        assertEquals("Le Condé", result.finalProjectMemory().candidateTermUpdates().get(0).sourceTerm());
        assertEquals("孔代咖啡馆", result.finalProjectMemory().candidateTermUpdates().get(0).candidateTranslation());
    }

    @Test
    void shouldCarryForwardFirstNamingDecisionEvenWhenChoosingToKeepSourceName() {
        PreprocessDossier dossier = new PreprocessDossier(
                new BookProject("project-translation-keep-source", "sample", "en", "zh"),
                new GlobalAnalysisBundle(
                        new BookAnalysis("summary", "outline", "style", List.of(), List.of()),
                        List.of(),
                        new CoarseChunkPlan(List.of(
                                new CoarseChunkBlock("block-1", 1, 0, 24, "Louki stood by the door.", "chunk-1 block", ""),
                                new CoarseChunkBlock("block-2", 2, 25, 49, "Louki looked back.", "chunk-2 block", "")
                        ))
                ),
                new ChunkAnnotationBundle(List.of(
                        new ChunkAnnotation(
                                new ChunkDescriptor("chunk-1", 1, "block-1", 0, 24, "Louki stood by the door."),
                                "summary-1",
                                List.of("Louki"),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        ),
                        new ChunkAnnotation(
                                new ChunkDescriptor("chunk-2", 2, "block-2", 25, 49, "Louki looked back."),
                                "summary-2",
                                List.of("Louki"),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        )
                )),
                new KnowledgeEnrichmentBundle(new ProjectKnowledgeBase("project-translation-keep-source", List.of(), List.of()))
        );

        KeepSourceNameChunkTranslator translator = new KeepSourceNameChunkTranslator();
        TranslationApplicationService service = new TranslationApplicationService(
                new TranslationTaskInputAssembler(),
                translator
        );

        service.translateChunks(
                dossier,
                new ProjectMemorySnapshot("project-translation-keep-source", Map.of(), List.of(), List.of()),
                new ChapterMemorySnapshot("chapter-1", Map.of(), List.of(), List.of()),
                TranslationRuntimeOptions.defaults()
        );

        assertTrue(translator.confirmedTermsByRound.get(1).containsKey("Louki"));
        assertEquals("Louki", translator.confirmedTermsByRound.get(1).get("Louki"));
    }

    private PreprocessDossier createAliasAwareDossier() {
        ChunkAnnotation chunk = new ChunkAnnotation(
                new ChunkDescriptor("chunk-1", 1, "block-1", 0, 54, "Louki, also called Jacqueline, waited for the Black Maria."),
                "摘要",
                List.of("Louki", "Jacqueline", "Black Maria"),
                List.of(),
                List.of(),
                List.of("Black Maria"),
                List.of(new PersonAliasHint(
                        List.of("Louki", "Jacqueline"),
                        "same-person-name-variant",
                        "HIGH",
                        "同段切换称呼"
                ))
        );

        ProjectKnowledgeBase knowledgeBase = new ProjectKnowledgeBase(
                "project-translation-3",
                List.of(new KnowledgeCard(
                        "card-louki",
                        KnowledgeCardType.CHARACTER_PROFILE,
                        "Louki 人物卡",
                        "人物背景",
                        List.of("Louki", "Jacqueline"),
                        List.of("Louki"),
                        List.of("chunk-1"),
                        "PROJECT",
                        List.of("chunk-1"),
                        Map.of(
                                "canonicalName", "Louki",
                                "aliasState", "SUSPECTED_ALIAS",
                                "confidence", "HIGH",
                                "surfaceForms", List.of("Louki", "Jacqueline")
                        )
                )),
                List.of(new CandidateTerm("Black Maria", List.of("黑色马车"), "TERM", "候选术语"))
        );

        return new PreprocessDossier(
                new BookProject("project-translation-3", "sample", "en", "zh"),
                new GlobalAnalysisBundle(
                        new BookAnalysis("概要", "结构", "风格", List.of(), List.of()),
                        List.of(),
                        new CoarseChunkPlan(List.of(new CoarseChunkBlock("block-1", 1, 0, 54, chunk.chunk().sourceText(), "摘要", "")))
                ),
                new ChunkAnnotationBundle(List.of(chunk)),
                new KnowledgeEnrichmentBundle(knowledgeBase)
        );
    }

    private PreprocessDossier createTwoChunkDossier(String projectId,
                                                    String firstSource,
                                                    String secondSource) {
        ChunkAnnotation firstChunk = new ChunkAnnotation(
                new ChunkDescriptor("chunk-1", 1, "block-1", 0, firstSource.length(), firstSource),
                "summary-1",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        ChunkAnnotation secondChunk = new ChunkAnnotation(
                new ChunkDescriptor("chunk-2", 2, "block-1", firstSource.length() + 1,
                        firstSource.length() + 1 + secondSource.length(), secondSource),
                "summary-2",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new PreprocessDossier(
                new BookProject(projectId, "sample", "en", "zh"),
                new GlobalAnalysisBundle(
                        new BookAnalysis("summary", "outline", "style", List.of(), List.of()),
                        List.of(),
                        new CoarseChunkPlan(List.of(new CoarseChunkBlock(
                                "block-1",
                                1,
                                0,
                                firstSource.length() + 1 + secondSource.length(),
                                firstSource + "\n" + secondSource,
                                "block summary",
                                ""
                        )))
                ),
                new ChunkAnnotationBundle(List.of(firstChunk, secondChunk)),
                new KnowledgeEnrichmentBundle(new ProjectKnowledgeBase(projectId, List.of(), List.of()))
        );
    }

    private static final class RecordingChunkTranslator implements ChunkTranslator {

        private TranslationTaskInput capturedInput;
        private final ChunkTranslationDraft resultToReturn = new ChunkTranslationDraft(
                "chunk-1",
                "Alice-zh met Bob-zh in Paris-zh.",
                "single round execution",
                List.of(new TranslationDecisionNote("note", "met Bob", "name can be reviewed later", "keep current rendering")),
                Map.of("Paris", "Paris-zh"),
                List.of(new TranslationCandidateUpdate("Bob", "Bob-zh", "common transliteration", true)),
                new ChunkTransitionNote("meeting scene", "shift into riverside walk", false)
        );

        @Override
        public ChunkTranslationDraft translate(TranslationTaskInput input) {
            this.capturedInput = input;
            return resultToReturn;
        }
    }

    private static final class SequencedChunkTranslator implements ChunkTranslator {

        private final List<Integer> previousTranslatedContextSizes = new ArrayList<>();
        private final List<Map<String, String>> confirmedTermsByRound = new ArrayList<>();
        private final List<List<TranslationCandidateUpdate>> candidateTermsByRound = new ArrayList<>();
        private final List<List<String>> continuityNotesByRound = new ArrayList<>();
        private int sequence = 0;

        @Override
        public ChunkTranslationDraft translate(TranslationTaskInput input) {
            previousTranslatedContextSizes.add(input.executionContextView().localSourceContext().previousChunkTranslatedTexts().size());
            confirmedTermsByRound.add(input.executionContextView().confirmedTerms());
            candidateTermsByRound.add(input.executionContextView().candidateTermUpdates());
            continuityNotesByRound.add(input.executionContextView().continuityNotes());
            sequence++;

            if (sequence == 1) {
                return new ChunkTranslationDraft(
                        input.sourceMaterial().chunk().chunk().chunkId(),
                        "draft-1",
                        "first sequential draft",
                        List.of(new TranslationDecisionNote("risk", "Harbor Master", "needs consistency check", "carry forward current rendering")),
                        Map.of("Harbor Master", "Harbor-Master-zh"),
                        List.of(new TranslationCandidateUpdate("North House", "North-House-zh", "candidate for later confirmation", true)),
                        new ChunkTransitionNote("continue current scene", "prompt next chunk with harbor tone", true)
                );
            }

            return new ChunkTranslationDraft(
                    input.sourceMaterial().chunk().chunk().chunkId(),
                    "draft-" + sequence,
                    "next sequential draft",
                    List.of(),
                    Map.of(),
                    List.of(),
                    new ChunkTransitionNote("previous chunk may need boundary follow-up", "", false)
            );
        }
    }

    private static final class AliasAwareChunkTranslator implements ChunkTranslator {

        private TranslationTaskInput capturedInput;
        private final ChunkTranslationDraft resultToReturn = new ChunkTranslationDraft(
                "chunk-1",
                "露姬等着夜巡人。",
                "alias table is read-only",
                List.of(new TranslationDecisionNote("consistency", "Jacqueline", "别名只读消费，不回写 alias 事实", "保持当前 alias 表只读")),
                Map.of("Night Watchman", "夜巡人"),
                List.of(new TranslationCandidateUpdate("Black Maria", "黑色马车", "表外候选", true)),
                new ChunkTransitionNote("", "", false)
        );

        @Override
        public ChunkTranslationDraft translate(TranslationTaskInput input) {
            this.capturedInput = input;
            return resultToReturn;
        }
    }

    private static final class KeepSourceNameChunkTranslator implements ChunkTranslator {

        private final List<Map<String, String>> confirmedTermsByRound = new ArrayList<>();
        private int sequence = 0;

        @Override
        public ChunkTranslationDraft translate(TranslationTaskInput input) {
            confirmedTermsByRound.add(input.executionContextView().confirmedTerms());
            sequence++;
            if (sequence == 1) {
                return new ChunkTranslationDraft(
                        input.sourceMaterial().chunk().chunk().chunkId(),
                        "Louki站在门口。",
                        "keep source name for now",
                        List.of(),
                        Map.of("Louki", "Louki"),
                        List.of(),
                        new ChunkTransitionNote("", "", false)
                );
            }
            return new ChunkTranslationDraft(
                    input.sourceMaterial().chunk().chunk().chunkId(),
                    "Louki回头看了一眼。",
                    "second round",
                    List.of(),
                    Map.of(),
                    List.of(),
                    new ChunkTransitionNote("", "", false)
            );
        }
    }

    private static final class SequencedTermChunkTranslator implements ChunkTranslator {

        private final String sourceTerm;
        private final String targetTerm;
        private final List<Map<String, String>> confirmedTermsByRound = new ArrayList<>();
        private int sequence = 0;

        private SequencedTermChunkTranslator(String sourceTerm, String targetTerm) {
            this.sourceTerm = sourceTerm;
            this.targetTerm = targetTerm;
        }

        @Override
        public ChunkTranslationDraft translate(TranslationTaskInput input) {
            confirmedTermsByRound.add(input.executionContextView().confirmedTerms());
            sequence++;
            if (sequence == 1) {
                return new ChunkTranslationDraft(
                        input.sourceMaterial().chunk().chunk().chunkId(),
                        "draft-1",
                        "confirm term",
                        List.of(),
                        Map.of(sourceTerm, targetTerm),
                        List.of(),
                        new ChunkTransitionNote("", "", false)
                );
            }
            return new ChunkTranslationDraft(
                    input.sourceMaterial().chunk().chunk().chunkId(),
                    "draft-2",
                    "consume term",
                    List.of(),
                    Map.of(),
                    List.of(),
                    new ChunkTransitionNote("", "", false)
            );
        }
    }

    private static final class FixedConfirmedTermChunkTranslator implements ChunkTranslator {

        private final String sourceTerm;
        private final String targetTerm;

        private FixedConfirmedTermChunkTranslator(String sourceTerm, String targetTerm) {
            this.sourceTerm = sourceTerm;
            this.targetTerm = targetTerm;
        }

        @Override
        public ChunkTranslationDraft translate(TranslationTaskInput input) {
            return new ChunkTranslationDraft(
                    input.sourceMaterial().chunk().chunk().chunkId(),
                    "draft",
                    "fixed term",
                    List.of(),
                    Map.of(sourceTerm, targetTerm),
                    List.of(),
                    new ChunkTransitionNote("", "", false)
            );
        }
    }

    private static final class RepairingConflictChunkTranslator implements ConfirmedTermConflictRepairingChunkTranslator {

        private final boolean alwaysConflicting;
        private final String repairedText;
        private final Map<String, String> repairedTerms;
        private final List<ConfirmedTermConflict> repairConflicts = new ArrayList<>();
        private final List<ChunkTranslationDraft> repairPreviousDrafts = new ArrayList<>();
        private final List<Integer> repairAttempts = new ArrayList<>();
        private int translateSequence = 0;

        private RepairingConflictChunkTranslator(boolean alwaysConflicting,
                                                String repairedText,
                                                Map<String, String> repairedTerms) {
            this.alwaysConflicting = alwaysConflicting;
            this.repairedText = repairedText;
            this.repairedTerms = repairedTerms;
        }

        private static RepairingConflictChunkTranslator repairingTo(String repairedText,
                                                                    Map<String, String> repairedTerms) {
            return new RepairingConflictChunkTranslator(false, repairedText, repairedTerms);
        }

        private static RepairingConflictChunkTranslator alwaysConflicting() {
            return new RepairingConflictChunkTranslator(true, "勒孔代咖啡馆里灯光昏暗。", Map.of("le condé", "勒孔代咖啡馆"));
        }

        @Override
        public ChunkTranslationDraft translate(TranslationTaskInput input) {
            translateSequence++;
            if (!alwaysConflicting && translateSequence > 1) {
                return new ChunkTranslationDraft(
                        input.sourceMaterial().chunk().chunk().chunkId(),
                        "next draft",
                        "no term update",
                        List.of(),
                        Map.of(),
                        List.of(),
                        new ChunkTransitionNote("", "", false)
                );
            }
            return conflictingDraft(input.sourceMaterial().chunk().chunk().chunkId());
        }

        @Override
        public ChunkTranslationDraft repairConfirmedTermConflict(TranslationTaskInput input,
                                                                 ChunkTranslationDraft previousDraft,
                                                                 ConfirmedTermConflict conflict,
                                                                 int attempt) {
            repairPreviousDrafts.add(previousDraft);
            repairConflicts.add(conflict);
            repairAttempts.add(attempt);
            if (alwaysConflicting) {
                return conflictingDraft(input.sourceMaterial().chunk().chunk().chunkId());
            }
            return new ChunkTranslationDraft(
                    input.sourceMaterial().chunk().chunk().chunkId(),
                    repairedText,
                    "沿用既有译名：" + conflict.existingSourceTerm() + " => " + conflict.existingTargetTerm(),
                    List.of(new TranslationDecisionNote(
                            "confirmed-term-conflict",
                            conflict.incomingSourceTerm(),
                            "本轮冲突译名已修正。",
                            "沿用既有译名：" + conflict.existingTargetTerm()
                    )),
                    repairedTerms,
                    List.of(),
                    new ChunkTransitionNote("", "", false)
            );
        }

        private ChunkTranslationDraft conflictingDraft(String chunkId) {
            return new ChunkTranslationDraft(
                    chunkId,
                    "勒孔代咖啡馆里灯光昏暗。",
                    "initial conflicting term",
                    List.of(),
                    Map.of("le condé", "勒孔代咖啡馆"),
                    List.of(),
                    new ChunkTransitionNote("", "", false)
            );
        }
    }

    private static final class SequencedCandidateChunkTranslator implements ChunkTranslator {

        private int sequence = 0;

        @Override
        public ChunkTranslationDraft translate(TranslationTaskInput input) {
            sequence++;
            TranslationCandidateUpdate candidate = sequence == 1
                    ? new TranslationCandidateUpdate("Le Condé", "孔代咖啡馆", "first", true)
                    : new TranslationCandidateUpdate("le condé", "孔代咖啡馆", "duplicate by key", true);
            return new ChunkTranslationDraft(
                    input.sourceMaterial().chunk().chunk().chunkId(),
                    "draft-" + sequence,
                    "candidate",
                    List.of(),
                    Map.of(),
                    List.of(candidate),
                    new ChunkTransitionNote("", "", false)
            );
        }
    }
}
