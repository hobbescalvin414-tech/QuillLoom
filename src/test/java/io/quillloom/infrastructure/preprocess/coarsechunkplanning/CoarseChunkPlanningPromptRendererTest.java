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
        assertTrue(prompt.contains("后续细分块大约翻译成中文是1000字左右的量"));
        assertTrue(prompt.contains("粗分块的大小以5到10个chunk的量为佳"));
        assertTrue(prompt.contains("不要出现极端大块或极端小块"));
        assertTrue(prompt.contains("注意：粗分块不要过细"));
        assertTrue(prompt.contains("文意的整体性要求高于字数的要求"));
        assertTrue(prompt.contains("summary"));
        assertTrue(prompt.contains("boundaryHint"));
    }

    @Test
    void shouldAllowBreakingSizeAdviceWithoutMechanicalParagraphSplits() {
        CoarseChunkPlanningTaskInput input = new CoarseChunkPlanningTaskInput(
                "project-3",
                "long-sample",
                "Paragraph one.\n\nParagraph two.\n\nParagraph three.\n\nParagraph four.",
                "fr",
                "zh"
        );

        String prompt = new CoarseChunkPlanningPromptRenderer().render(input);

        assertTrue(prompt.contains("根据文本情况，可以打破这条建议"));
        assertTrue(prompt.contains("普通正文只有在章节切换、场景切换、明显时间跳跃、空间跳跃、叙事视角切换时才切出新的 coarse block"));
        assertTrue(prompt.contains("不能仅因为换段就切"));
    }
}
