package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.EvidenceSufficiency;
import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewAgentAction;
import io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation;
import io.quillloom.application.postdraft.review.model.ReviewAgentState;
import io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProblemType;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewWorkingSetContext;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;
import io.quillloom.application.postdraft.review.prompt.EvaluationPromptBuilder;
import io.quillloom.application.postdraft.review.service.PromptBackedStrategyEvaluationService;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBackedStrategyEvaluationServiceTest {

    private static final List<String> MOJIBAKE_MARKERS = List.of(
            "闂?", "濞?", "闁?", "閳?", "閿?", "閸?", "鐠?", "椤?", "缂?", "缁?"
    );

    @Test
    void shouldUseReadableEvaluationSystemPromptAndWorkingSetTextContext() {
        AtomicReference<String> capturedSystemPrompt = new AtomicReference<>("");
        AtomicReference<String> capturedUserPrompt = new AtomicReference<>("");
        PromptBackedStrategyEvaluationService service = new PromptBackedStrategyEvaluationService(
                new EvaluationPromptBuilder(),
                new ReviewAgentStructuredGenerationPort() {
                    @Override
                    public io.quillloom.application.postdraft.review.model.ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt) {
                        capturedSystemPrompt.set(systemPrompt);
                        capturedUserPrompt.set(userPrompt);
                        return new ReviewAgentEvaluation(ReviewStrategy.KEEP, "ok", EvidenceSufficiency.SUFFICIENT, false);
                    }

                    @Override
                    public io.quillloom.application.postdraft.review.model.RecordConfirmedTermsProposal generateRecordConfirmedTermsProposal(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public io.quillloom.application.postdraft.review.model.RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }
                }
        );

        service.evaluate(sampleSession(), sampleChunk());

        assertTrue(capturedSystemPrompt.get().contains("You are a post-draft translation review agent"));
        assertTrue(capturedSystemPrompt.get().contains("working-set text context"));
        assertFalse(containsMojibake(capturedSystemPrompt.get()));
        assertTrue(capturedUserPrompt.get().contains("[Working Set Text Context]"));
        assertTrue(capturedUserPrompt.get().contains("chunkId=chunk-2"));
    }

    private static boolean containsMojibake(String text) {
        for (String marker : MOJIBAKE_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static PostDraftReviewSession sampleSession() {
        return new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                "check continuity",
                List.of(),
                Set.of(ReviewProblemType.UNRESOLVED_DECISION),
                List.of("evidence-1"),
                ReviewStrategy.LIGHT_EDIT,
                false,
                ReviewAgentState.INVESTIGATING,
                List.of(new ReviewAgentAction("read_next_chunks", "need context", Map.of("count", "1"))),
                Set.of("chunk:1"),
                List.of("key-evidence-1"),
                List.of("conflict-evidence-1"),
                List.of("gap-1")
        ).withWorkingSetContext(new ReviewWorkingSetContext(List.of(
                new ReviewContextChunkSnapshot("chunk-1", 1, "source-1", "translated-1", "", List.of(), List.of(), "", true),
                new ReviewContextChunkSnapshot("chunk-2", 2, "source-2", "translated-2", "", List.of(), List.of(), "", false)
        )));
    }

    private static PostDraftChunkRecord sampleChunk() {
        return new PostDraftChunkRecord(
                "chunk-1",
                1,
                "block-1",
                "source-1",
                "translated-1",
                "",
                List.of(),
                Map.of(),
                List.of(),
                null
        );
    }
}
