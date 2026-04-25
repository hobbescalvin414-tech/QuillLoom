package io.quillloom.infrastructure.preprocess.chunkannotation;

import io.quillloom.application.preprocess.model.ChunkAnnotationTaskInput;
import io.quillloom.domain.preprocess.PersonAliasHint;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class ChunkAnnotationLlmResultNormalizer {

    private static final int MAX_ENTITY_COUNT = 12;

    public ChunkAnnotationLlmResult normalize(ChunkAnnotationTaskInput input,
                                                    ChunkAnnotationLlmResult result) {
        ChunkAnnotationLlmResult source = result == null
                ? new ChunkAnnotationLlmResult(null, null, null, null, null, null)
                : result;

        return new ChunkAnnotationLlmResult(
                normalizeSummary(input, source.summary()),
                normalizeList(source.entities(), MAX_ENTITY_COUNT),
                normalizeList(source.backgroundQuestions(), Integer.MAX_VALUE),
                normalizeList(source.translationRisks(), Integer.MAX_VALUE),
                normalizeList(source.keyExpressions(), Integer.MAX_VALUE),
                normalizeAliasHints(source.personAliasHints())
        );
    }

    private String normalizeSummary(ChunkAnnotationTaskInput input, String summary) {
        String normalized = summary == null ? "" : summary.trim();
        if (!normalized.isBlank()) {
            return normalized;
        }

        String sourceText = input.chunk().sourceText() == null ? "" : input.chunk().sourceText();
        String compact = sourceText.replaceAll("\\s+", " ").trim();
        if (compact.isBlank()) {
            return "[缺少摘要]";
        }
        return compact.substring(0, Math.min(compact.length(), 120));
    }

    private List<String> normalizeList(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isBlank()) {
                normalized.add(trimmed);
            }
            if (normalized.size() >= limit) {
                break;
            }
        }
        return List.copyOf(normalized);
    }

    private List<PersonAliasHint> normalizeAliasHints(List<PersonAliasHint> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        Set<String> dedup = new LinkedHashSet<>();
        java.util.ArrayList<PersonAliasHint> normalized = new java.util.ArrayList<>();
        for (PersonAliasHint value : values) {
            if (value == null) {
                continue;
            }
            List<String> surfaceForms = normalizeList(value.surfaceForms(), 4);
            if (surfaceForms.size() < 2) {
                continue;
            }
            String hintType = value.hintType() == null ? "" : value.hintType().trim();
            String confidence = value.confidence() == null ? "" : value.confidence().trim();
            String evidence = value.evidence() == null ? "" : value.evidence().trim();
            String key = surfaceForms + "|" + hintType + "|" + confidence;
            if (dedup.add(key)) {
                normalized.add(new PersonAliasHint(surfaceForms, hintType, confidence, evidence));
            }
        }
        return List.copyOf(normalized);
    }
}
