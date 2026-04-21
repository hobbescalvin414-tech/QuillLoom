package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewAgentAction;
import io.quillloom.application.postdraft.review.model.ReviewAgentState;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProblemType;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionMode;
import io.quillloom.application.postdraft.review.prompt.EvaluationPromptBuilder;
import io.quillloom.application.postdraft.review.prompt.InvestigationPromptBuilder;
import io.quillloom.application.postdraft.review.prompt.ReviewAgentSystemPromptBuilder;
import io.quillloom.application.postdraft.review.prompt.RevisionPromptBuilder;
import io.quillloom.application.postdraft.review.prompt.RevisionSelfCheckPromptBuilder;
import io.quillloom.application.postdraft.review.service.ReviewToolRegistry;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewPromptBuilderTest {

    private static final List<String> MOJIBAKE_MARKERS = List.of(
            "闂", "娴", "閺", "鈧", "锟", "鍙", "璇", "顩", "缂", "绱",
            "鏃?", "鏄?", "鍚?"
    );

    @Test
    void shouldRenderInvestigationPromptWithDynamicTools() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession();

        String prompt = builder.build(
                session,
                ReviewToolRegistry.defaultRegistry().definitions(),
                List.of("evidence-A", "evidence-B")
        );

        assertTrue(prompt.contains("project-1"));
        assertTrue(prompt.contains("chunk-1"));
        assertTrue(prompt.contains("INVESTIGATING"));
        assertTrue(prompt.contains("evidence-A"));
        assertTrue(prompt.contains("gap-1"));
        assertTrue(prompt.contains("\"toolName\""));
        assertTrue(prompt.contains("\"arguments\""));
        assertTrue(prompt.contains("\"reason\""));
    }

    @Test
    void shouldRenderRecentTranscriptIntoInvestigationPrompt() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession()
                .appendTranscript("rejected read_previous_chunks -> missing_argument:count");

        String prompt = builder.build(
                session,
                ReviewToolRegistry.defaultRegistry().definitions(),
                List.of("evidence-A")
        );

        assertTrue(prompt.contains("missing_argument:count"));
    }

    @Test
    void shouldRenderStructuredToolMemoryTranscriptIntoInvestigationPrompt() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession()
                .appendTranscript("tool_use read_confirmed_terms {\"sourceTerms\":[\"Le Conde\"]}")
                .appendTranscript("tool_result read_confirmed_terms sourceTerms=[Le Conde] -> confirmedTerm=Le Conde->Le Conde Cafe");

        String prompt = builder.build(
                session,
                ReviewToolRegistry.defaultRegistry().definitions(),
                List.of("evidence-A")
        );

        assertTrue(prompt.contains("tool_use read_confirmed_terms {\"sourceTerms\":[\"Le Conde\"]}"));
        assertTrue(prompt.contains("confirmedTerm=Le Conde->Le Conde Cafe"));
    }

    @Test
    void shouldRenderPerToolArgumentExamplesInSystemPrompt() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("read_confirmed_terms"));
        assertTrue(prompt.contains("\"sourceTerms\""));
        assertTrue(prompt.contains("complete_working_set"));
        assertTrue(prompt.contains("\"chunkIds\""));
        assertTrue(prompt.contains("draft_revision"));
        assertTrue(prompt.contains("arguments={}"));
        assertNoMojibake(prompt);
    }

    @Test
    void shouldRenderFormalToolOperatingRules() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("Tool: read_confirmed_terms"));
        assertTrue(prompt.contains("When to use:"));
        assertTrue(prompt.contains("When not to use:"));
        assertTrue(prompt.contains("Result semantics:"));
        assertTrue(prompt.contains("Repeat policy:"));
        assertTrue(prompt.contains("FORBID_SAME_SIGNATURE_AFTER_SUCCESS"));
        assertTrue(prompt.contains("Do not pre-query terms"));
        assertTrue(prompt.contains("One confirmed-term lookup per source term is enough"));
        assertNoMojibake(prompt);
    }

    @Test
    void shouldExplainWorkingSetIsEvidenceScopeNotSubmitScope() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("workingSet is the evidence scope, not the submission scope"));
        assertTrue(prompt.contains("may contain only chunks that are still pending"));
        assertTrue(prompt.contains("not automatically part of submission"));
        assertTrue(prompt.contains("actually reviewed and completed in this anchor round"));
    }

    @Test
    void shouldExplainInputFieldAuthorityLevels() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("[Input Field Authority]"));
        assertTrue(prompt.contains("sourceText is the highest-authority evidence"));
        assertTrue(prompt.contains("read_confirmed_terms returns project-level authoritative results"));
        assertTrue(prompt.contains("translatorCommentary is low-priority translator commentary"));
        assertTrue(prompt.contains("decisionNotes is low-priority draft-stage risk commentary"));
        assertTrue(prompt.contains("transitionNote is low-priority continuity commentary"));
        assertFalse(prompt.contains("candidateUpdates"));
    }

    @Test
    void shouldPreventLowAuthorityNotesFromTriggeringHighRiskActions() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("decisionNotes / translatorCommentary / transitionNote / confirmedTermLookupMiss"));
        assertTrue(prompt.contains("evaluate_focus"));
        assertTrue(prompt.contains("record_confirmed_terms / draft_revision / request_human_review"));
        assertTrue(prompt.contains("not registered yet"));
        assertTrue(prompt.contains("not registration permission"));
    }

    @Test
    void shouldRenderP0BlockingRulesAndRemoveLooseCompletionExitInSystemPrompt() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("[P0 Hard Blocks]"));
        assertTrue(prompt.contains("confirmed-term conflict is already identified and unresolved"));
        assertTrue(prompt.contains("do not call complete_working_set"));
        assertTrue(prompt.contains("If there is no explicit source->target term pair"));
        assertTrue(prompt.contains("do not call record_confirmed_terms"));
        assertTrue(prompt.contains("If the basis is only low-priority signals"));
        assertFalse(prompt.contains("not sharp enough"));
    }

    @Test
    void shouldRenderLowPriorityEvidencePermissionBoundaryInInvestigationPrompt() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession();

        String prompt = builder.build(
                session,
                ReviewToolRegistry.defaultRegistry().definitions(),
                List.of("decisionNotes=name consistency may be wrong", "confirmedTermLookupMiss=[Bernolle]")
        );

        assertTrue(prompt.contains("decisionNotes / translatorCommentary / transitionNote / confirmedTermLookupMiss"));
        assertTrue(prompt.contains("evaluate_focus"));
        assertTrue(prompt.contains("record_confirmed_terms / draft_revision / request_human_review"));
        assertTrue(prompt.contains("confirmedTermLookupMiss"));
    }

    @Test
    void shouldRenderConfirmedTermsEntriesPlacementRuleInInvestigationPrompt() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession();

        String prompt = builder.build(
                session,
                ReviewToolRegistry.defaultRegistry().definitions(),
                List.of()
        );

        assertTrue(prompt.contains("record_confirmed_terms"));
        assertTrue(prompt.contains("arguments.entries"));
        assertTrue(prompt.contains("not only in reason"));
        assertTrue(prompt.contains("When toolName=record_confirmed_terms, candidate pairs must be written in arguments.entries, not only in reason."));
    }

    @Test
    void shouldKeepProposalDtoDetailsOutOfInvestigationPrompt() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession();

        String prompt = builder.build(
                session,
                ReviewToolRegistry.defaultRegistry().definitions(),
                List.of()
        );

        assertFalse(prompt.contains("\"action\": \"RECORD_CONFIRMED_TERMS\""));
        assertFalse(prompt.contains("\"action\": \"NOT_APPLICABLE\""));
        assertFalse(prompt.contains("\"sourceTerm\""));
        assertFalse(prompt.contains("\"targetTerm\""));
    }

    @Test
    void shouldNotTeachLegacyEntriesRepairAsProposalMainPathContract() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertFalse(prompt.contains("proposal main path must repair final arguments.entries directly"));
    }

    @Test
    void shouldRejectMojibakeInInvestigationPrompt() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession();

        String prompt = builder.build(
                session,
                ReviewToolRegistry.defaultRegistry().definitions(),
                List.of("evidence-A")
        );

        assertNoMojibake(prompt);
    }

    @Test
    void shouldUsePlaceholderExampleInsteadOfRealTermInInvestigationPrompt() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession();

        String prompt = builder.build(
                session,
                ReviewToolRegistry.defaultRegistry().definitions(),
                List.of()
        );

        assertTrue(prompt.contains("read_confirmed_terms"));
        assertTrue(prompt.contains("<source-term>"));
        assertFalse(prompt.contains("Le Cond"));
    }

    @Test
    void shouldRenderRevisionPromptWithAllRequiredInputs() {
        RevisionPromptBuilder builder = new RevisionPromptBuilder();
        PostDraftReviewSession session = sampleSession();
        PostDraftChunkRecord chunk = ReviewAgentFixtures.chunkWithTermUpdate(
                "chunk-1",
                "Le Conde etait plein.",
                "Le Conde Cafe was crowded.",
                Map.of("Le Conde", "Le Conde Cafe")
        );

        String prompt = builder.build(
                session,
                chunk,
                ReviewStrategy.DEEP_EDIT,
                List.of("rationale-1", "rationale-2"),
                List.of("risk-1")
        );

        assertTrue(prompt.contains("[Current Facts]"));
        assertTrue(prompt.contains("DEEP_EDIT"));
        assertTrue(prompt.contains("rationale-1"));
        assertTrue(prompt.contains("risk-1"));
        assertTrue(prompt.contains("\"formalTranslation\""));
        assertTrue(prompt.contains("\"revisionMode\""));
        assertTrue(prompt.contains("Le Conde etait plein."));
        assertTrue(prompt.contains("Le Conde Cafe was crowded."));
        assertTrue(prompt.contains("confirmedTermUpdates"));
        assertNoMojibake(prompt);
    }

    @Test
    void shouldRenderSelfCheckPromptWithConfirmedTermConsistencyRules() {
        RevisionSelfCheckPromptBuilder builder = new RevisionSelfCheckPromptBuilder();
        PostDraftReviewSession session = sampleSession()
                .appendTranscript("read_confirmed_terms: Le Conde -> Le Conde Cafe");
        PostDraftChunkRecord chunk = ReviewAgentFixtures.chunkWithTermUpdate(
                "chunk-1",
                "Le Conde etait plein.",
                "Le Conde Bistro was crowded.",
                Map.of("Le Conde", "Le Conde Cafe")
        );
        RevisionDraft draft = new RevisionDraft(
                "Le Conde Cafe was crowded.",
                RevisionMode.LIGHT_EDIT,
                List.of("fix Le Conde term"),
                List.of()
        );

        String prompt = builder.build(session, chunk, ReviewStrategy.LIGHT_EDIT, draft);

        assertTrue(prompt.contains("confirmed terms"));
        assertTrue(prompt.contains("confirmedTermUpdates"));
        assertTrue(prompt.contains("Le Conde"));
        assertTrue(prompt.contains("Le Conde Cafe"));
        assertTrue(prompt.contains("passed=false"));
    }

    private static void assertNoMojibake(String text) {
        for (String marker : MOJIBAKE_MARKERS) {
            assertFalse(text.contains(marker), "unexpected mojibake marker: " + marker);
        }
    }

    private static PostDraftReviewSession sampleSession() {
        return new PostDraftReviewSession(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                "check local continuity and naming",
                List.of("context-1"),
                Set.of(ReviewProblemType.UNRESOLVED_DECISION),
                List.of("evidence-summary-1"),
                ReviewStrategy.LIGHT_EDIT,
                false,
                ReviewAgentState.INVESTIGATING,
                List.of(new ReviewAgentAction("lookup_knowledge_cards", "check", Map.of("key", "value"))),
                Set.of("chunk:1"),
                List.of("key-evidence-session"),
                List.of("conflict-evidence-1"),
                List.of("gap-1")
        );
    }
}
