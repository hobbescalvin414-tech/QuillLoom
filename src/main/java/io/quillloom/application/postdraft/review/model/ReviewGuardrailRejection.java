package io.quillloom.application.postdraft.review.model;

public record ReviewGuardrailRejection(
        boolean rejected,
        String toolName,
        String rejectionReason
) {

    public ReviewGuardrailRejection {
        toolName = toolName == null ? "" : toolName.trim();
        rejectionReason = rejectionReason == null ? "" : rejectionReason.trim();
        if (rejected && toolName.isBlank()) {
            throw new IllegalArgumentException("rejected guardrail result requires toolName");
        }
        if (rejected && rejectionReason.isBlank()) {
            throw new IllegalArgumentException("rejected guardrail result requires rejectionReason");
        }
    }

    public static ReviewGuardrailRejection none() {
        return new ReviewGuardrailRejection(false, "", "");
    }

    public static ReviewGuardrailRejection rejected(String toolName, String rejectionReason) {
        return new ReviewGuardrailRejection(true, toolName, rejectionReason);
    }
}
