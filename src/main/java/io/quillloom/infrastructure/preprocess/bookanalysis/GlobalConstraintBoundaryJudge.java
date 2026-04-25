package io.quillloom.infrastructure.preprocess.bookanalysis;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public final class GlobalConstraintBoundaryJudge {

    public GlobalConstraintBoundaryDecision judge(String type, String description) {
        String normalizedType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String normalizedDescription = description == null ? "" : description.trim();
        if (normalizedDescription.isBlank()) {
            return GlobalConstraintBoundaryDecision.reject("blank-description");
        }
        if (mentionsDoNotTranslateEntities(normalizedDescription, normalizedType)) {
            return GlobalConstraintBoundaryDecision.reject("entity-level-do-not-translate");
        }
        if (mentionsKeepQuotedTextOriginal(normalizedDescription)) {
            return GlobalConstraintBoundaryDecision.reject("quoted-text-keep-original");
        }
        return GlobalConstraintBoundaryDecision.accept();
    }

    private boolean mentionsDoNotTranslateEntities(String description, String type) {
        boolean mentionsEntityScope = description.contains("专有名词")
                || description.contains("人名")
                || description.contains("地名")
                || description.contains("书名")
                || description.contains("术语")
                || type.contains("entity")
                || type.contains("name");
        boolean mentionsKeepOriginal = description.contains("原文不译")
                || description.contains("保留原文")
                || description.contains("保持原文")
                || description.contains("不翻译");
        return mentionsEntityScope && mentionsKeepOriginal;
    }

    private boolean mentionsKeepQuotedTextOriginal(String description) {
        boolean mentionsQuotedText = description.contains("引述")
                || description.contains("引语")
                || description.contains("引用")
                || description.contains("quoted text");
        boolean mentionsKeepOriginal = description.contains("保留原文")
                || description.contains("保持原文")
                || description.contains("原文不译");
        return mentionsQuotedText && mentionsKeepOriginal;
    }
}
