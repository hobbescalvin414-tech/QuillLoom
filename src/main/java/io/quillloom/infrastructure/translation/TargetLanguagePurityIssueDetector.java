package io.quillloom.infrastructure.translation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class TargetLanguagePurityIssueDetector {

    private static final Pattern CHINESE_CHARACTER = Pattern.compile("[\\p{IsHan}]");
    private static final Pattern LATIN_WORD = Pattern.compile("\\b[\\p{IsLatin}]{4,}\\b");

    public List<TranslatedTextIssue> detect(String targetLanguage, String translatedText) {
        String normalizedTargetLanguage = targetLanguage == null ? "" : targetLanguage.trim().toLowerCase(Locale.ROOT);
        String text = translatedText == null ? "" : translatedText.trim();
        if (!"zh".equals(normalizedTargetLanguage) || text.isBlank()) {
            return List.of();
        }
        if (CHINESE_CHARACTER.matcher(text).find()) {
            return List.of();
        }
        long latinWordCount = LATIN_WORD.matcher(text).results().count();
        if (latinWordCount < 4) {
            return List.of();
        }
        return List.of(new TranslatedTextIssue(
                "target-language-purity",
                "检测到目标语言为中文时正文仍存在整句或整段外语残留。"
        ));
    }
}
