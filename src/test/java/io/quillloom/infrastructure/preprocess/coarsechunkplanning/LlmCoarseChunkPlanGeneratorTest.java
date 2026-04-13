package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

import io.quillloom.application.preprocess.model.CoarseChunkPlanningTaskInput;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmCoarseChunkPlanGeneratorTest {

    @Test
    void shouldDelegateToLlmClientAndReturnValidatedParagraphBoundaries() {
        CoarseChunkPlanningTaskInput input = new CoarseChunkPlanningTaskInput(
                "project-1",
                "sample-novel",
                "Alice met Bob in Paris near the river.\n\nBob warned her that the docks were being watched.",
                "en",
                "zh"
        );
        LlmCoarseChunkPlanClient client = prompt -> {
            assertTrue(prompt.contains("段落视图"));
            assertTrue(prompt.contains("paragraphView"));
            return new CoarseChunkPlanningLlmResult(List.of(
                    new CoarseChunkPlanningLlmBoundary(1, "first summary", "end of riverside scene"),
                    new CoarseChunkPlanningLlmBoundary(2, "second summary", "cover to end")
            ));
        };
        LlmCoarseChunkPlanGenerator generator = new LlmCoarseChunkPlanGenerator(
                new CoarseChunkPlanningPromptRenderer(),
                new CoarseChunkPlanningRepairPromptRenderer(),
                client,
                new CoarseChunkPlanningLlmResultNormalizer()
        );

        var result = generator.generate(input);

        assertEquals(2, result.boundaries().size());
        assertEquals(1, result.boundaries().get(0).endParagraphIndex());
        assertEquals("first summary", result.boundaries().get(0).summary());
        assertEquals("end of riverside scene", result.boundaries().get(0).boundaryHint());
        assertEquals(2, result.boundaries().get(1).endParagraphIndex());
    }

    @Test
    void shouldRecordPromptRawResponseAndNormalizedTrace() {
        CoarseChunkPlanningTaskInput input = new CoarseChunkPlanningTaskInput(
                "project-1",
                "sample-novel",
                "Alice met Bob in Paris near the river.\n\nBob warned her that the docks were being watched.",
                "en",
                "zh"
        );
        WorkflowTraceRecorder traceRecorder = new WorkflowTraceRecorder();
        traceRecorder.startRun("run-coarse-1", "draft-workflow", input.projectId());
        LlmCoarseChunkPlanClient client = new LlmCoarseChunkPlanClient() {
            @Override
            public CoarseChunkPlanningLlmResult generate(String prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LlmCoarseChunkPlanClientResponse generateDetailed(String prompt) {
                return new LlmCoarseChunkPlanClientResponse(
                        "{\"boundaries\":[{\"endParagraphIndex\":1,\"summary\":\"first summary\",\"boundaryHint\":\"hint\"},{\"endParagraphIndex\":2,\"summary\":\"second summary\",\"boundaryHint\":\"hint-2\"}]}",
                        new CoarseChunkPlanningLlmResult(List.of(
                                new CoarseChunkPlanningLlmBoundary(1, "first summary", "hint"),
                                new CoarseChunkPlanningLlmBoundary(2, "second summary", "hint-2")
                        )),
                        300
                );
            }
        };
        LlmCoarseChunkPlanGenerator generator = new LlmCoarseChunkPlanGenerator(
                new CoarseChunkPlanningPromptRenderer(),
                new CoarseChunkPlanningRepairPromptRenderer(),
                client,
                new CoarseChunkPlanningLlmResultNormalizer(),
                traceRecorder
        );

        var result = generator.generate(input);
        var events = traceRecorder.snapshotEvents();

        assertEquals(3, events.size());
        assertEquals("coarse_planning_prompt_rendered", events.get(0).eventType());
        assertEquals("coarse_planning_llm_responded", events.get(1).eventType());
        assertEquals("coarse_planning_normalized", events.get(2).eventType());
        assertTrue(String.valueOf(((Map<?, ?>) events.get(0).payload().get("prompt")).get("text")).contains("paragraphView"));
        assertEquals(300, events.get(1).payload().get("timeoutSeconds"));
        assertTrue(String.valueOf(((Map<?, ?>) events.get(1).payload().get("rawResponse")).get("text")).contains("first summary"));
        assertEquals(result.boundaries().size(), ((List<?>) events.get(2).payload().get("normalizedResult")).size());

        traceRecorder.clear();
    }

    @Test
    void shouldRetryOnceWhenFirstBoundarySequenceIsInvalidAndRepairSucceeds() {
        CoarseChunkPlanningTaskInput input = new CoarseChunkPlanningTaskInput(
                "project-1",
                "sample-novel",
                "P1.\n\nP2.\n\nP3.",
                "en",
                "zh"
        );
        WorkflowTraceRecorder traceRecorder = new WorkflowTraceRecorder();
        traceRecorder.startRun("run-coarse-repair-1", "draft-workflow", input.projectId());
        AtomicInteger callCount = new AtomicInteger();

        LlmCoarseChunkPlanClient client = new LlmCoarseChunkPlanClient() {
            @Override
            public CoarseChunkPlanningLlmResult generate(String prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LlmCoarseChunkPlanClientResponse generateDetailed(String prompt) {
                if (callCount.getAndIncrement() == 0) {
                    return new LlmCoarseChunkPlanClientResponse(
                            "{\"boundaries\":[{\"endParagraphIndex\":2,\"summary\":\"a\",\"boundaryHint\":\"x\"},{\"endParagraphIndex\":1,\"summary\":\"b\",\"boundaryHint\":\"y\"}]}",
                            new CoarseChunkPlanningLlmResult(List.of(
                                    new CoarseChunkPlanningLlmBoundary(2, "a", "x"),
                                    new CoarseChunkPlanningLlmBoundary(1, "b", "y")
                            )),
                            120
                    );
                }
                assertTrue(prompt.contains("上一次 coarse chunk planning 输出失败"));
                assertTrue(prompt.contains("endParagraphIndex"));
                return new LlmCoarseChunkPlanClientResponse(
                        "{\"boundaries\":[{\"endParagraphIndex\":3,\"summary\":\"merged\",\"boundaryHint\":\"cover to end\"}]}",
                        new CoarseChunkPlanningLlmResult(List.of(
                                new CoarseChunkPlanningLlmBoundary(3, "merged", "cover to end")
                        )),
                        120
                );
            }
        };

        LlmCoarseChunkPlanGenerator generator = new LlmCoarseChunkPlanGenerator(
                new CoarseChunkPlanningPromptRenderer(),
                new CoarseChunkPlanningRepairPromptRenderer(),
                client,
                new CoarseChunkPlanningLlmResultNormalizer(),
                traceRecorder
        );

        var result = generator.generate(input);
        var events = traceRecorder.snapshotEvents();

        assertEquals(1, result.boundaries().size());
        assertEquals(7, events.size());
        assertEquals("coarse_planning_llm_responded", events.get(1).eventType());
        assertEquals("coarse_planning_llm_failed", events.get(2).eventType());
        assertEquals("coarse_planning_repair_requested", events.get(3).eventType());
        assertEquals("coarse_planning_repair_llm_responded", events.get(4).eventType());
        assertEquals("coarse_planning_normalized", events.get(5).eventType());
        assertEquals("coarse_planning_repair_succeeded", events.get(6).eventType());
    }

    @Test
    void shouldFailAfterRepairAttemptIsExhausted() {
        CoarseChunkPlanningTaskInput input = new CoarseChunkPlanningTaskInput(
                "project-1",
                "sample-novel",
                "P1.\n\nP2.\n\nP3.",
                "en",
                "zh"
        );
        AtomicInteger callCount = new AtomicInteger();

        LlmCoarseChunkPlanClient client = new LlmCoarseChunkPlanClient() {
            @Override
            public CoarseChunkPlanningLlmResult generate(String prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LlmCoarseChunkPlanClientResponse generateDetailed(String prompt) {
                callCount.incrementAndGet();
                return new LlmCoarseChunkPlanClientResponse(
                        "{\"boundaries\":[{\"endParagraphIndex\":2,\"summary\":\"a\",\"boundaryHint\":\"x\"},{\"endParagraphIndex\":1,\"summary\":\"b\",\"boundaryHint\":\"y\"}]}",
                        new CoarseChunkPlanningLlmResult(List.of(
                                new CoarseChunkPlanningLlmBoundary(2, "a", "x"),
                                new CoarseChunkPlanningLlmBoundary(1, "b", "y")
                        )),
                        120
                );
            }
        };

        LlmCoarseChunkPlanGenerator generator = new LlmCoarseChunkPlanGenerator(
                new CoarseChunkPlanningPromptRenderer(),
                new CoarseChunkPlanningRepairPromptRenderer(),
                client,
                new CoarseChunkPlanningLlmResultNormalizer()
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> generator.generate(input));

        assertEquals(2, callCount.get());
        assertTrue(ex.getMessage().contains("coarse chunk planning repair exhausted"));
        assertTrue(ex.getMessage().contains("attempts=2"));
    }

    @Test
    void shouldFailFastWhenParagraphBoundaryDoesNotReachFinalParagraph() {
        CoarseChunkPlanningTaskInput input = new CoarseChunkPlanningTaskInput(
                "project-1",
                "sample-novel",
                "Editions Gallimard, 2007.\n\nA la moitie du chemin.",
                "fr",
                "zh"
        );
        LlmCoarseChunkPlanClient client = prompt -> new CoarseChunkPlanningLlmResult(List.of(
                new CoarseChunkPlanningLlmBoundary(1, "summary", "stops too early")
        ));
        LlmCoarseChunkPlanGenerator generator = new LlmCoarseChunkPlanGenerator(
                new CoarseChunkPlanningPromptRenderer(),
                new CoarseChunkPlanningRepairPromptRenderer(),
                client,
                new CoarseChunkPlanningLlmResultNormalizer()
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> generator.generate(input));

        assertTrue(ex.getMessage().contains("未覆盖最后一个段落"));
        assertTrue(ex.getMessage().contains("lastParagraphIndex=2"));
        assertTrue(ex.getMessage().contains("rawBoundaries=[{endParagraphIndex=1"));
    }
}
