package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

import io.quillloom.application.preprocess.model.CoarseChunkPlanningTaskInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CoarseChunkPlanningPromptRendererTest {

    @Test
    void shouldRenderParagraphIndexedPrompt() {
        CoarseChunkPlanningTaskInput input = new CoarseChunkPlanningTaskInput(
                "project-1",
                "sample-novel",
                "Alice met Bob in Paris.\n\nThey walked toward the river.",
                "en",
                "zh"
        );

        String prompt = new CoarseChunkPlanningPromptRenderer().render(input);

        assertTrue(prompt.contains("Agent A"));
        assertTrue(prompt.contains("paragraphView"));
        assertTrue(prompt.contains("endParagraphIndex"));
        assertTrue(prompt.contains("P1: Alice met Bob in Paris."));
        assertTrue(prompt.contains("P2: They walked toward the river."));
        assertTrue(prompt.contains("title: sample-novel"));
    }

    @Test
    void shouldBiasCoarsePlanningTowardFewerLargerBlocksWithoutOverShortSummaries() {
        CoarseChunkPlanningTaskInput input = new CoarseChunkPlanningTaskInput(
                "project-2",
                "narrative-sample",
                "Paragraph one.\n\nParagraph two.\n\nParagraph three.",
                "fr",
                "zh"
        );

        String prompt = new CoarseChunkPlanningPromptRenderer().render(input);

        assertTrue(prompt.contains("粗分块默认要明显偏大"));
        assertTrue(prompt.contains("优先减少 coarse block 数量"));
        assertTrue(prompt.contains("宁可少切，也不要把整本书切成很长的 coarse block 列表"));
        assertTrue(prompt.contains("不能仅因为换段就切"));
        assertTrue(prompt.contains("summary 是该粗块的简短概括"));
        assertTrue(prompt.contains("1 句完整概括"));
        assertTrue(prompt.contains("20 到 80 个中文字符"));
        assertTrue(prompt.contains("不能短到只剩标签词"));
        assertTrue(prompt.contains("boundaryHint 说明为什么在这里切，但要简洁"));
    }
}
