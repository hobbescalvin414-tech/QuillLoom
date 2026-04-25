package io.quillloom.application.postdraft.review.model;

import java.util.List;
import java.util.Set;

public record ReviewProcessSummary(
        String projectId,
        ReviewFocus focus,
        ReviewStrategy strategy,
        Set<ReviewProblemType> problemTypes,
        List<String> evidenceSummaries,
        String processNote
) {

    public ReviewProcessSummary {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (focus == null) {
            throw new IllegalArgumentException("focus must not be null");
        }
        problemTypes = problemTypes == null ? Set.of() : Set.copyOf(problemTypes);
        evidenceSummaries = evidenceSummaries == null ? List.of() : List.copyOf(evidenceSummaries);
        strategy = strategy == null ? ReviewStrategy.KEEP : strategy;
    }
}
