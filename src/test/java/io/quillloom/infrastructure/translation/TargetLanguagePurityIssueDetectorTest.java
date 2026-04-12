package io.quillloom.infrastructure.translation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetLanguagePurityIssueDetectorTest {

    @Test
    void shouldReturnNoIssuesForChineseTargetWithChineseBody() {
        TargetLanguagePurityIssueDetector detector = new TargetLanguagePurityIssueDetector();

        List<TranslatedTextIssue> issues = detector.detect("zh", "她站在窗边，沉默地望着街道。");

        assertTrue(issues.isEmpty());
    }

    @Test
    void shouldDetectForeignSentenceResidualForChineseTarget() {
        TargetLanguagePurityIssueDetector detector = new TargetLanguagePurityIssueDetector();

        List<TranslatedTextIssue> issues = detector.detect("zh", "Elle se tenait pres de la fenetre et regardait la rue.");

        assertEquals(1, issues.size());
        assertEquals("target-language-purity", issues.get(0).code());
    }
}
