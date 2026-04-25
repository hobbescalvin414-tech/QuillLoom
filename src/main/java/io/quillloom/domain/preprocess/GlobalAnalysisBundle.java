package io.quillloom.domain.preprocess;

import io.quillloom.domain.knowledge.GlobalConstraint;

import java.util.List;

/**
 * Agent A 的全书级分析产物。
 * 这里承接全书分析、全局约束与粗切分方案，但不承载运行态执行字段。
 */
public record GlobalAnalysisBundle(
        BookAnalysis bookAnalysis,
        List<GlobalConstraint> globalConstraints,
        CoarseChunkPlan coarseChunkPlan
) {

    public GlobalAnalysisBundle(BookAnalysis bookAnalysis,
                                List<GlobalConstraint> globalConstraints) {
        this(bookAnalysis, globalConstraints, CoarseChunkPlan.empty());
    }

    public GlobalAnalysisBundle {
        globalConstraints = globalConstraints == null ? List.of() : List.copyOf(globalConstraints);
        coarseChunkPlan = coarseChunkPlan == null ? CoarseChunkPlan.empty() : coarseChunkPlan;
    }
}