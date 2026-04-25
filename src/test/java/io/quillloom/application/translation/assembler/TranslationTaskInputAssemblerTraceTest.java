package io.quillloom.application.translation.assembler;

import io.quillloom.application.preprocess.assembler.PreprocessDossierAssembler;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import io.quillloom.domain.memory.ChapterMemorySnapshot;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.translation.TranslationRuntimeOptions;
import io.quillloom.support.BookAnalysisTestSupport;
import io.quillloom.support.PreprocessTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationTaskInputAssemblerTraceTest {

    @Test
    void shouldRecordSelectedCardsLocalContextAndAssembledInput() {
        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-trace-1",
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
        WorkflowTraceRecorder traceRecorder = new WorkflowTraceRecorder();
        traceRecorder.startRun("run-input-trace-1", "draft-workflow", command.projectId());
        TranslationTaskInputAssembler assembler = new TranslationTaskInputAssembler(new io.quillloom.application.translation.service.RuleBasedKnowledgeCardSelector(), traceRecorder);

        var input = assembler.assemble(
                dossier,
                chunk,
                new ProjectMemorySnapshot(command.projectId(), Map.of("Alice", "Alice-zh"), List.of(), List.of()),
                new ChapterMemorySnapshot("chapter-1", Map.of(), List.of(), List.of("continuity")),
                TranslationRuntimeOptions.defaults()
        );
        var events = traceRecorder.snapshotEvents();

        assertEquals(4, events.size());
        assertEquals("translation_input_assembly_started", events.get(0).eventType());
        assertEquals("knowledge_cards_selected", events.get(1).eventType());
        assertEquals("local_context_built", events.get(2).eventType());
        assertEquals("translation_input_assembled", events.get(3).eventType());
        assertEquals(chunk.chunk().chunkId(), input.sourceMaterial().chunk().chunk().chunkId());
        assertTrue(events.get(3).payload().containsKey("compiledResult"));

        traceRecorder.clear();
    }
}
