package io.quillloom.application.postdraft.review.model;

import java.util.Map;

public record ReviewAgentAction(
        String toolName,
        String reason,
        Map<String, String> parameters
) {

    public ReviewAgentAction {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        toolName = toolName.trim();
        reason = reason == null ? "" : reason.trim();
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
