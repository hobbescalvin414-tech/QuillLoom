package io.quillloom.infrastructure.translation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlossaryComplianceIssueDetectorTest {

    @Test
    void shouldAddDecisionNoteWhenGlossaryEntryIsNotAppliedInTranslatedText() {
        GlossaryComplianceIssueDetector detector = new GlossaryComplianceIssueDetector();

        List<ChunkTranslationDecisionNoteResult> issues = detector.detect(
                Map.of("Louki", "露姬"),
                "Louki站在门口，沉默地看着街道。"
        );

        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(issue -> issue.type().equals("glossary-compliance-warning")));
        assertTrue(issues.stream().anyMatch(issue -> issue.type().equals("name-residue-warning")));
        assertTrue(issues.stream().anyMatch(issue -> issue.type().equals("glossary-entry-not-applied")));
    }

    @Test
    void shouldAddDecisionNoteWhenSourceAndGlossaryTranslationAreMixed() {
        GlossaryComplianceIssueDetector detector = new GlossaryComplianceIssueDetector();

        List<ChunkTranslationDecisionNoteResult> issues = detector.detect(
                Map.of("Louki", "露姬"),
                "Louki站在门口，露姬没有回头。"
        );

        assertTrue(issues.stream().anyMatch(issue -> issue.type().equals("glossary-compliance-warning")));
        assertTrue(issues.stream().anyMatch(issue -> issue.type().equals("name-residue-warning")));
    }

    @Test
    void shouldIgnoreBlankText() {
        GlossaryComplianceIssueDetector detector = new GlossaryComplianceIssueDetector();

        List<ChunkTranslationDecisionNoteResult> issues = detector.detect(
                Map.of("Louki", "露姬"),
                "   "
        );

        assertTrue(issues.isEmpty());
    }
}
