package io.quillloom.infrastructure.preprocess.bookanalysis;

import io.quillloom.application.preprocess.model.BookAnalysisTaskInput;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 对 LLM 返回的全书分析结果做兜底与规范化，避免空字段直接泄漏到下游契约。
 */
@Component
public class BookAnalysisLlmResultNormalizer {

    private final GlobalConstraintBoundaryJudge boundaryJudge;

    public BookAnalysisLlmResultNormalizer() {
        this(new GlobalConstraintBoundaryJudge());
    }

    public BookAnalysisLlmResultNormalizer(GlobalConstraintBoundaryJudge boundaryJudge) {
        this.boundaryJudge = boundaryJudge;
    }

    public BookAnalysisLlmResult normalize(BookAnalysisTaskInput input, BookAnalysisLlmResult rawResult) {
        String fallbackSynopsis = summarize(input.sourceText(), 180);
        return new BookAnalysisLlmResult(
                normalizeText(rawResult == null ? null : rawResult.synopsis(), fallbackSynopsis),
                normalizeText(rawResult == null ? null : rawResult.narrativeOutline(), "当前未提取到可靠的全书叙事结构，请在后续阶段补充校验。"),
                normalizeText(rawResult == null ? null : rawResult.styleProfile(), "保持忠实、可审阅、不过度定稿的小说初稿风格。"),
                normalizeTexts(rawResult == null ? null : rawResult.globalRisks()),
                normalizeTexts(rawResult == null ? null : rawResult.translationStrategyNotes()),
                normalizeConstraints(rawResult == null ? null : rawResult.globalConstraints())
        );
    }

    private List<String> normalizeTexts(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String candidate = normalizeText(value, "");
            if (!candidate.isBlank()) {
                normalized.add(candidate);
            }
        }
        return List.copyOf(normalized);
    }

    private List<BookAnalysisLlmConstraint> normalizeConstraints(List<BookAnalysisLlmConstraint> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<BookAnalysisLlmConstraint> normalized = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        for (BookAnalysisLlmConstraint value : values) {
            String type = normalizeText(value == null ? null : value.type(), "general");
            String description = normalizeText(value == null ? null : value.description(), "");
            if (description.isBlank()) {
                continue;
            }
            if (!boundaryJudge.judge(type, description).accepted()) {
                continue;
            }
            String signature = type + "::" + description;
            if (dedupe.add(signature)) {
                normalized.add(new BookAnalysisLlmConstraint(type, description));
            }
        }
        return List.copyOf(normalized);
    }

    private String summarize(String text, int limit) {
        String normalized = normalizeText(text, "");
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit);
    }

    private String normalizeText(String value, String fallback) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? fallback : normalized;
    }
}
