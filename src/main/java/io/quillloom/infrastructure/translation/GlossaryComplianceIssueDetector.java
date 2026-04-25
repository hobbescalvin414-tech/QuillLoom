package io.quillloom.infrastructure.translation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GlossaryComplianceIssueDetector {

    public List<ChunkTranslationDecisionNoteResult> detect(Map<String, String> glossaryTerms,
                                                           String translatedText) {
        if (glossaryTerms == null || glossaryTerms.isEmpty()) {
            return List.of();
        }
        String text = translatedText == null ? "" : translatedText;
        if (text.isBlank()) {
            return List.of();
        }

        Map<String, ChunkTranslationDecisionNoteResult> dedup = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : glossaryTerms.entrySet()) {
            String sourceTerm = normalize(entry.getKey());
            String translatedTerm = normalize(entry.getValue());
            if (sourceTerm.isBlank() || translatedTerm.isBlank()) {
                continue;
            }

            if (text.contains(sourceTerm) && text.contains(translatedTerm)) {
                addIssue(dedup, new ChunkTranslationDecisionNoteResult(
                        "glossary-compliance-warning",
                        sourceTerm,
                        "检测到原文命名与当前词池译名在同一正文中混用。",
                        "请在修订轮统一沿用当前词池中的稳定译名。"
                ));
                addIssue(dedup, new ChunkTranslationDecisionNoteResult(
                        "name-residue-warning",
                        sourceTerm,
                        "当前词池已有对应译名，但正文仍残留原文命名。",
                        "请在修订轮清理正文中的原文残留，并统一改为当前词池译名。"
                ));
                continue;
            }

            if (text.contains(sourceTerm) && !text.contains(translatedTerm)) {
                addIssue(dedup, new ChunkTranslationDecisionNoteResult(
                        "glossary-compliance-warning",
                        sourceTerm,
                        "检测到当前词池已有稳定译名，但正文没有沿用。",
                        "请在修订轮改为沿用当前词池中的稳定译名。"
                ));
                addIssue(dedup, new ChunkTranslationDecisionNoteResult(
                        "name-residue-warning",
                        sourceTerm,
                        "当前词池已有对应译名，但正文仍残留原文命名。",
                        "请在修订轮清理正文中的原文残留，并统一改为当前词池译名。"
                ));
                addIssue(dedup, new ChunkTranslationDecisionNoteResult(
                        "glossary-entry-not-applied",
                        sourceTerm,
                        "当前词池已有对应译名，但正文未采用该译名。",
                        "请在修订轮优先应用当前词池中的对应译法。"
                ));
            }
        }
        return List.copyOf(new ArrayList<>(dedup.values()));
    }

    private void addIssue(Map<String, ChunkTranslationDecisionNoteResult> dedup,
                          ChunkTranslationDecisionNoteResult issue) {
        String key = issue.type() + "|" + issue.sourceAnchor() + "|" + issue.description();
        dedup.putIfAbsent(key, issue);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
