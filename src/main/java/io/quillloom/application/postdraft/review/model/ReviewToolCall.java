package io.quillloom.application.postdraft.review.model;

import java.util.Map;

public record ReviewToolCall(
        String toolName,
        Map<String, Object> arguments,
        String reason
) {

    public ReviewToolCall {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        toolName = toolName.trim();
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        reason = reason == null ? "" : reason.trim();
    }
}
