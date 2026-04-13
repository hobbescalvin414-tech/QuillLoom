package io.quillloom.infrastructure.preprocess.chunkannotation;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.model.ChunkAnnotationTaskInput;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import io.quillloom.domain.knowledge.GlobalConstraint;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import io.quillloom.domain.preprocess.PersonAliasHint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmChunkAnnotationGeneratorTest {

    @Test
    void shouldDelegateToLlmClientAndReuseNormalizationChain() {
        ChunkAnnotationTaskInput input = createTaskInput();
        LlmChunkAnnotationClient client = prompt -> {
            assertTrue(prompt.contains("请只输出一个 JSON 对象"));
            return new ChunkAnnotationLlmResult("   ", List.of(" Alice ", "Alice"), null, null, null, null);
        };
        LlmChunkAnnotationGenerator generator = new LlmChunkAnnotationGenerator(
                new ChunkAnnotationPromptRenderer(),
                new ChunkAnnotationRepairPromptRenderer(),
                client,
                new ChunkAnnotationLlmResultNormalizer(),
                new ChunkAnnotationLlmResultParser()
        );

        var annotation = generator.generate(input);

        assertEquals(input.chunk(), annotation.chunk());
        assertFalse(annotation.summary().isBlank());
        assertEquals(List.of("Alice"), annotation.entities());
        assertEquals(List.of(), annotation.backgroundQuestions());
        assertEquals(List.of(), annotation.translationRisks());
        assertEquals(List.of(), annotation.keyExpressions());
        assertEquals(List.of(), annotation.personAliasHints());
    }

    @Test
    void shouldRecordPromptRawResponseNormalizedAndCompiledTrace() {
        ChunkAnnotationTaskInput input = createTaskInput();
        WorkflowTraceRecorder traceRecorder = new WorkflowTraceRecorder();
        traceRecorder.startRun("run-annotation-1", "draft-workflow", input.projectId());
        LlmChunkAnnotationClient client = new LlmChunkAnnotationClient() {
            @Override
            public ChunkAnnotationLlmResult generate(String prompt) {
                throw new UnsupportedOperationException("generate should not be called when detailed response is available");
            }

            @Override
            public ChunkAnnotationLlmClientResponse generateDetailed(String prompt) {
                return new ChunkAnnotationLlmClientResponse(
                        "{\"summary\":\" Alice arrives \",\"entities\":[\" Alice \",\"Bob\"]}",
                        new ChunkAnnotationLlmResult(
                                " Alice arrives ",
                                List.of(" Alice ", "Bob"),
                                List.of(" who is Bob? "),
                                List.of(" tone shift "),
                                List.of(" old house "),
                                List.of(new PersonAliasHint(
                                        List.of("Bowling", "le Capitaine"),
                                        "same-person-name-variant",
                                        "HIGH",
                                        "同段交替出现"
                                ))
                        )
                );
            }
        };
        LlmChunkAnnotationGenerator generator = new LlmChunkAnnotationGenerator(
                new ChunkAnnotationPromptRenderer(),
                new ChunkAnnotationRepairPromptRenderer(),
                client,
                new ChunkAnnotationLlmResultNormalizer(),
                new ChunkAnnotationLlmResultParser(),
                traceRecorder
        );

        var annotation = generator.generate(input);
        var events = traceRecorder.snapshotEvents();

        assertEquals(4, events.size());
        assertEquals("chunk_annotation_prompt_rendered", events.get(0).eventType());
        assertEquals("chunk_annotation_llm_responded", events.get(1).eventType());
        assertEquals("chunk_annotation_normalized", events.get(2).eventType());
        assertEquals("chunk_annotation_completed", events.get(3).eventType());
        assertEquals("chunk-1", events.get(3).chunkId());
        assertTrue(readText(events.get(0).payload(), "prompt").contains("Alice met Bob in Paris"));
        assertTrue(readText(events.get(1).payload(), "rawResponse").contains("Alice arrives"));
        assertEquals("Alice arrives", readMap(events.get(2).payload(), "normalizedResult").get("summary"));
        assertEquals(annotation.summary(), readMap(events.get(3).payload(), "compiledResult").get("summary"));

        traceRecorder.clear();
    }

    @Test
    void shouldRetryOnceWhenFirstStructuredOutputFailsAndRepairSucceeds() {
        ChunkAnnotationTaskInput input = createTaskInput();
        WorkflowTraceRecorder traceRecorder = new WorkflowTraceRecorder();
        traceRecorder.startRun("run-annotation-repair-1", "draft-workflow", input.projectId());
        AtomicInteger callCount = new AtomicInteger();

        LlmChunkAnnotationClient client = new LlmChunkAnnotationClient() {
            @Override
            public ChunkAnnotationLlmResult generate(String prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ChunkAnnotationLlmClientResponse generateDetailed(String prompt) {
                if (callCount.getAndIncrement() == 0) {
                    throw new ChunkAnnotationStructuredOutputException(
                            "invalid-json",
                            "translationRisks[0] 字符串未闭合",
                            "{\"summary\":\"broken\",\"translationRisks\":[\"too long",
                            true,
                            null
                    );
                }
                assertTrue(prompt.contains("上一次输出失败"));
                assertTrue(prompt.contains("translationRisks[0] 字符串未闭合"));
                return new ChunkAnnotationLlmClientResponse(
                        "{\"summary\":\"Alice arrives\",\"entities\":[\"Alice\"],\"backgroundQuestions\":[],\"translationRisks\":[\"tone shift\"],\"keyExpressions\":[],\"personAliasHints\":[]}",
                        new ChunkAnnotationLlmResult(
                                "Alice arrives",
                                List.of("Alice"),
                                List.of(),
                                List.of("tone shift"),
                                List.of(),
                                List.of()
                        )
                );
            }
        };

        LlmChunkAnnotationGenerator generator = new LlmChunkAnnotationGenerator(
                new ChunkAnnotationPromptRenderer(),
                new ChunkAnnotationRepairPromptRenderer(),
                client,
                new ChunkAnnotationLlmResultNormalizer(),
                new ChunkAnnotationLlmResultParser(),
                traceRecorder
        );

        var annotation = generator.generate(input);
        var events = traceRecorder.snapshotEvents();

        assertEquals("Alice arrives", annotation.summary());
        assertEquals(6, events.size());
        assertEquals("chunk_annotation_llm_failed", events.get(1).eventType());
        assertEquals("chunk_annotation_repair_requested", events.get(2).eventType());
        assertEquals("chunk_annotation_repair_llm_responded", events.get(3).eventType());
        assertEquals("chunk_annotation_normalized", events.get(4).eventType());
        assertEquals("chunk_annotation_completed", events.get(5).eventType());
    }

    @Test
    void shouldFailAfterRepairAttemptIsExhausted() {
        ChunkAnnotationTaskInput input = createTaskInput();
        WorkflowTraceRecorder traceRecorder = new WorkflowTraceRecorder();
        traceRecorder.startRun("run-annotation-repair-2", "draft-workflow", input.projectId());
        AtomicInteger callCount = new AtomicInteger();

        LlmChunkAnnotationClient client = new LlmChunkAnnotationClient() {
            @Override
            public ChunkAnnotationLlmResult generate(String prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ChunkAnnotationLlmClientResponse generateDetailed(String prompt) {
                callCount.incrementAndGet();
                throw new ChunkAnnotationStructuredOutputException(
                        "invalid-json",
                        "summary 字符串未闭合",
                        "{\"summary\":\"broken",
                        true,
                        null
                );
            }
        };

        LlmChunkAnnotationGenerator generator = new LlmChunkAnnotationGenerator(
                new ChunkAnnotationPromptRenderer(),
                new ChunkAnnotationRepairPromptRenderer(),
                client,
                new ChunkAnnotationLlmResultNormalizer(),
                new ChunkAnnotationLlmResultParser(),
                traceRecorder
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> generator.generate(input));

        assertEquals(2, callCount.get());
        assertTrue(ex.getMessage().contains("chunk annotation repair exhausted"));
        assertTrue(ex.getMessage().contains("attempts=2"));
        assertTrue(ex.getMessage().contains("summary 字符串未闭合"));

        var events = traceRecorder.snapshotEvents();
        assertEquals("chunk_annotation_repair_failed", events.get(3).eventType());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Map<String, Object> payload, String key) {
        return (Map<String, Object>) payload.get(key);
    }

    private String readText(Map<String, Object> payload, String key) {
        return String.valueOf(readMap(payload, key).get("text"));
    }

    private ChunkAnnotationTaskInput createTaskInput() {
        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-1",
                "示例小说",
                "Alice met Bob in Paris near the river.",
                "en",
                "zh"
        );
        return new ChunkAnnotationTaskInput(
                command.projectId(),
                command.title(),
                command.sourceLanguage(),
                command.targetLanguage(),
                new BookAnalysis("全书摘要", "叙事结构", "冷静克制", List.of(), List.of()),
                List.of(new GlobalConstraint("c1", "style", "保持一致")),
                new ChunkDescriptor("chunk-1", 1, "block-1", 0, command.sourceText().length(), command.sourceText())
        );
    }
}
