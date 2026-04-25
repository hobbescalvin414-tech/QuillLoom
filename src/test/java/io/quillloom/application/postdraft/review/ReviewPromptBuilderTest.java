package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewAgentAction;
import io.quillloom.application.postdraft.review.model.ReviewAgentState;
import io.quillloom.application.postdraft.review.model.ReviewBoundaryWindow;
import io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProblemType;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewToolTrace;
import io.quillloom.application.postdraft.review.model.ReviewWorkingSetContext;
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
    void shouldRenderLiteraryTranslationReviewerPositioningInSystemPrompt() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("literary translation review specialist"));
        assertTrue(prompt.contains("not re-running the full translation pipeline"));
        assertTrue(prompt.contains("naming consistency"));
        assertTrue(prompt.contains("workingSet submission"));
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
    void shouldRequireConcreteQuestionForHumanInSystemPrompt() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("questionForHuman"));
        assertTrue(prompt.contains("requestReason"));
        assertTrue(prompt.contains("requestNote"));
        assertTrue(prompt.contains("resumeHint"));
        assertTrue(prompt.contains("Do not use vague human questions"));
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
    void shouldExplainQuestionForHumanRequirementInInvestigationPrompt() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession();

        String prompt = builder.build(
                session,
                ReviewToolRegistry.defaultRegistry().definitions(),
                List.of()
        );

        assertTrue(prompt.contains("questionForHuman"));
        assertTrue(prompt.contains("request_human_review"));
        assertTrue(prompt.contains("not empty"));
        assertTrue(prompt.contains("Do not use vague human questions"));
    }

    @Test
    void shouldExposeObjectiveAdjacentBoundaryStateInInvestigationPrompt() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession()
                .withWorkingSetContext(new ReviewWorkingSetContext(List.of(
                        new ReviewContextChunkSnapshot("chunk-1", 1, "source-1", "translated-1", "", List.of(), List.of(), "", true)
                )))
                .withBoundaryWindow(new ReviewBoundaryWindow(List.of(
                        new ReviewContextChunkSnapshot("chunk-1", 1, "source-1", "translated-1", "", List.of(), List.of(), "", true)
                )));

        String prompt = builder.build(
                session,
                ReviewToolRegistry.defaultRegistry().definitions(),
                List.of()
        );

        assertTrue(prompt.contains("boundaryLeftChunkId=chunk-1"));
        assertTrue(prompt.contains("boundaryRightChunkId=chunk-1"));
        assertTrue(prompt.contains("anchorOnlyView=true"));
        assertTrue(prompt.contains("hasPreviousRead=false"));
        assertTrue(prompt.contains("hasNextRead=false"));
        assertTrue(prompt.contains("adjacentReadCount=0"));
    }

    @Test
    void shouldStrengthenAdjacentReadPriorityAndBlockExpansionBoundaryInPrompts() {
        String systemPrompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());
        String investigationPrompt = new InvestigationPromptBuilder()
                .build(sampleSession(), ReviewToolRegistry.defaultRegistry().definitions(), List.of());

        assertTrue(systemPrompt.contains("do not directly evaluate_focus or complete_working_set"));
        assertTrue(systemPrompt.contains("expand_block_context does not replace adjacent continuity verification"));
        assertTrue(investigationPrompt.contains("Before making any continuity judgment, first read adjacent context with tools."));
        assertTrue(investigationPrompt.contains("In these cases, do not directly treat continuity as established."));
        assertTrue(investigationPrompt.contains("expand_block_context"));
        assertTrue(investigationPrompt.contains("does not replace adjacent continuity reading"));
    }

    @Test
    void shouldTreatAnchorOnlyViewAsMissingAdjacentContextForContinuitySensitiveCases() {
        PostDraftReviewSession session = sampleSession()
                .withWorkingSetContext(new ReviewWorkingSetContext(List.of(
                        new ReviewContextChunkSnapshot("chunk-1", 1, "source-1", "translated-1", "", List.of(), List.of(), "", true)
                )))
                .withBoundaryWindow(new ReviewBoundaryWindow(List.of(
                        new ReviewContextChunkSnapshot("chunk-1", 1, "source-1", "translated-1", "", List.of(), List.of(), "", true)
                )));

        String prompt = new InvestigationPromptBuilder()
                .build(session, ReviewToolRegistry.defaultRegistry().definitions(), List.of("reply-like continuity risk"));

        assertTrue(prompt.contains("anchorOnlyView=true"));
        assertTrue(prompt.contains("adjacentReadCount=0"));
        assertTrue(prompt.contains("If no adjacent context has been read yet, first call `read_previous_chunks` and/or `read_next_chunks`."));
        assertTrue(prompt.contains("Do not treat anchor-only reading as sufficient in that case."));
    }

    @Test
    void shouldNotTreatSingleSidedAdjacentReadAsFullContinuityVerification() {
        PostDraftReviewSession session = sampleSession()
                .withWorkingSetContext(new ReviewWorkingSetContext(List.of(
                        new ReviewContextChunkSnapshot("chunk-1", 1, "source-1", "translated-1", "", List.of(), List.of(), "", true),
                        new ReviewContextChunkSnapshot("chunk-2", 2, "source-2", "translated-2", "", List.of(), List.of(), "", false)
                )))
                .withBoundaryWindow(new ReviewBoundaryWindow(List.of(
                        new ReviewContextChunkSnapshot("chunk-1", 1, "source-1", "translated-1", "", List.of(), List.of(), "", true),
                        new ReviewContextChunkSnapshot("chunk-2", 2, "source-2", "translated-2", "", List.of(), List.of(), "", false)
                )));

        String prompt = new InvestigationPromptBuilder()
                .build(session, ReviewToolRegistry.defaultRegistry().definitions(), List.of("transition continuity risk"));

        assertTrue(prompt.contains("hasPreviousRead=false"));
        assertTrue(prompt.contains("hasNextRead=true"));
        assertTrue(prompt.contains("Do not treat a single-sided adjacent read as sufficient when the unresolved issue still depends on the missing side."));
        assertTrue(prompt.contains("If the current judgment still depends on unread adjacent text, continue investigation."));
    }

    @Test
    void shouldForbidCompleteWorkingSetBeforeRevisionSelfCheckHasPassedInPrompts() {
        PostDraftReviewSession session = sampleSession()
                .withStrategy(ReviewStrategy.LIGHT_EDIT);
        String systemPrompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());
        String investigationPrompt = new InvestigationPromptBuilder()
                .build(session, ReviewToolRegistry.defaultRegistry().definitions(), List.of());

        assertTrue(systemPrompt.contains("if current strategy is LIGHT_EDIT / DEEP_EDIT / RETRANSLATE"));
        assertTrue(systemPrompt.contains("must not call complete_working_set"));
        assertTrue(systemPrompt.contains("selfCheckPassed=true"));
        assertTrue(systemPrompt.contains("revision_ready_for_completion"));
        assertTrue(investigationPrompt.contains("If current strategy is `LIGHT_EDIT` / `DEEP_EDIT` / `RETRANSLATE`, you must not call `complete_working_set`"));
        assertTrue(investigationPrompt.contains("selfCheckPassed=true"));
    }

    @Test
    void shouldRenderProjectCompletionStateIntoInvestigationPrompt() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession();

        String prompt = builder.build(
                session,
                ReviewToolRegistry.defaultRegistry().definitions(),
                List.of(),
                new InvestigationPromptBuilder.PromptProjectState(0, 3, false)
        );

        assertTrue(prompt.contains("pendingChunkCount=0"));
        assertTrue(prompt.contains("completedChunkCount=3"));
        assertTrue(prompt.contains("currentFocusChunkStillPending=false"));
        assertTrue(prompt.contains("Call `complete_project`"));
        assertTrue(prompt.contains("Do not call `complete_working_set` for a focusChunk that is no longer pending."));
    }

    @Test
    void shouldExplainPendingEmptyAndStaleFocusEndgameInSystemPrompt() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("If pendingChunkCount=0"));
        assertTrue(prompt.contains("prefer complete_project"));
        assertTrue(prompt.contains("If currentFocusChunkStillPending=false"));
        assertTrue(prompt.contains("do not call complete_working_set"));
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
    void shouldRenderInvestigationPromptWithNewSectionOrder() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession();

        String prompt = builder.build(
                session,
                ReviewToolRegistry.defaultRegistry().definitions(),
                List.of("evidence-A")
        );

        assertTrue(prompt.contains("[Current Facts]"));
        assertTrue(prompt.contains("[Product Role And Core Responsibilities]"));
        assertTrue(prompt.contains("[Action Tree]"));
        assertTrue(prompt.contains("[Working Set Text Context]"));
        assertTrue(prompt.contains("[State Memory]"));
        assertTrue(prompt.indexOf("[Current Facts]") < prompt.indexOf("[Product Role And Core Responsibilities]"));
        assertTrue(prompt.indexOf("[Product Role And Core Responsibilities]") < prompt.indexOf("[Action Tree]"));
        assertTrue(prompt.indexOf("[Action Tree]") < prompt.indexOf("[Working Set Text Context]"));
        assertTrue(prompt.indexOf("[Working Set Text Context]") < prompt.indexOf("[State Memory]"));
    }

    @Test
    void shouldRenderWorkingSetContextSnapshotsIntoInvestigationPrompt() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession().withWorkingSetContext(new ReviewWorkingSetContext(List.of(
                new ReviewContextChunkSnapshot(
                        "chunk-1",
                        1,
                        "source-1",
                        "translated-1",
                        "commentary-1",
                        List.of("decision-1"),
                        List.of("Louki->露姬"),
                        "transition-1",
                        true
                ),
                new ReviewContextChunkSnapshot(
                        "chunk-2",
                        2,
                        "source-2",
                        "translated-2",
                        "commentary-2",
                        List.of(),
                        List.of(),
                        "",
                        false
                )
        )));

        String prompt = builder.build(
                session,
                ReviewToolRegistry.defaultRegistry().definitions(),
                List.of("evidence-A")
        );

        assertTrue(prompt.contains("chunkId=chunk-1"));
        assertTrue(prompt.contains("sourceText=source-1"));
        assertTrue(prompt.contains("translatedText=translated-1"));
        assertTrue(prompt.contains("anchor=true"));
        assertTrue(prompt.contains("chunkId=chunk-2"));
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
    void shouldRenderWorkingSetTextContextIntoEvaluationPrompt() {
        EvaluationPromptBuilder builder = new EvaluationPromptBuilder();
        PostDraftReviewSession session = sampleSession().withWorkingSetContext(new ReviewWorkingSetContext(List.of(
                new ReviewContextChunkSnapshot(
                        "chunk-1",
                        1,
                        "Louki looked back.",
                        "露琪回头看了一眼。",
                        "命名和衔接需复核",
                        List.of("decision-1"),
                        List.of("Louki->露琪"),
                        "上一句引出动作",
                        true
                ),
                new ReviewContextChunkSnapshot(
                        "chunk-2",
                        2,
                        "She opened the cafe door.",
                        "她推开了咖啡馆的门。",
                        "",
                        List.of(),
                        List.of(),
                        "",
                        false
                )
        )));

        String prompt = builder.build(
                session,
                Set.of(ReviewStrategy.KEEP, ReviewStrategy.LIGHT_EDIT),
                List.of("key-evidence-1")
        );

        assertTrue(prompt.contains("[Working Set Text Context]"));
        assertTrue(prompt.contains("chunkId=chunk-1"));
        assertTrue(prompt.contains("sourceText=Louki looked back."));
        assertTrue(prompt.contains("translatedText=露琪回头看了一眼。"));
        assertTrue(prompt.contains("chunkId=chunk-2"));
    }

    @Test
    void shouldDemoteEvidenceSummariesToStateMemoryInEvaluationPrompt() {
        EvaluationPromptBuilder builder = new EvaluationPromptBuilder();
        PostDraftReviewSession session = sampleSession().withWorkingSetContext(new ReviewWorkingSetContext(List.of(
                new ReviewContextChunkSnapshot(
                        "chunk-1",
                        1,
                        "source-1",
                        "translated-1",
                        "",
                        List.of(),
                        List.of(),
                        "",
                        true
                )
        )));

        String prompt = builder.build(
                session,
                Set.of(ReviewStrategy.KEEP, ReviewStrategy.LIGHT_EDIT),
                List.of("key-evidence-1")
        );

        assertTrue(prompt.contains("[State Memory]"));
        assertTrue(prompt.contains("[Key Evidence]"));
        assertTrue(prompt.indexOf("[Working Set Text Context]") < prompt.indexOf("[State Memory]"));
    }

    @Test
    void shouldRenderRevisionPromptWithAllRequiredInputs() {
        RevisionPromptBuilder builder = new RevisionPromptBuilder();
        PostDraftReviewSession session = sampleSession().withWorkingSetContext(new ReviewWorkingSetContext(List.of(
                new ReviewContextChunkSnapshot(
                        "chunk-1",
                        1,
                        "Le Conde etait plein.",
                        "Le Conde Cafe was crowded.",
                        "",
                        List.of(),
                        List.of("Le Conde->Le Conde Cafe"),
                        "",
                        true
                ),
                new ReviewContextChunkSnapshot(
                        "chunk-2",
                        2,
                        "Louki opened the cafe door.",
                        "露琪推开了咖啡馆的门。",
                        "",
                        List.of(),
                        List.of(),
                        "next scene enters cafe",
                        false
                )
        )));
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
        assertTrue(prompt.contains("[Working Set Context]"));
        assertTrue(prompt.contains("chunkId=chunk-2"));
        assertTrue(prompt.contains("sourceText=Louki opened the cafe door."));
        assertNoMojibake(prompt);
    }

    @Test
    void shouldRenderSelfCheckPromptWithConfirmedTermConsistencyRules() {
        RevisionSelfCheckPromptBuilder builder = new RevisionSelfCheckPromptBuilder();
        PostDraftReviewSession session = sampleSession()
                .withWorkingSetContext(new ReviewWorkingSetContext(List.of(
                        new ReviewContextChunkSnapshot(
                                "chunk-1",
                                1,
                                "Le Conde etait plein.",
                                "Le Conde Bistro was crowded.",
                                "",
                                List.of(),
                                List.of("Le Conde->Le Conde Cafe"),
                                "",
                                true
                        ),
                        new ReviewContextChunkSnapshot(
                                "chunk-2",
                                2,
                                "Louki sat beside him.",
                                "露琪坐在他旁边。",
                                "",
                                List.of(),
                                List.of(),
                                "",
                                false
                        )
                )))
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
        assertTrue(prompt.contains("[Working Set Context]"));
        assertTrue(prompt.contains("chunkId=chunk-2"));
        assertTrue(prompt.contains("sourceText=Louki sat beside him."));
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
