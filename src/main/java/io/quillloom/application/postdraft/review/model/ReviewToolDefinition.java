package io.quillloom.application.postdraft.review.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public record ReviewToolDefinition(
        String toolName,
        String description,
        String whenToUse,
        String whenNotToUse,
        String resultSemantics,
        ToolRepeatPolicy repeatPolicy,
        boolean authoritativeResult,
        String nextStepGuidance,
        Set<String> requiredArguments,
        List<ToolArgumentSchema> argumentSchemas
) {

    public ReviewToolDefinition(String toolName, String description, Set<String> requiredArguments) {
        this(toolName, description, requiredArguments, List.of());
    }

    public ReviewToolDefinition(String toolName,
                                String description,
                                Set<String> requiredArguments,
                                List<ToolArgumentSchema> argumentSchemas) {
        this(toolName,
                description,
                "",
                "",
                "",
                ToolRepeatPolicy.ALLOW,
                false,
                "",
                requiredArguments,
                argumentSchemas);
    }

    public ReviewToolDefinition {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        toolName = toolName.trim();
        description = description.trim();
        whenToUse = normalizeText(whenToUse);
        whenNotToUse = normalizeText(whenNotToUse);
        resultSemantics = normalizeText(resultSemantics);
        repeatPolicy = repeatPolicy == null ? ToolRepeatPolicy.ALLOW : repeatPolicy;
        nextStepGuidance = normalizeText(nextStepGuidance);
        requiredArguments = requiredArguments == null ? Set.of() : Set.copyOf(requiredArguments);
        argumentSchemas = argumentSchemas == null ? List.of() : List.copyOf(argumentSchemas);
        for (String requiredArgument : requiredArguments) {
            if (requiredArgument == null || requiredArgument.isBlank()) {
                throw new IllegalArgumentException("requiredArguments must not contain blank argument");
            }
        }
    }

    public static Builder builder(String toolName, String description) {
        return new Builder(toolName, description);
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    public boolean requiresArgument(String argumentName) {
        return requiredArguments.contains(Objects.requireNonNull(argumentName, "argumentName"));
    }

    public Set<String> allowedArguments() {
        return argumentSchemas.stream()
                .map(ToolArgumentSchema::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    public String renderArgumentsExample() {
        if (argumentSchemas.isEmpty()) {
            return "{}";
        }
        return argumentSchemas.stream()
                .map(schema -> "\"" + schema.name() + "\": " + schema.exampleJsonValue())
                .collect(Collectors.joining(", ", "{", "}"));
    }

    public String renderArgumentRequirements() {
        if (argumentSchemas.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ToolArgumentSchema schema : argumentSchemas) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(schema.render());
        }
        return sb.toString();
    }

    public Optional<ToolArgumentSchema> findArgumentSchema(String argumentName) {
        Objects.requireNonNull(argumentName, "argumentName");
        return argumentSchemas.stream()
                .filter(schema -> schema.name().equals(argumentName))
                .findFirst();
    }

    public static final class Builder {
        private final String toolName;
        private final String description;
        private String whenToUse = "";
        private String whenNotToUse = "";
        private String resultSemantics = "";
        private ToolRepeatPolicy repeatPolicy = ToolRepeatPolicy.ALLOW;
        private boolean authoritativeResult;
        private String nextStepGuidance = "";
        private Set<String> requiredArguments = Set.of();
        private List<ToolArgumentSchema> argumentSchemas = List.of();

        private Builder(String toolName, String description) {
            this.toolName = toolName;
            this.description = description;
        }

        public Builder whenToUse(String value) {
            this.whenToUse = value;
            return this;
        }

        public Builder whenNotToUse(String value) {
            this.whenNotToUse = value;
            return this;
        }

        public Builder resultSemantics(String value) {
            this.resultSemantics = value;
            return this;
        }

        public Builder repeatPolicy(ToolRepeatPolicy value) {
            this.repeatPolicy = value;
            return this;
        }

        public Builder authoritativeResult(boolean value) {
            this.authoritativeResult = value;
            return this;
        }

        public Builder nextStepGuidance(String value) {
            this.nextStepGuidance = value;
            return this;
        }

        public Builder requiredArguments(Set<String> value) {
            this.requiredArguments = value;
            return this;
        }

        public Builder argumentSchemas(List<ToolArgumentSchema> value) {
            this.argumentSchemas = value;
            return this;
        }

        public ReviewToolDefinition build() {
            return new ReviewToolDefinition(
                    toolName,
                    description,
                    whenToUse,
                    whenNotToUse,
                    resultSemantics,
                    repeatPolicy,
                    authoritativeResult,
                    nextStepGuidance,
                    requiredArguments,
                    argumentSchemas
            );
        }
    }
}
