package io.quillloom.application.postdraft.review.model;

import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ToolArgumentSchema(
        String name,
        String type,
        boolean required,
        String description
) {

    public ToolArgumentSchema {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        Objects.requireNonNull(description, "description");
        name = name.trim();
        type = type.trim();
    }

    public String render() {
        String requiredMark = required ? "required" : "optional";
        return "%s (%s, %s): %s; example=%s".formatted(name, type, requiredMark, description, exampleJsonValue());
    }

    public String exampleJsonValue() {
        return switch (type) {
            case "integer" -> "1";
            case "string" -> "\"...\"";
            case "string[]" -> "[\"...\"]";
            case "object{string:string}" -> "{\"<source-term>\":\"<target-term>\"}";
            default -> "\"...\"";
        };
    }

    public String schemaDescription() {
        return description + "; example=" + exampleJsonValue();
    }

    public Optional<String> validateJsonLikeValue(Object value) {
        return switch (type) {
            case "integer" -> validatePositiveInteger(value);
            case "string" -> validateString(value);
            case "string[]" -> validateStringList(value);
            case "object{string:string}" -> validateStringMap(value);
            default -> Optional.empty();
        };
    }

    private Optional<String> validatePositiveInteger(Object value) {
        if (!(value instanceof Number number) || number.intValue() <= 0) {
            return Optional.of("invalid");
        }
        return Optional.empty();
    }

    private Optional<String> validateString(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return Optional.of("invalid");
        }
        return Optional.empty();
    }

    private Optional<String> validateStringList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return Optional.of("invalid");
        }
        boolean allStrings = list.stream().allMatch(item -> item instanceof String text && !text.isBlank());
        return allStrings ? Optional.empty() : Optional.of("invalid");
    }

    private Optional<String> validateStringMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
            return Optional.of("invalid");
        }
        if (rawMap.containsKey("sourceTerm") || rawMap.containsKey("targetTerm")) {
            return Optional.of("invalid");
        }
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                return Optional.of("invalid");
            }
            if (!(entry.getValue() instanceof String stringValue) || stringValue.isBlank()) {
                return Optional.of("invalid");
            }
        }
        return Optional.empty();
    }
}
