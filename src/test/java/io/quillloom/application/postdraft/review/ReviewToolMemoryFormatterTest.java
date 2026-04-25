package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.ToolCallSignature;
import io.quillloom.application.postdraft.review.service.ReviewToolMemoryFormatter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewToolMemoryFormatterTest {

    @Test
    void shouldRenderToolUseAndResult() {
        ToolCallSignature signature = ToolCallSignature.forReadConfirmedTerms(List.of("Le Conde"));

        assertEquals(
                "tool_use read_confirmed_terms {\"sourceTerms\":[\"Le Conde\"]}",
                ReviewToolMemoryFormatter.renderReadConfirmedTermsUse(signature)
        );
        assertEquals(
                "tool_result read_confirmed_terms sourceTerms=[Le Conde] -> confirmedTerm=Le Conde->孔代咖啡馆",
                ReviewToolMemoryFormatter.renderToolResult(signature, List.of("confirmedTerm=Le Conde->孔代咖啡馆"))
        );
    }

    @Test
    void shouldRenderRedundantHint() {
        ToolCallSignature signature = ToolCallSignature.forReadConfirmedTerms(List.of("Le Conde"));

        String hint = ReviewToolMemoryFormatter.renderRedundantToolCallHint(signature);

        assertTrue(hint.contains("已经成功查过"));
        assertTrue(hint.contains("evaluate_focus"));
        assertTrue(hint.contains("complete_working_set"));
        assertTrue(hint.contains("request_human_review"));
    }
}
