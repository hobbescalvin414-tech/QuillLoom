package io.quillloom.application.postdraft.review.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record ToolCallSignature(
        String toolName,
        String key,
        String display
) {

    public ToolCallSignature {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (display == null || display.isBlank()) {
            throw new IllegalArgumentException("display must not be blank");
        }
        toolName = toolName.trim();
        key = key.trim();
        display = display.trim();
    }

    public static ToolCallSignature forReadConfirmedTerms(List<String> sourceTerms) {
        Map<String, String> displayByKey = new LinkedHashMap<>();
        for (String sourceTerm : Objects.requireNonNullElse(sourceTerms, List.<String>of())) {
            if (sourceTerm == null || sourceTerm.isBlank()) {
                continue;
            }
            String displayTerm = sourceTerm.trim();
            String keyTerm = displayTerm.toLowerCase(Locale.ROOT);
            displayByKey.putIfAbsent(keyTerm, displayTerm);
        }
        if (displayByKey.isEmpty()) {
            throw new IllegalArgumentException("read_confirmed_terms sourceTerms must not be blank");
        }

        ArrayList<String> sortedKeys = new ArrayList<>(displayByKey.keySet());
        sortedKeys.sort(String::compareTo);
        List<String> sortedDisplays = sortedKeys.stream()
                .map(displayByKey::get)
                .toList();
        String key = "read_confirmed_terms:sourceTerms=[" + String.join(", ", sortedKeys) + "]";
        String display = "read_confirmed_terms sourceTerms=[" + String.join(", ", sortedDisplays) + "]";
        return new ToolCallSignature("read_confirmed_terms", key, display);
    }
}
