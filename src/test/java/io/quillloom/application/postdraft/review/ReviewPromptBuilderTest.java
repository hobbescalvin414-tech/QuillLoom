package io.quillloom.application.postdraft.review;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.quillloom.infrastructure.postdraft.review.OpenAiCompatibleReviewAgentStructuredGenerationClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
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
    void shouldShrinkSystemPromptToLayerAWithoutLegacyAvailableToolsManual() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("[Agent Role]"));
        assertTrue(prompt.contains("[Global Hard Rules]"));
        assertTrue(prompt.contains("[Authority Rules]"));
        assertTrue(prompt.contains("[Global Working Discipline]"));
        assertTrue(prompt.contains("[Global Completion / Escalation Rules]"));
        assertTrue(prompt.contains("[Output Contract]"));
        assertFalse(prompt.contains("[Available Tools]"));
        assertFalse(prompt.contains("Tool: read_confirmed_terms"));
        assertNoMojibake(prompt);
    }

    @Test
    void shouldUseRefactorDesignEnglishForSystemPromptHardRules() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("You are a literary translation review agent."));
        assertTrue(prompt.contains("Do not treat low-priority signals as sufficient grounds for high-risk actions by themselves."));
        assertTrue(prompt.contains("confirmedTermLookupMiss only means there is no current hit; it does not authorize writing confirmed terms."));
        assertTrue(prompt.contains("Strategy is an evaluation result, not a completion signal."));
        assertNoMojibake(prompt);
    }

    @Test
    void shouldExplainWorkingSetIsEvidenceScopeNotSubmitScope() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("[Global Completion / Escalation Rules]"));
        assertTrue(prompt.contains("A readiness signal is only a completion candidate condition."));
        assertTrue(prompt.contains("prefer complete_project instead of continuing the old focus"));
        assertTrue(prompt.contains("do not call complete_working_set for the stale focus"));
    }

    @Test
    void shouldExplainInputFieldAuthorityLevels() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("[Authority Rules]"));
        assertTrue(prompt.contains("sourceText is the highest-authority textual evidence."));
        assertTrue(prompt.contains("read_confirmed_terms is the project-level authoritative lookup."));
        assertTrue(prompt.contains("confirmedTermLookupMiss only means there is no current hit; it does not authorize writing confirmed terms."));
    }

    @Test
    void shouldPreventLowAuthorityNotesFromTriggeringHighRiskActions() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("decisionNotes / translatorCommentary / transitionNote / confirmedTermLookupMiss"));
        assertTrue(prompt.contains("Do not treat low-priority signals as sufficient grounds for high-risk actions by themselves."));
        assertTrue(prompt.contains("Strategy is an evaluation result, not a completion signal."));
    }

    @Test
    void shouldRequireConcreteQuestionForHumanInSystemPrompt() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("Use request_human_review only for real unresolved semantics."));
        assertTrue(prompt.contains("Do not escalate ordinary lack of evidence directly to human review."));
    }

    @Test
    void shouldRequireChineseForHumanVisibleSummaryFieldsInSystemAndInvestigationPrompts() {
        String systemPrompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());
        String investigationPrompt = new InvestigationPromptBuilder()
                .build(sampleSession(), ReviewToolRegistry.defaultRegistry().definitions(), List.of());

        assertTrue(systemPrompt.contains("当前项目优先中文"));
        assertTrue(systemPrompt.contains("reason / questionForHuman"));
        assertTrue(systemPrompt.contains("sourceText 原文引用"));
        assertTrue(investigationPrompt.contains("当前项目默认用中文"));
        assertTrue(investigationPrompt.contains("reason"));
        assertTrue(investigationPrompt.contains("questionForHuman"));
        assertTrue(investigationPrompt.contains("tool 名称"));
    }

    @Test
    void shouldRenderP0BlockingRulesAndRemoveLooseCompletionExitInSystemPrompt() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("[Global Hard Rules]"));
        assertTrue(prompt.contains("Do not call complete_working_set while an unresolved confirmed-term conflict still exists."));
        assertTrue(prompt.contains("Do not advance into an unsupported next stage before the evidence is closed."));
        assertFalse(prompt.contains("not sharp enough"));
    }

    @Test
    void shouldRenderDecisionGateSummaryInInvestigationPrompt() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession();

        String prompt = builder.build(
                session,
                ReviewToolRegistry.defaultRegistry().definitions(),
                List.of("decisionNotes=name consistency may be wrong", "confirmedTermLookupMiss=[Bernolle]")
        );

        assertTrue(prompt.contains("[Decision Gate Summary]"));
        assertTrue(prompt.contains("Identify the current review dimension first"));
        assertTrue(prompt.contains("continuity gate:"));
        assertTrue(prompt.contains("term gate:"));
        assertTrue(prompt.contains("quality gate:"));
        assertTrue(prompt.contains("completion gate:"));
        assertTrue(prompt.contains("If the judgment depends on unread adjacent chunks, read the necessary chunks first."));
        assertTrue(prompt.contains("Not looked up yet: call read_confirmed_terms first."));
        assertTrue(prompt.contains("This dimension handles translation quality issues that can be judged directly from the current chunk's sourceText and translatedText"));
        assertTrue(prompt.contains("Completion may become a candidate next step only when a readiness signal is present"));
        assertFalse(prompt.contains("[Action Tree]"));
        assertFalse(prompt.contains("When toolName=record_confirmed_terms, candidate pairs must be written in arguments.entries, not only in reason."));
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

        assertTrue(systemPrompt.contains("If the issue depends on adjacent text, do not judge it from the anchor chunk alone."));
        assertTrue(investigationPrompt.contains("[Decision Gate Summary]"));
        assertTrue(investigationPrompt.contains("continuity gate:"));
        assertTrue(investigationPrompt.contains("If the judgment depends on unread adjacent chunks, read the necessary chunks first."));
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
        assertTrue(prompt.contains("continuity gate:"));
        assertTrue(prompt.contains("Before the required adjacent reading is complete, do not evaluate_focus and do not complete_working_set."));
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
        assertTrue(prompt.contains("continuity gate:"));
        assertTrue(prompt.contains("read the necessary chunks first."));
    }

    @Test
    void shouldForbidCompleteWorkingSetBeforeRevisionSelfCheckHasPassedInPrompts() {
        PostDraftReviewSession session = sampleSession()
                .withStrategy(ReviewStrategy.LIGHT_EDIT);
        String systemPrompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());
        String investigationPrompt = new InvestigationPromptBuilder()
                .build(session, ReviewToolRegistry.defaultRegistry().definitions(), List.of());

        assertTrue(systemPrompt.contains("Strategy is an evaluation result, not a completion signal."));
        assertTrue(systemPrompt.contains("A readiness signal is only a completion candidate condition."));
        assertTrue(investigationPrompt.contains("existing signals related to revision / self-check / completion: strategy=LIGHT_EDIT"));
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
        assertTrue(prompt.contains("completion gate:"));
        assertTrue(prompt.contains("prefer complete_project."));
    }

    @Test
    void shouldExplainPendingEmptyAndStaleFocusEndgameInSystemPrompt() {
        String prompt = new ReviewAgentSystemPromptBuilder()
                .build(ReviewToolRegistry.defaultRegistry().definitions());

        assertTrue(prompt.contains("prefer complete_project instead of continuing the old focus"));
        assertTrue(prompt.contains("If currentFocusChunkStillPending=false, do not call complete_working_set for the stale focus."));
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
    void shouldRenderInvestigationPromptWithLayerBSectionOrder() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession();

        String prompt = builder.build(
                session,
                ReviewToolRegistry.defaultRegistry().definitions(),
                List.of("evidence-A")
        );

        assertTrue(prompt.contains("[Current Facts]"));
        assertTrue(prompt.contains("[Decision Gate Summary]"));
        assertTrue(prompt.contains("[Working Set Text Context]"));
        assertTrue(prompt.contains("[State Memory]"));
        assertTrue(prompt.contains("[Output Reminder]"));
        assertTrue(prompt.indexOf("[Current Facts]") < prompt.indexOf("[Decision Gate Summary]"));
        assertTrue(prompt.indexOf("[Decision Gate Summary]") < prompt.indexOf("[Working Set Text Context]"));
        assertTrue(prompt.indexOf("[Working Set Text Context]") < prompt.indexOf("[State Memory]"));
        assertTrue(prompt.indexOf("[State Memory]") < prompt.indexOf("[Output Reminder]"));
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
    void shouldRenderCanonicalWorkingSetOrderIntoInvestigationPrompt() {
        InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
        PostDraftReviewSession session = sampleSession().withWorkingSetContext(new ReviewWorkingSetContext(List.of(
                new ReviewContextChunkSnapshot(
                        "chunk-7",
                        7,
                        "source-7",
                        "translated-7",
                        "",
                        List.of(),
                        List.of(),
                        "",
                        true
                ),
                new ReviewContextChunkSnapshot(
                        "chunk-8",
                        8,
                        "source-8",
                        "translated-8",
                        "",
                        List.of(),
                        List.of(),
                        "",
                        false
                ),
                new ReviewContextChunkSnapshot(
                        "chunk-6",
                        6,
                        "source-6",
                        "translated-6",
                        "",
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

        int chunk6Index = prompt.indexOf("chunkId=chunk-6");
        int chunk7Index = prompt.indexOf("chunkId=chunk-7");
        int chunk8Index = prompt.indexOf("chunkId=chunk-8");
        assertTrue(chunk6Index >= 0);
        assertTrue(chunk7Index >= 0);
        assertTrue(chunk8Index >= 0);
        assertTrue(chunk6Index < chunk7Index);
        assertTrue(chunk7Index < chunk8Index);
        assertTrue(prompt.contains("anchor=true"));
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
    void shouldRenderEvaluationPromptWithRefactorOutputContract() {
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

        assertTrue(prompt.contains("[Evaluation Inputs]"));
        assertTrue(prompt.contains("[Evaluation Handoff]"));
        assertTrue(prompt.contains("[Evaluation Task]"));
        assertTrue(prompt.contains("[Evaluation Constraints]"));
        assertTrue(prompt.contains("[Output Contract]"));
        assertTrue(prompt.contains("Key Evidence"));
        assertTrue(prompt.contains("Conflicting Evidence"));
        assertTrue(prompt.contains("Evidence Gaps"));
        assertTrue(prompt.contains("recommendedStrategy"));
        assertTrue(prompt.contains("strategyReason"));
        assertTrue(prompt.contains("evidenceSufficiency"));
        assertTrue(prompt.contains("continueInvestigation"));
        assertTrue(prompt.contains("UNKNOWN / SUFFICIENT / PARTIAL / INSUFFICIENT"));
    }

    @Test
    void shouldRenderRevisionPromptWithRevisionTarget() {
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

        assertTrue(prompt.contains("[Revision Target]"));
        assertTrue(prompt.contains("[Revision Contract]"));
        assertTrue(prompt.contains("[Output Contract]"));
        assertTrue(prompt.contains("issues that must be fixed in this round"));
        assertTrue(prompt.contains("boundary that must not be expanded"));
        assertTrue(prompt.contains("formalTranslation"));
        assertTrue(prompt.contains("revisionMode"));
        assertTrue(prompt.contains("keyRationales"));
        assertTrue(prompt.contains("residualRisks"));
        assertTrue(prompt.contains("The output must be the complete formal translation of the current chunk."));
        assertTrue(prompt.contains("Do not output a diff, a partial fragment"));
        assertNoMojibake(prompt);
    }

    @Test
    void shouldRenderSelfCheckPromptAgainstRevisionTarget() {
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

        assertTrue(prompt.contains("[Self-Check Objective]"));
        assertTrue(prompt.contains("[Self-Check Task]"));
        assertTrue(prompt.contains("[Self-Check Constraints]"));
        assertTrue(prompt.contains("[Output Contract]"));
        assertTrue(prompt.contains("current Revision Target"));
        assertTrue(prompt.contains("passed"));
        assertTrue(prompt.contains("stopReason"));
        assertTrue(prompt.contains("findings"));
        assertFalse(prompt.contains("readiness"));
    }

    @Test
    void shouldKeepArgumentRequirementsAndMinimalStaticSemanticsInInvestigationSchemaDescription() throws Exception {
        OpenAiCompatibleReviewAgentStructuredGenerationClient client =
                new OpenAiCompatibleReviewAgentStructuredGenerationClient(null, new ObjectMapper());
        Method method = OpenAiCompatibleReviewAgentStructuredGenerationClient.class
                .getDeclaredMethod("investigationSchemaDescription");
        method.setAccessible(true);

        String description = (String) method.invoke(client);

        assertTrue(description.contains("Tool read_previous_chunks"));
        assertTrue(description.contains("argumentRequirements=count: integer (required)"));
        assertTrue(description.contains("Tool record_confirmed_terms"));
        assertTrue(description.contains("argumentRequirements=entries: object{string:string} (required)"));
        assertTrue(description.contains("argumentsExample={\"entries\": {"));
        assertTrue(description.contains("<source-term>"));
        assertTrue(description.contains("<target-term>"));
        assertTrue(description.contains("Tool request_human_review"));
        assertTrue(description.contains("Tool complete_working_set"));
        assertTrue(description.contains("Tool complete_project"));
        assertFalse(description.contains("whenToUse="));
        assertFalse(description.contains("whenNotToUse="));
        assertFalse(description.contains("resultSemantics="));
        assertFalse(description.contains("repeatPolicy="));
        assertFalse(description.contains("nextStepGuidance="));
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
