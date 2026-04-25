package io.quillloom.application.postdraft.review.model;

import java.util.ArrayList;
import java.util.List;

public record FocusAutonomyState(
        int investigationTurns,
        int revisionAttempts,
        int selfCheckFailures,
        List<String> localFailureReasons
) {

    public FocusAutonomyState {
        if (investigationTurns < 0 || revisionAttempts < 0 || selfCheckFailures < 0) {
            throw new IllegalArgumentException("autonomy counters must be >= 0");
        }
        localFailureReasons = localFailureReasons == null ? List.of() : List.copyOf(localFailureReasons);
    }

    public static FocusAutonomyState initial() {
        return new FocusAutonomyState(0, 0, 0, List.of());
    }

    public FocusAutonomyState afterInvestigationTurn() {
        return new FocusAutonomyState(
                investigationTurns + 1,
                revisionAttempts,
                selfCheckFailures,
                List.of()
        );
    }

    public FocusAutonomyState afterRevisionAttempt() {
        return new FocusAutonomyState(
                investigationTurns,
                revisionAttempts + 1,
                selfCheckFailures,
                List.of()
        );
    }

    public FocusAutonomyState afterLocalFailure(String failureReason) {
        if (failureReason == null || failureReason.isBlank()) {
            return this;
        }
        ArrayList<String> merged = new ArrayList<>(localFailureReasons);
        merged.add(failureReason.trim());
        return new FocusAutonomyState(
                investigationTurns,
                revisionAttempts,
                selfCheckFailures,
                List.copyOf(merged)
        );
    }

    public FocusAutonomyState clearLocalFailures() {
        if (localFailureReasons.isEmpty()) {
            return this;
        }
        return new FocusAutonomyState(
                investigationTurns,
                revisionAttempts,
                selfCheckFailures,
                List.of()
        );
    }

    public int consecutiveLocalFailureCount(String failureReason) {
        if (failureReason == null || failureReason.isBlank()) {
            return 0;
        }
        int count = 0;
        for (int index = localFailureReasons.size() - 1; index >= 0; index--) {
            if (!failureReason.equals(localFailureReasons.get(index))) {
                break;
            }
            count++;
        }
        return count;
    }

    public FocusAutonomyState afterSelfCheckFailure(List<String> findings) {
        ArrayList<String> merged = new ArrayList<>(localFailureReasons);
        if (findings != null) {
            merged.addAll(findings);
        }
        return new FocusAutonomyState(
                investigationTurns,
                revisionAttempts,
                selfCheckFailures + 1,
                List.copyOf(merged)
        );
    }
}
