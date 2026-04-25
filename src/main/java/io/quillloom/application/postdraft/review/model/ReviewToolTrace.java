package io.quillloom.application.postdraft.review.model;

import java.util.ArrayList;
import java.util.List;

public record ReviewToolTrace(
        String toolName,
        String reason,
        List<String> notes,
        String callSignature
) {

    public ReviewToolTrace(String toolName, String reason, List<String> notes) {
        this(toolName, reason, notes, "");
    }

    public ReviewToolTrace {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        toolName = toolName.trim();
        reason = reason == null ? "" : reason.trim();
        notes = normalize(notes);
        callSignature = callSignature == null ? "" : callSignature.trim();
    }

    private static List<String> normalize(List<String> notes) {
        if (notes == null) {
            return List.of();
        }
        ArrayList<String> normalized = new ArrayList<>();
        for (String note : notes) {
            if (note != null && !note.isBlank()) {
                normalized.add(note.trim());
            }
        }
        return List.copyOf(normalized);
    }
}
