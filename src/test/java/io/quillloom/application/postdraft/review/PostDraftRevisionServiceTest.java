package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewAgentState;
import io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewWorkingSetContext;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionMode;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;
import io.quillloom.application.postdraft.review.prompt.RevisionSelfCheckPromptBuilder;
import io.quillloom.application.postdraft.review.prompt.RevisionPromptBuilder;
import io.quillloom.application.postdraft.review.service.LlmBackedRevisionSelfCheckService;
import io.quillloom.application.postdraft.review.service.PostDraftRevisionService;
import io.quillloom.application.postdraft.review.service.PromptBackedRevisionDraftProvider;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostDraftRevisionServiceTest {

    @Test
    void shouldDelegateGenerateAndSelfCheckForExecutableRevisionStrategies() {
        PostDraftRevisionService service = new PostDraftRevisionService(
                (session, chunk, strategy) -> new RevisionDraft(
                        "revised translation",
                        RevisionMode.DEEP_EDIT,
                        List.of("fix consistency"),
                        List.of()
                ),
                (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(
                        true,
                        "",
                        List.of()
                )
        );

        RevisionDraft draft = service.generate(session(), chunk(), ReviewStrategy.DEEP_EDIT);
        RevisionSelfCheckResult selfCheck = service.selfCheck(session(), chunk(), ReviewStrategy.DEEP_EDIT, draft);

        assertEquals(RevisionMode.DEEP_EDIT, draft.revisionMode());
        assertEquals(true, selfCheck.passed());
    }

    @Test
    void shouldRejectNonExecutableRevisionStrategies() {
        PostDraftRevisionService service = new PostDraftRevisionService(
                (session, chunk, strategy) -> new RevisionDraft(
                        "revised translation",
                        RevisionMode.DEEP_EDIT,
                        List.of(),
                        List.of()
                ),
                (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
        );

        assertThrows(IllegalArgumentException.class, () -> service.generate(session(), chunk(), ReviewStrategy.KEEP));
        assertThrows(IllegalArgumentException.class, () -> service.generate(session(), chunk(), ReviewStrategy.REQUIRE_HUMAN_REVIEW));
    }

    @Test
    void shouldRetrySelfCheckOnceBeforeEscalating() {
        ArrayDeque<RevisionSelfCheckResult> results = new ArrayDeque<>(List.of(
                new RevisionSelfCheckResult(false, "bad", List.of("risk")),
                new RevisionSelfCheckResult(true, "", List.of())
        ));
        LlmBackedRevisionSelfCheckService service = new LlmBackedRevisionSelfCheckService(
                new RevisionSelfCheckPromptBuilder(),
                new ReviewAgentStructuredGenerationPort() {
                    @Override
                    public io.quillloom.application.postdraft.review.model.ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt) {
                        return results.removeFirst();
                    }
                }
        );

        RevisionSelfCheckResult result = service.check(
                session(),
                chunk(),
                ReviewStrategy.DEEP_EDIT,
                new RevisionDraft("revised translation", RevisionMode.DEEP_EDIT, List.of(), List.of())
        );

        assertTrue(result.passed());
        assertEquals(0, results.size());
    }

    @Test
    void shouldRetryRevisionDraftOnceAfterInvalidStructuredOutput() {
        ArrayDeque<Object> draftResults = new ArrayDeque<>(List.of(
                new IllegalStateException("invalid revision draft"),
                new RevisionDraft("revised translation", RevisionMode.DEEP_EDIT, List.of("fix"), List.of())
        ));
        PromptBackedRevisionDraftProvider provider = new PromptBackedRevisionDraftProvider(
                new RevisionPromptBuilder(),
                new ReviewAgentStructuredGenerationPort() {
                    @Override
                    public io.quillloom.application.postdraft.review.model.ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt) {
                        Object next = draftResults.removeFirst();
                        if (next instanceof RuntimeException ex) {
                            throw ex;
                        }
                        return (RevisionDraft) next;
                    }

                    @Override
                    public RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }
                }
        );

        RevisionDraft draft = provider.generate(session(), chunk(), ReviewStrategy.DEEP_EDIT);

        assertEquals("revised translation", draft.formalTranslation());
        assertEquals(0, draftResults.size());
    }

    @Test
    void shouldIncludeWorkingSetContextInPromptBackedRevisionDraftPrompt() {
        AtomicReference<String> capturedPrompt = new AtomicReference<>("");
        PromptBackedRevisionDraftProvider provider = new PromptBackedRevisionDraftProvider(
                new RevisionPromptBuilder(),
                new ReviewAgentStructuredGenerationPort() {
                    @Override
                    public io.quillloom.application.postdraft.review.model.ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt) {
                        capturedPrompt.set(userPrompt);
                        return new RevisionDraft("revised translation", RevisionMode.DEEP_EDIT, List.of("fix"), List.of());
                    }

                    @Override
                    public RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }
                }
        );

        provider.generate(sessionWithWorkingSetContext(), chunk(), ReviewStrategy.DEEP_EDIT);

        assertTrue(capturedPrompt.get().contains("[Revision Target]"));
        assertTrue(capturedPrompt.get().contains("[Revision Contract]"));
        assertTrue(capturedPrompt.get().contains("[Output Contract]"));
        assertTrue(capturedPrompt.get().contains("issues that must be fixed in this round"));
        assertTrue(capturedPrompt.get().contains("boundary that must not be expanded"));
        assertTrue(capturedPrompt.get().contains("formalTranslation must be the complete formal translation of the current chunk"));
        assertTrue(capturedPrompt.get().contains("[Working Set Context]"));
        assertTrue(capturedPrompt.get().contains("chunkId=chunk-2"));
        assertTrue(capturedPrompt.get().contains("sourceText=neighbor source"));
    }

    @Test
    void shouldIncludeWorkingSetContextInRevisionSelfCheckPrompt() {
        AtomicReference<String> firstPrompt = new AtomicReference<>("");
        LlmBackedRevisionSelfCheckService service = new LlmBackedRevisionSelfCheckService(
                new RevisionSelfCheckPromptBuilder(),
                new ReviewAgentStructuredGenerationPort() {
                    @Override
                    public io.quillloom.application.postdraft.review.model.ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt) {
                        firstPrompt.compareAndSet("", userPrompt);
                        return new RevisionSelfCheckResult(true, "", List.of());
                    }
                }
        );

        service.check(
                sessionWithWorkingSetContext(),
                chunk(),
                ReviewStrategy.DEEP_EDIT,
                new RevisionDraft("revised translation", RevisionMode.DEEP_EDIT, List.of("fix"), List.of())
        );

        assertTrue(firstPrompt.get().contains("[Self-Check Objective]"));
        assertTrue(firstPrompt.get().contains("[Self-Check Task]"));
        assertTrue(firstPrompt.get().contains("[Self-Check Constraints]"));
        assertTrue(firstPrompt.get().contains("[Output Contract]"));
        assertTrue(firstPrompt.get().contains("current Revision Target"));
        assertTrue(firstPrompt.get().contains("addresses previous findings one by one if previous findings exist"));
        assertTrue(firstPrompt.get().contains("The fields must be:"));
        assertTrue(firstPrompt.get().contains("- passed"));
        assertTrue(firstPrompt.get().contains("- stopReason"));
        assertTrue(firstPrompt.get().contains("- findings"));
        assertTrue(firstPrompt.get().contains("[Working Set Context]"));
        assertTrue(firstPrompt.get().contains("chunkId=chunk-2"));
        assertTrue(firstPrompt.get().contains("sourceText=neighbor source"));
    }

    private static PostDraftReviewSession session() {
        return new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                "operator-note",
                List.of(),
                Set.of(),
                List.of("seed-evidence"),
                ReviewStrategy.DEEP_EDIT,
                false,
                ReviewAgentState.REVISING,
                List.of(),
                Set.of(),
                List.of("key-evidence"),
                List.of(),
                List.of(),
                null
        );
    }

    private static PostDraftChunkRecord chunk() {
        return new PostDraftChunkRecord(
                "chunk-1",
                1,
                "block-1",
                "source text",
                "old translation",
                "commentary",
                List.of(),
                Map.of(),
                List.<TranslationCandidateUpdate>of(),
                null
        );
    }

    private static PostDraftReviewSession sessionWithWorkingSetContext() {
        return session().withWorkingSetContext(new ReviewWorkingSetContext(List.of(
                new ReviewContextChunkSnapshot(
                        "chunk-1",
                        1,
                        "source text",
                        "old translation",
                        "",
                        List.of(),
                        List.of(),
                        "",
                        true
                ),
                new ReviewContextChunkSnapshot(
                        "chunk-2",
                        2,
                        "neighbor source",
                        "neighbor translation",
                        "",
                        List.of(),
                        List.of(),
                        "",
                        false
                )
        )));
    }
}
