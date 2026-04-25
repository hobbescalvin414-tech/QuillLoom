package io.quillloom.infrastructure.preprocess.chunksegmentation;

import io.quillloom.application.preprocess.model.ChunkBoundaryPlan;
import io.quillloom.application.preprocess.model.ChunkSegmentationPlanningResult;
import io.quillloom.domain.preprocess.CoarseChunkBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDescriptorCompilerTest {

    @Test
    void shouldCompileParagraphBoundariesIntoStableChunkDescriptors() {
        String blockText = "First chunk ends by the river.\n\nSecond chunk continues inside the old house.";
        CoarseChunkBlock block = new CoarseChunkBlock("block-1", 1, 100, 100 + blockText.length(), blockText, "粗块概括", "粗块提示");
        ChunkSegmentationPlanningResult planningResult = new ChunkSegmentationPlanningResult(List.of(
                new ChunkBoundaryPlan(1, "第一段结束"),
                new ChunkBoundaryPlan(2, "第二段结束")
        ));

        var chunks = new ChunkDescriptorCompiler().compile(block, planningResult);

        assertEquals(2, chunks.size());
        assertEquals("block-1-chunk-1", chunks.get(0).chunkId());
        assertEquals("First chunk ends by the river.", chunks.get(0).sourceText());
        assertEquals("block-1-chunk-2", chunks.get(1).chunkId());
        assertEquals("Second chunk continues inside the old house.", chunks.get(1).sourceText());
        assertEquals("block-1", chunks.get(0).coarseBlockId());
    }

    @Test
    void shouldRejectNonIncreasingParagraphBoundaryWithDiagnostics() {
        CoarseChunkBlock block = new CoarseChunkBlock("block-1", 1, 0, 52, "Alice met Bob in Paris.\n\nThey walked toward the old house.", "粗块概括", "粗块提示");
        ChunkSegmentationPlanningResult planningResult = new ChunkSegmentationPlanningResult(List.of(
                new ChunkBoundaryPlan(1, "第一段结束"),
                new ChunkBoundaryPlan(1, "无效重复段号")
        ));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ChunkDescriptorCompiler().compile(block, planningResult));

        assertTrue(ex.getMessage().contains("must be strictly increasing"));
        assertTrue(ex.getMessage().contains("boundaryIndex=1"));
    }
}