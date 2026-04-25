package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

import io.quillloom.application.preprocess.model.CoarseChunkBoundaryPlan;
import io.quillloom.application.preprocess.model.CoarseChunkPlanningResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoarseChunkPlanCompilerTest {

    @Test
    void shouldCompileParagraphBoundariesIntoStableCoarseChunkPlan() {
        String sourceText = "First chapter begins in Paris by the river.\n\nSecond chapter moves into the old house.";
        CoarseChunkPlanningResult planningResult = new CoarseChunkPlanningResult(List.of(
                new CoarseChunkBoundaryPlan(1, "first coarse summary", "end first block before scene shift"),
                new CoarseChunkBoundaryPlan(2, "second coarse summary", "last block to end")
        ));

        var plan = new CoarseChunkPlanCompiler().compile(sourceText, planningResult);

        assertEquals(2, plan.blocks().size());
        assertEquals("block-1", plan.blocks().get(0).blockId());
        assertEquals("First chapter begins in Paris by the river.", plan.blocks().get(0).sourceText());
        assertEquals("first coarse summary", plan.blocks().get(0).summary());
        assertEquals("block-2", plan.blocks().get(1).blockId());
        assertEquals("Second chapter moves into the old house.", plan.blocks().get(1).sourceText());
        assertEquals("second coarse summary", plan.blocks().get(1).summary());
    }

    @Test
    void shouldRejectNonIncreasingParagraphBoundaryWithDiagnostics() {
        String sourceText = "Alice met Bob in Paris.\n\nThey walked toward the old house.";
        CoarseChunkPlanningResult planningResult = new CoarseChunkPlanningResult(List.of(
                new CoarseChunkBoundaryPlan(1, "summary", "hint"),
                new CoarseChunkBoundaryPlan(1, "summary-2", "hint-2")
        ));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new CoarseChunkPlanCompiler().compile(sourceText, planningResult));

        assertTrue(ex.getMessage().contains("must be strictly increasing"));
        assertTrue(ex.getMessage().contains("boundaryIndex=1"));
    }
}