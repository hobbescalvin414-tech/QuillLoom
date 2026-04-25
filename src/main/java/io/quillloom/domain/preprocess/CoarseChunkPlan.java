package io.quillloom.domain.preprocess;

import java.util.List;

/**
 * Agent A 产出的全书级粗切分方案。
 * Agent B 后续只在这些粗块内部继续细切分并生成最终标注。
 */
public record CoarseChunkPlan(
        List<CoarseChunkBlock> blocks
) {

    public CoarseChunkPlan {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public static CoarseChunkPlan empty() {
        return new CoarseChunkPlan(List.of());
    }
}