package io.quillloom.infrastructure.translation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class GlossaryComplianceIssueDetector {

    public List<ChunkTranslationDecisionNoteResult> detect(Map<String, String> confirmedTerms,
                                                           String translatedText) {
        if (confirmedTerms == null || confirmedTerms.isEmpty()) {
            return List.of();
        }
        String text = translatedText == null ? "" : translatedText;
        List<ChunkTranslationDecisionNoteResult> issues = new ArrayList<>();
        for (Map.Entry<String, String> entry : confirmedTerms.entrySet()) {
            String sourceTerm = normalize(entry.getKey());
            String translatedTerm = normalize(entry.getValue());
            if (sourceTerm.isBlank() || translatedTerm.isBlank()) {
                continue;
            }
            if (text.contains(sourceTerm) && text.contains(translatedTerm)) {
                issues.add(new ChunkTranslationDecisionNoteResult(
                        "glossary-compliance-warning",
                        sourceTerm,
                        "检测到原文名与已确认译名在同一正文中混用。",
                        "请在修订轮统一沿用当前已确认译名。"
                ));
                continue;
            }
            if (text.contains(sourceTerm) && !text.contains(translatedTerm)) {
                issues.add(new ChunkTranslationDecisionNoteResult(
                        "glossary-compliance-warning",
                        sourceTerm,
                        "检测到已确认术语未在正文中沿用。",
                        "请在修订轮改为沿用当前已确认译名。"
                ));
            }
        }
        return List.copyOf(issues);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
