package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.EvidenceSufficiency;
import io.quillloom.application.postdraft.review.model.ReviewGuardrailRejection;
import io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation;
import io.quillloom.application.postdraft.review.model.ReviewToolCall;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.ReviewToolTrace;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewStructuredResultModelTest {

    @Test
    void shouldValidateReviewToolDecisionAndCall() {
        ReviewToolDecision decision = new ReviewToolDecision(
                "read_next_chunks",
                Map.of("count", 2),
                "need more evidence"
        );
        ReviewToolCall toolCall = decision.toCall();

        assertEquals("read_next_chunks", decision.toolName());
        assertEquals(2, toolCall.arguments().get("count"));
        assertEquals("need more evidence", toolCall.reason());
        assertThrows(IllegalArgumentException.class, () -> new ReviewToolDecision(" ", Map.of(), "reason"));
        assertThrows(UnsupportedOperationException.class, () -> toolCall.arguments().put("count", 3));
    }

    @Test
    void shouldValidateGuardrailRejection() {
        ReviewGuardrailRejection rejection = ReviewGuardrailRejection.rejected(
                "unknown_tool",
                "unregistered_tool"
        );

        assertTrue(rejection.rejected());
        assertEquals("unknown_tool", rejection.toolName());
        assertThrows(IllegalArgumentException.class, () -> ReviewGuardrailRejection.rejected("", "bad"));
    }

    @Test
    void shouldKeepToolTraceCallSignatureWhenProvided() {
        ReviewToolTrace trace = new ReviewToolTrace(
                "read_confirmed_terms",
                "lookup",
                List.of("confirmedTerm=Le Conde->孔代咖啡馆"),
                "read_confirmed_terms:sourceTerms=[le conde]"
        );

        assertEquals("read_confirmed_terms:sourceTerms=[le conde]", trace.callSignature());
    }

    @Test
    void shouldDefaultToolTraceCallSignatureForLegacyConstructor() {
        ReviewToolTrace trace = new ReviewToolTrace("read_confirmed_terms", "lookup", List.of("ok"));

        assertEquals("", trace.callSignature());
    }

    @Test
    void shouldValidateReviewAgentEvaluation() {
        ReviewAgentEvaluation evaluation = new ReviewAgentEvaluation(
                ReviewStrategy.RETRANSLATE,
                "evidence is strong enough",
                EvidenceSufficiency.SUFFICIENT,
                true
        );

        assertEquals(ReviewStrategy.RETRANSLATE, evaluation.recommendedStrategy());
        assertEquals("evidence is strong enough", evaluation.strategyReason());
        assertEquals(EvidenceSufficiency.SUFFICIENT, evaluation.evidenceSufficiency());
        assertTrue(evaluation.continueInvestigation());
        assertThrows(IllegalArgumentException.class, () -> new ReviewAgentEvaluation(
                null,
                "reason",
                EvidenceSufficiency.PARTIAL,
                false
        ));
        assertThrows(IllegalArgumentException.class, () -> new ReviewAgentEvaluation(
                ReviewStrategy.LIGHT_EDIT,
                " ",
                EvidenceSufficiency.PARTIAL,
                false
        ));
        assertThrows(IllegalArgumentException.class, () -> new ReviewAgentEvaluation(
                ReviewStrategy.LIGHT_EDIT,
                "reason",
                null,
                false
        ));
    }

    @Test
    void shouldValidateRevisionDraft() {
        List<String> rationales = new ArrayList<>(List.of("rationale-1"));
        List<String> risks = new ArrayList<>(List.of("risk-1"));
        RevisionDraft draft = new RevisionDraft(
                "formal translation",
                RevisionMode.DEEP_EDIT,
                rationales,
                risks
        );
        rationales.add("rationale-2");
        risks.add("risk-2");

        assertEquals(RevisionMode.DEEP_EDIT, draft.revisionMode());
        assertEquals(List.of("rationale-1"), draft.keyRationales());
        assertEquals(List.of("risk-1"), draft.residualRisks());
        assertThrows(IllegalArgumentException.class, () -> new RevisionDraft(" ", RevisionMode.DEEP_EDIT, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RevisionDraft("text", null, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RevisionDraft("text", RevisionMode.KEEP, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RevisionDraft("text", RevisionMode.KEEP, List.of(), null));
        assertThrows(UnsupportedOperationException.class, () -> draft.keyRationales().add("cannot-add"));
    }
}
