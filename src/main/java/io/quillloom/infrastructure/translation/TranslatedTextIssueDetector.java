package io.quillloom.infrastructure.translation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TranslatedTextIssueDetector {

    private final TargetLanguagePurityIssueDetector targetLanguagePurityIssueDetector;

    public TranslatedTextIssueDetector() {
        this(new TargetLanguagePurityIssueDetector());
    }

    public TranslatedTextIssueDetector(TargetLanguagePurityIssueDetector targetLanguagePurityIssueDetector) {
        this.targetLanguagePurityIssueDetector = targetLanguagePurityIssueDetector;
    }

    public List<TranslatedTextIssue> detect(String translatedText) {
        return detect("", translatedText);
    }

    public List<TranslatedTextIssue> detect(String targetLanguage, String translatedText) {
        String text = translatedText == null ? "" : translatedText;
        List<TranslatedTextIssue> issues = new ArrayList<>();

        if (text.contains("（") && text.contains("）")) {
            issues.add(new TranslatedTextIssue(
                    "bracketed-explanation",
                    "检测到正文中出现括号注或解释性补充结构。"
            ));
        }
        if (text.contains("——") && (text.contains("一家") || text.contains("位于") || text.contains("指"))) {
            issues.add(new TranslatedTextIssue(
                    "encyclopedic-insertion",
                    "检测到正文中出现破折号包裹的解释性插入，疑似百科式补写或知识卡泄漏。"
            ));
        }
        issues.addAll(targetLanguagePurityIssueDetector.detect(targetLanguage, text));
        return List.copyOf(issues);
    }
}
