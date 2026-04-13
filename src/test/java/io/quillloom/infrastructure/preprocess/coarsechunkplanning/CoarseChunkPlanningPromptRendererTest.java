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
    void shouldInstructModelToPreferLargerCoarseBlocksForNormalNarrative() {
        CoarseChunkPlanningTaskInput input = new CoarseChunkPlanningTaskInput(
                "project-2",
                "narrative-sample",
                "Paragraph one.\n\nParagraph two.\n\nParagraph three.",
                "fr",
                "zh"
        );

        String prompt = new CoarseChunkPlanningPromptRenderer().render(input);

        assertTrue(prompt.contains("粗分块默认要尽量大"));
        assertTrue(prompt.contains("不能仅因为换段就切"));
        assertTrue(prompt.contains("block 数量宁少勿多"));
        assertTrue(prompt.contains("只有明确属于结构性短片段时，才允许单段独立成块"));
    }
}
