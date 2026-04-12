package io.quillloom.infrastructure.preprocess.bookanalysis;

import java.util.List;

public record BookAnalysisLlmResult(
        String synopsis,
        String narrativeOutline,
        String styleProfile,
        List<String> globalRisks,
        List<String> translationStrategyNotes,
        List<BookAnalysisLlmConstraint> globalConstraints,
        List<RejectedGlobalConstraintTracePayload> rejectedGlobalConstraints
) {

    public BookAnalysisLlmResult(
            String synopsis,
            String narrativeOutline,
            String styleProfile,
            List<String> globalRisks,
            List<String> translationStrategyNotes,
            List<BookAnalysisLlmConstraint> globalConstraints
    ) {
        this(synopsis, narrativeOutline, styleProfile, globalRisks, translationStrategyNotes, globalConstraints, List.of());
    }
}
