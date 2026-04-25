package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.ReviewGuardrailRejection;
import io.quillloom.application.postdraft.review.model.ReviewToolCall;
import io.quillloom.application.postdraft.review.model.ReviewToolDefinition;

import java.util.Objects;

public class ReviewToolGuardrail {

    public ReviewGuardrailRejection validate(ReviewToolCall call, ReviewToolRegistry registry) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(registry, "registry");
        if (!registry.contains(call.toolName())) {
            return ReviewGuardrailRejection.rejected(call.toolName(), "unregistered_tool");
        }
        ReviewToolDefinition definition = registry.require(call.toolName());
        for (String requiredArgument : definition.requiredArguments()) {
            Object value = call.arguments().get(requiredArgument);
            if (value == null) {
                return ReviewGuardrailRejection.rejected(call.toolName(), "missing_argument:" + requiredArgument);
            }
        }
        return ReviewGuardrailRejection.none();
    }
}
