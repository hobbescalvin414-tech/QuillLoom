package io.quillloom.infrastructure.preprocess.chunksegmentation;

import io.quillloom.application.preprocess.model.ChunkSegmentationTaskInput;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.CoarseChunkBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSegmentationPromptRendererTest {

    @Test
    void shouldRenderParagraphIndexedPrompt() {
        ChunkSegmentationTaskInput input = new ChunkSegmentationTaskInput(
                "project-1",
                "sample-novel",
                "fr",
                "zh",
                new BookAnalysis("synopsis", "outline", "style", List.of(), List.of()),
                List.of(),
                new CoarseChunkBlock("block-1", 1, 0, 52, "Alice met Bob in Paris.\n\nThey walked toward the river.", "summary", "hint")
        );

        String prompt = new ChunkSegmentationPromptRenderer().render(input);

        assertTrue(prompt.contains("Agent B 细分块规划助手"));
        assertTrue(prompt.contains("段落视图"));
        assertTrue(prompt.contains("paragraphView"));
        assertTrue(prompt.contains("endParagraphIndex"));
        assertTrue(prompt.contains("不要返回原文片段，不要返回 endAnchor"));
        assertTrue(prompt.contains("blockId: block-1"));
        assertTrue(prompt.contains("P1: Alice met Bob in Paris."));
        assertTrue(prompt.contains("P2: They walked toward the river."));
    }

    @Test
    void shouldInstructLocalityAndEstimatedChineseLengthRange() {
        ChunkSegmentationTaskInput input = new ChunkSegmentationTaskInput(
                "project-2",
                "sample-novel",
                "fr",
                "zh",
                new BookAnalysis("synopsis", "outline", "style", List.of(), List.of()),
                List.of(),
                new CoarseChunkBlock("block-2", 1, 0, 120, "Paragraph one.\n\nParagraph two.\n\nParagraph three.", "summary", "hint")
        );

        String prompt = new ChunkSegmentationPromptRenderer().render(input);

        assertTrue(prompt.contains("按情节推进和局部上下文联系来切"));
        assertTrue(prompt.contains("不要仅因为换段就切"));
        assertTrue(prompt.contains("同一连续动作、同一场景观察、同一轮对话往返、同一心理或回忆推进"));
        assertTrue(prompt.contains("中文译文通常应控制在约 500 到 2000 字"));
        assertTrue(prompt.contains("若预估明显低于约 500 字"));
        assertTrue(prompt.contains("若预估明显高于约 2000 字"));
    }
}
