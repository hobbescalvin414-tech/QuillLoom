package io.quillloom.application.postdraft.review.model;

import java.util.List;

public record FocusReviewDiagnostics(
        int revisionAttemptCount,
        int selfCheckFailureCount,
        List<String> localRejectionReasons
) {

    public FocusReviewDiagnostics {
        if (revisionAttemptCount < 0 || selfCheckFailureCount < 0) {
            throw new IllegalArgumentException("diagnostic counters must be >= 0");
        }
        localRejectionReasons = localRejectionReasons == null ? List.of() : List.copyOf(localRejectionReasons);
    }

    public static FocusReviewDiagnostics empty() {
        return new FocusReviewDiagnostics(0, 0, List.of());
    }

    public FocusReviewDiagnostics appendLocalRejection(String rejectionReason) {
        if (rejectionReason == null || rejectionReason.isBlank()) {
            return this;
        }
        java.util.ArrayList<String> updated = new java.util.ArrayList<>(localRejectionReasons);
        updated.add(rejectionReason.trim());
        return new FocusReviewDiagnostics(revisionAttemptCount, selfCheckFailureCount, updated);
    }

    public FocusReviewDiagnostics clearLocalRejections() {
        if (localRejectionReasons.isEmpty()) {
            return this;
        }
        return new FocusReviewDiagnostics(revisionAttemptCount, selfCheckFailureCount, List.of());
    }

    public int consecutiveLocalFailureCount(String rejectionReason) {
        if (rejectionReason == null || rejectionReason.isBlank() || localRejectionReasons.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int index = localRejectionReasons.size() - 1; index >= 0; index--) {
            if (!rejectionReason.equals(localRejectionReasons.get(index))) {
                break;
            }
            count++;
        }
        return count;
    }
}
