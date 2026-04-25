package io.quillloom.application.postdraft.review.model;

import java.util.List;

public record RevisionSelfCheckResult(
        boolean passed,
        String stopReason,
        List<String> findings
) {

    public RevisionSelfCheckResult {
        stopReason = stopReason == null ? "" : stopReason.trim();
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
