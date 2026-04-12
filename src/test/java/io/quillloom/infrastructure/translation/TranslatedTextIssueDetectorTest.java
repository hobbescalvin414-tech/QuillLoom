package io.quillloom.infrastructure.translation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslatedTextIssueDetectorTest {

    @Test
    void shouldDetectKnowledgeCardLeakAndBracketExplanationPatterns() {
        TranslatedTextIssueDetector detector = new TranslatedTextIssueDetector();

        List<TranslatedTextIssue> issues = detector.detect(
                "zh",
                "孔代咖啡馆（Le Conde）是巴黎左岸一处边缘文化据点——位于旧街区的一家通宵咖啡馆。"
        );

        assertEquals(2, issues.size());
        assertTrue(issues.stream().anyMatch(item -> item.code().equals("bracketed-explanation")));
        assertTrue(issues.stream().anyMatch(item -> item.code().equals("encyclopedic-insertion")));
    }

    @Test
    void shouldDetectFrenchParagraphResidualWhenTargetLanguageIsZh() {
        TranslatedTextIssueDetector detector = new TranslatedTextIssueDetector();

        List<TranslatedTextIssue> issues = detector.detect(
                "zh",
                "Elle se tenait pres de la fenetre et regardait la rue sans parler."
        );

        assertTrue(issues.stream().anyMatch(item -> item.code().equals("target-language-purity")));
    }
}
