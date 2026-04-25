package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.ReviewToolDecision;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class ReviewToolDecisionContractValidator {

    public Optional<String> validate(ReviewToolDecision decision, ReviewToolRegistry toolRegistry) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(toolRegistry, "toolRegistry");

        if (!toolRegistry.contains(decision.toolName())) {
            return Optional.of("unregistered_tool");
        }

        var definition = toolRegistry.require(decision.toolName());
        Set<String> allowedArguments = definition.allowedArguments();
        for (String argumentName : decision.arguments().keySet()) {
            if (!allowedArguments.contains(argumentName)) {
                return Optional.of("unexpected_argument:" + argumentName);
            }
        }

        for (String requiredArgument : definition.requiredArguments()) {
            if (!decision.arguments().containsKey(requiredArgument) || decision.arguments().get(requiredArgument) == null) {
                return Optional.of("missing_argument:" + requiredArgument);
            }
        }

        for (var schema : definition.argumentSchemas()) {
            if (!decision.arguments().containsKey(schema.name())) {
                continue;
            }
            Object value = decision.arguments().get(schema.name());
            if (schema.validateJsonLikeValue(value).isPresent()) {
                return Optional.of("invalid_argument:" + schema.name());
            }
        }

        if ("request_human_review".equals(decision.toolName())
                && (decision.reason() == null || decision.reason().isBlank())) {
            return Optional.of("invalid_reason");
        }

        return Optional.empty();
    }
}
