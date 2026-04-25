package io.quillloom.infrastructure.preprocess.chunksegmentation;

import io.quillloom.application.preprocess.model.ChunkSegmentationTaskInput;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.CoarseChunkBlock;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmChunkSegmentationPlanGeneratorTest {

    @Test
    void shouldDelegateToLlmClientAndReturnValidatedParagraphBoundaries() {
        ChunkSegmentationTaskInput input = new ChunkSegmentationTaskInput(
                "project-1",
                "sample-novel",
                "en",
                "zh",
                new BookAnalysis("synopsis", "outline", "style", List.of(), List.of()),
                List.of(),
                new CoarseChunkBlock("block-1", 1, 0, 82, "Alice met Bob in Paris near the river.\n\nBob warned her that the docks were being watched.", "summary", "hint")
        );
        LlmChunkSegmentationPlanClient client = prompt -> {
            assertTrue(prompt.contains("段落视图"));
            assertTrue(prompt.contains("paragraphView"));
            return new ChunkSegmentationPlanningLlmResult(List.of(
                    new ChunkSegmentationPlanningLlmBoundary(1, "first boundary"),
                    new ChunkSegmentationPlanningLlmBoundary(2, "second boundary")
            ));
        };
        LlmChunkSegmentationPlanGenerator generator = new LlmChunkSegmentationPlanGenerator(
                new ChunkSegmentationPromptRenderer(),
                client,
                new ChunkSegmentationPlanningLlmResultNormalizer()
        );

        var result = generator.generate(input);

        assertEquals(2, result.boundaries().size());
        assertEquals(1, result.boundaries().get(0).endParagraphIndex());
        assertEquals("first boundary", result.boundaries().get(0).boundaryHint());
        assertEquals(2, result.boundaries().get(1).endParagraphIndex());
    }

    @Test
    void shouldRecordPromptRawResponseAndNormalizedTrace() {
        ChunkSegmentationTaskInput input = new ChunkSegmentationTaskInput(
                "project-1",
                "sample-novel",
                "en",
                "zh",
                new BookAnalysis("synopsis", "outline", "style", List.of(), List.of()),
                List.of(),
                new CoarseChunkBlock("block-1", 1, 0, 82, "Alice met Bob in Paris near the river.\n\nBob warned her that the docks were being watched.", "summary", "hint")
        );
        WorkflowTraceRecorder traceRecorder = new WorkflowTraceRecorder();
        traceRecorder.startRun("run-seg-1", "draft-workflow", input.projectId());
        LlmChunkSegmentationPlanClient client = new LlmChunkSegmentationPlanClient() {
            @Override
            public ChunkSegmentationPlanningLlmResult generate(String prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LlmChunkSegmentationPlanClientResponse generateDetailed(String prompt) {
                return new LlmChunkSegmentationPlanClientResponse(
                        "{\"boundaries\":[{\"endParagraphIndex\":1,\"boundaryHint\":\"first\"},{\"endParagraphIndex\":2,\"boundaryHint\":\"second\"}]}",
                        new ChunkSegmentationPlanningLlmResult(List.of(
                                new ChunkSegmentationPlanningLlmBoundary(1, "first"),
                                new ChunkSegmentationPlanningLlmBoundary(2, "second")
                        ))
                );
            }
        };
        LlmChunkSegmentationPlanGenerator generator = new LlmChunkSegmentationPlanGenerator(
                new ChunkSegmentationPromptRenderer(),
                client,
                new ChunkSegmentationPlanningLlmResultNormalizer(),
                traceRecorder
        );

        var result = generator.generate(input);
        var events = traceRecorder.snapshotEvents();

        assertEquals(3, events.size());
        assertEquals("chunk_segmentation_prompt_rendered", events.get(0).eventType());
        assertEquals("chunk_segmentation_llm_responded", events.get(1).eventType());
        assertEquals("chunk_segmentation_normalized", events.get(2).eventType());
        assertTrue(String.valueOf(((java.util.Map<?, ?>) events.get(0).payload().get("prompt")).get("text")).contains("paragraphView"));
        assertTrue(String.valueOf(((java.util.Map<?, ?>) events.get(1).payload().get("rawResponse")).get("text")).contains("first"));
        assertEquals(result.boundaries().size(), ((java.util.List<?>) events.get(2).payload().get("normalizedResult")).size());

        traceRecorder.clear();
    }

    @Test
    void shouldFailFastWhenParagraphBoundaryDoesNotReachFinalParagraph() {
        ChunkSegmentationTaskInput input = new ChunkSegmentationTaskInput(
                "project-1",
                "sample-novel",
                "fr",
                "zh",
                new BookAnalysis("synopsis", "outline", "style", List.of(), List.of()),
                List.of(),
                new CoarseChunkBlock("block-1", 1, 0, 66, "ils avaient donc entre dix-neuf et vingt-cinq ans.\n\nUn autre paragraphe suit.", "summary", "hint")
        );
        LlmChunkSegmentationPlanClient client = prompt -> new ChunkSegmentationPlanningLlmResult(List.of(
                new ChunkSegmentationPlanningLlmBoundary(1, "stops too early")
        ));
        LlmChunkSegmentationPlanGenerator generator = new LlmChunkSegmentationPlanGenerator(
                new ChunkSegmentationPromptRenderer(),
                client,
                new ChunkSegmentationPlanningLlmResultNormalizer()
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> generator.generate(input));

        assertTrue(ex.getMessage().contains("blockId=block-1"));
        assertTrue(ex.getMessage().contains("未覆盖最后一个段落"));
        assertTrue(ex.getMessage().contains("rawBoundaries=[{endParagraphIndex=1"));
    }

    @Test
    void shouldRetryOnceWhenFirstResponseMissesFinalParagraph() {
        ChunkSegmentationTaskInput input = new ChunkSegmentationTaskInput(
                "project-1",
                "sample-novel",
                "fr",
                "zh",
                new BookAnalysis("synopsis", "outline", "style", List.of(), List.of()),
                List.of(),
                new CoarseChunkBlock("block-1", 1, 0, 66, "ils avaient donc entre dix-neuf et vingt-cinq ans.\n\nUn autre paragraphe suit.", "summary", "hint")
        );
        AtomicInteger callCount = new AtomicInteger();
        WorkflowTraceRecorder traceRecorder = new WorkflowTraceRecorder();
        traceRecorder.startRun("run-seg-repair-1", "draft-workflow", input.projectId());

        LlmChunkSegmentationPlanClient client = new LlmChunkSegmentationPlanClient() {
            @Override
            public ChunkSegmentationPlanningLlmResult generate(String prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LlmChunkSegmentationPlanClientResponse generateDetailed(String prompt) {
                if (callCount.getAndIncrement() == 0) {
                    return new LlmChunkSegmentationPlanClientResponse(
                            "{\"boundaries\":[{\"endParagraphIndex\":1,\"boundaryHint\":\"stops too early\"}]}",
                            new ChunkSegmentationPlanningLlmResult(List.of(
                                    new ChunkSegmentationPlanningLlmBoundary(1, "stops too early")
                            ))
                    );
                }
                assertTrue(prompt.contains("未覆盖最后一个段落"));
                return new LlmChunkSegmentationPlanClientResponse(
                        "{\"boundaries\":[{\"endParagraphIndex\":1,\"boundaryHint\":\"first\"},{\"endParagraphIndex\":2,\"boundaryHint\":\"second\"}]}",
                        new ChunkSegmentationPlanningLlmResult(List.of(
                                new ChunkSegmentationPlanningLlmBoundary(1, "first"),
                                new ChunkSegmentationPlanningLlmBoundary(2, "second")
                        ))
                );
            }
        };

        LlmChunkSegmentationPlanGenerator generator = new LlmChunkSegmentationPlanGenerator(
                new ChunkSegmentationPromptRenderer(),
                new ChunkSegmentationRepairPromptRenderer(),
                client,
                new ChunkSegmentationPlanningLlmResultNormalizer(),
                traceRecorder
        );

        var result = generator.generate(input);
        var events = traceRecorder.snapshotEvents();

        assertEquals(2, callCount.get());
        assertEquals(2, result.boundaries().size());
        assertTrue(events.stream().anyMatch(event -> event.eventType().equals("chunk_segmentation_repair_requested")));
        assertTrue(events.stream().anyMatch(event -> event.eventType().equals("chunk_segmentation_repair_succeeded")));
        traceRecorder.clear();
    }

    @Test
    void shouldFailWhenRepairStillMissesFinalParagraph() {
        ChunkSegmentationTaskInput input = new ChunkSegmentationTaskInput(
                "project-1",
                "sample-novel",
                "fr",
                "zh",
                new BookAnalysis("synopsis", "outline", "style", List.of(), List.of()),
                List.of(),
                new CoarseChunkBlock("block-1", 1, 0, 66, "ils avaient donc entre dix-neuf et vingt-cinq ans.\n\nUn autre paragraphe suit.", "summary", "hint")
        );
        LlmChunkSegmentationPlanClient client = new LlmChunkSegmentationPlanClient() {
            @Override
            public ChunkSegmentationPlanningLlmResult generate(String prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LlmChunkSegmentationPlanClientResponse generateDetailed(String prompt) {
                return new LlmChunkSegmentationPlanClientResponse(
                        "{\"boundaries\":[{\"endParagraphIndex\":1,\"boundaryHint\":\"still too early\"}]}",
                        new ChunkSegmentationPlanningLlmResult(List.of(
                                new ChunkSegmentationPlanningLlmBoundary(1, "still too early")
                        ))
                );
            }
        };
        LlmChunkSegmentationPlanGenerator generator = new LlmChunkSegmentationPlanGenerator(
                new ChunkSegmentationPromptRenderer(),
                new ChunkSegmentationRepairPromptRenderer(),
                client,
                new ChunkSegmentationPlanningLlmResultNormalizer()
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> generator.generate(input));

        assertTrue(ex.getMessage().contains("chunk segmentation repair exhausted"));
        assertTrue(ex.getMessage().contains("firstFailure="));
        assertTrue(ex.getMessage().contains("secondFailure="));
    }
}
