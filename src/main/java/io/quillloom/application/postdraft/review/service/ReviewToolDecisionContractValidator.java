package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.ReviewToolDecision;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class ReviewToolDecisionContractValidator {

    public Optional<String> validate(ReviewToolDecision decision, ReviewToolRegistry toolRegistry) {
        return validate(decision, toolRegistry, ValidationMode.EXECUTABLE);
    }

    public Optional<String> validateNextStepDecision(ReviewToolDecision decision, ReviewToolRegistry toolRegistry) {
        return validate(decision, toolRegistry, ValidationMode.NEXT_STEP);
    }

    public Optional<String> validateExecutableDecision(ReviewToolDecision decision, ReviewToolRegistry toolRegistry) {
        return validate(decision, toolRegistry, ValidationMode.EXECUTABLE);
    }

    private Optional<String> validate(ReviewToolDecision decision,
                                      ReviewToolRegistry toolRegistry,
                                      ValidationMode mode) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(toolRegistry, "toolRegistry");
        Objects.requireNonNull(mode, "mode");

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

        if (mode == ValidationMode.NEXT_STEP && "record_confirmed_terms".equals(decision.toolName())) {
            return validateReasonOnly(decision);
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

        return validateReasonOnly(decision);
    }

    private Optional<String> validateReasonOnly(ReviewToolDecision decision) {
        if ("request_human_review".equals(decision.toolName())
                && (decision.reason() == null || decision.reason().isBlank())) {
            return Optional.of("invalid_reason");
        }
        return Optional.empty();
    }

    private enum ValidationMode {
        NEXT_STEP,
        EXECUTABLE
    }
}
