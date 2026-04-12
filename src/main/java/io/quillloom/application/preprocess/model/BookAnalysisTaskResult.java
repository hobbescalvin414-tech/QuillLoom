package io.quillloom.application.preprocess.model;

import io.quillloom.domain.knowledge.GlobalConstraint;
import io.quillloom.domain.preprocess.BookAnalysis;

import java.util.List;
import java.util.Map;

public record BookAnalysisTaskResult(
        BookAnalysis bookAnalysis,
        List<GlobalConstraint> globalConstraints,
        Map<String, Object> tracePayload
) {

    public BookAnalysisTaskResult(BookAnalysis bookAnalysis, List<GlobalConstraint> globalConstraints) {
        this(bookAnalysis, globalConstraints, Map.of());
    }

    public BookAnalysisTaskResult {
        globalConstraints = globalConstraints == null ? List.of() : List.copyOf(globalConstraints);
        tracePayload = tracePayload == null ? Map.of() : Map.copyOf(tracePayload);
    }
}
