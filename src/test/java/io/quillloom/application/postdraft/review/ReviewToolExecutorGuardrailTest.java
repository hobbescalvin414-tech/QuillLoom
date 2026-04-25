package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ProjectReviewStatus;
import io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation;
import io.quillloom.application.postdraft.review.model.ReviewAgentState;
import io.quillloom.application.postdraft.review.model.ReviewBoundaryWindow;
import io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProjectStopReason;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.ReviewToolExecutionResult;
import io.quillloom.application.postdraft.review.model.ReviewToolTrace;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionMode;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentTermWriter;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;
import io.quillloom.application.postdraft.review.prompt.EvaluationPromptBuilder;
import io.quillloom.application.postdraft.review.service.FocusHumanStopPolicy;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProblemClassifier;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProcessSummaryAssembler;
import io.quillloom.application.postdraft.review.service.PostDraftReviewSessionFactory;
import io.quillloom.application.postdraft.review.service.PostDraftRevisionService;
import io.quillloom.application.postdraft.review.service.PromptBackedRevisionDraftProvider;
import io.quillloom.application.postdraft.review.service.PromptBackedStrategyEvaluationService;
import io.quillloom.application.postdraft.review.service.ReviewToolExecutor;
import io.quillloom.application.postdraft.review.service.ReviewToolGuardrail;
import io.quillloom.application.postdraft.review.service.ReviewToolRegistry;
import io.quillloom.application.postdraft.review.service.WorkingSetCompletionHandler;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftContinuationContext;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.translation.TranslationDecisionNote;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewToolExecutorGuardrailTest {

    @Test
    void shouldAppendGuardrailRejectionIntoCurrentFocusSessionContext() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

        ProjectReviewRuntimeSession nextRuntime = executor.execute(
                runtime,
                new ReviewToolDecision("read_previous_chunks", Map.of(), "need context")
        ).nextRuntime();

        PostDraftReviewSession nextSession = nextRuntime.currentFocusSession().orElseThrow();
        assertTrue(nextSession.transcriptStore().entries().stream()
                .anyMatch(entry -> entry.contains("missing_argument:count")));
        assertTrue(nextSession.historyLog().events().stream()
                .anyMatch(event -> event.detail().contains("missing_argument:count")));
    }

    @Test
    void shouldFailWithNoProgressAfterRepeatedSameGuardrailRejection() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");
        ReviewToolDecision badDecision = new ReviewToolDecision("read_previous_chunks", Map.of(), "need context");

        ProjectReviewRuntimeSession current = runtime;
        for (int i = 0; i < 3; i++) {
            current = executor.execute(current, badDecision).nextRuntime();
        }

        assertEquals(ProjectReviewStatus.FAILED, current.status());
        assertEquals(ReviewProjectStopReason.NO_PROGRESS, current.stopReason());
        assertTrue(current.humanReviewRequest().isEmpty());
        PostDraftReviewSession failedSession = current.currentFocusSession().orElseThrow();
        assertEquals(3, failedSession.diagnostics()
                .consecutiveLocalFailureCount("guardrail:read_previous_chunks:missing_argument:count"));
        assertTrue(failedSession.transcriptStore().entries().stream()
                .anyMatch(entry -> entry.contains("missing_argument:count")));
    }

    @Test
    void shouldExposeMissingChunkIdsForCompleteWorkingSetInsteadOfSilentlyFilling() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision("complete_working_set", Map.of(), "finish now")
        );

        assertFalse(result.success());
        assertEquals("missing_argument:chunkIds", result.rejection().rejectionReason());
        PostDraftReviewSession nextSession = result.nextRuntime().currentFocusSession().orElseThrow();
        assertTrue(nextSession.diagnostics().localRejectionReasons()
                .contains("guardrail:complete_working_set:missing_argument:chunkIds"));
    }

    @Test
    void shouldRejectCompleteWorkingSetWhenChunkIdsOmitAnchor() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunk("chunk-1", "translated-1"),
                chunk("chunk-2", "translated-2")
        ));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");
        PostDraftReviewSession expandedSession = runtime.currentFocusSession().orElseThrow()
                .withStrategy(ReviewStrategy.LIGHT_EDIT)
                .withWorkingSet(io.quillloom.application.postdraft.review.model.ReviewWorkingSet.fromAnchor("chunk-1")
                        .expandTo(List.of("chunk-1", "chunk-2")))
                .withEvaluatingState();
        runtime = runtime.withCurrentFocusSession(expandedSession, runtime.currentFocusRound(), ReviewAgentState.EVALUATING);

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-2")), "submit wrong subset")
        );

        assertFalse(result.success());
        assertEquals(
                "complete_working_set chunkIds must include anchorChunkId=chunk-1",
                result.rejection().rejectionReason()
        );
    }

    @Test
    void shouldRejectCompleteWorkingSetWhenChunkIdsContainAlreadyCompletedChunk() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunk("chunk-4", "translated-4"),
                chunk("chunk-5", "translated-5"),
                chunk("chunk-6", "translated-6")
        ));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-5", "chunk-6"))
                .withSelectedFocus("chunk-5");
        PostDraftReviewSession session = new PostDraftReviewSessionFactory().createProjectFocusSession(
                "project-1",
                "operator note",
                reader.loadChunkById("project-1", "chunk-5").orElseThrow(),
                new PostDraftReviewProblemClassifier().classify(reader.loadChunkById("project-1", "chunk-5").orElseThrow()),
                List.of("seed")
        ).withWorkingSet(io.quillloom.application.postdraft.review.model.ReviewWorkingSet.fromAnchor("chunk-5")
                .expandTo(List.of("chunk-5", "chunk-6", "chunk-4")))
                .withEvaluatingState();
        runtime = runtime.withCurrentFocusSession(session, runtime.currentFocusRound(), ReviewAgentState.EVALUATING);

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-5", "chunk-6", "chunk-4")), "submit with old context")
        );

        assertFalse(result.success());
        assertEquals(
                "complete_working_set currently allows only focusChunk=chunk-5; offendingChunkId=chunk-6",
                result.rejection().rejectionReason()
        );
    }

    @Test
    void shouldRejectCompleteWorkingSetWhenChunkIdsContainNonFocusPendingChunk() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunk("chunk-1", "translated-1"),
                chunk("chunk-2", "translated-2")
        ));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1", "chunk-2"))
                .withSelectedFocus("chunk-1");
        PostDraftReviewSession expandedSession = new PostDraftReviewSessionFactory().createProjectFocusSession(
                "project-1",
                "operator note",
                reader.loadChunkById("project-1", "chunk-1").orElseThrow(),
                new PostDraftReviewProblemClassifier().classify(reader.loadChunkById("project-1", "chunk-1").orElseThrow()),
                List.of("seed")
        ).withStrategy(ReviewStrategy.LIGHT_EDIT)
                .withWorkingSet(io.quillloom.application.postdraft.review.model.ReviewWorkingSet.fromAnchor("chunk-1")
                        .expandTo(List.of("chunk-1", "chunk-2")))
                .withEvaluatingState();
        runtime = runtime.withCurrentFocusSession(expandedSession, runtime.currentFocusRound(), ReviewAgentState.EVALUATING);

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-1", "chunk-2")), "submit anchor plus context chunk")
        );

        assertFalse(result.success());
        assertEquals(
                "complete_working_set currently allows only focusChunk=chunk-1; offendingChunkId=chunk-2",
                result.rejection().rejectionReason()
        );
    }

    @Test
    void shouldRejectDraftRevisionWhenCurrentStrategyIsNotExecutable() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

        ProjectReviewRuntimeSession nextRuntime = executor.execute(
                runtime,
                new ReviewToolDecision("draft_revision", Map.of(), "revise now")
        ).nextRuntime();

        PostDraftReviewSession nextSession = nextRuntime.currentFocusSession().orElseThrow();
        assertEquals(ReviewAgentState.INVESTIGATING, nextRuntime.state());
        assertTrue(nextSession.transcriptStore().entries().stream()
                .anyMatch(entry -> entry.contains("invalid_strategy_for_tool:draft_revision")));
    }

    @Test
    void shouldRejectRepeatedDraftRevisionAfterSuccessfulSelfCheck() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");
        PostDraftReviewSession revisedSession = runtime.currentFocusSession().orElseThrow()
                .withStrategy(ReviewStrategy.LIGHT_EDIT)
                .appendToolTrace(new ReviewToolTrace("draft_revision", "done", List.of(
                        "finalTranslation=revised text",
                        "revisionMode=LIGHT_EDIT",
                        "selfCheckPassed=true"
                )))
                .appendTranscript("draft_revision -> LIGHT_EDIT")
                .withRevisingState();
        runtime = runtime.withCurrentFocusSession(revisedSession, runtime.currentFocusRound(), ReviewAgentState.REVISING);

        ProjectReviewRuntimeSession nextRuntime = executor.execute(
                runtime,
                new ReviewToolDecision("draft_revision", Map.of(), "try again")
        ).nextRuntime();

        PostDraftReviewSession nextSession = nextRuntime.currentFocusSession().orElseThrow();
        assertEquals(ReviewAgentState.REVISING, nextSession.state());
        assertTrue(nextSession.transcriptStore().entries().stream()
                .anyMatch(entry -> entry.contains("redundant_tool_call:draft_revision_after_successful_self_check")));
    }

    @Test
    void shouldReadConfirmedTermsByRequestedSourceTerms() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        reader.confirmedTerms = Map.of("Louki", "Louki CN", "Harbor Master", "Harbor Master CN");
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Louki")), "lookup terms")
        );

        assertTrue(result.success());
        PostDraftReviewSession nextSession = result.nextRuntime().currentFocusSession().orElseThrow();
        assertTrue(nextSession.evidenceBundle().keyEvidenceSummaries().stream()
                .anyMatch(entry -> entry.contains("confirmedTerm=Louki->Louki CN")));
    }

    @Test
    void shouldRecordReadConfirmedTermsWithArgumentsAndResultInTranscript() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        reader.confirmedTerms = Map.of("Le Conde", "Conde Cafe");
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Le Conde")), "lookup term")
        );

        assertTrue(result.success());
        PostDraftReviewSession session = result.nextRuntime().currentFocusSession().orElseThrow();
        assertTrue(session.transcriptStore().entries().stream()
                .anyMatch(entry -> entry.contains("tool_use read_confirmed_terms")
                        && entry.contains("Le Conde")));
        assertTrue(session.transcriptStore().entries().stream()
                .anyMatch(entry -> entry.contains("tool_result read_confirmed_terms")
                        && entry.contains("confirmedTerm=Le Conde->Conde Cafe")));
        assertTrue(session.toolTraces().stream()
                .anyMatch(trace -> trace.callSignature().equals("read_confirmed_terms:sourceTerms=[le conde]")));
        assertTrue(result.summary().contains("confirmedTerm=Le Conde->Conde Cafe"));
    }

    @Test
    void shouldRecordLookupMissesForPartialConfirmedTermHits() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        reader.confirmedTerms = Map.of("La Pergola", "La Pergola CN");
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision(
                        "read_confirmed_terms",
                        Map.of("sourceTerms", List.of("La Pergola", "Le Bouquet")),
                        "lookup visible cafe names"
                )
        );

        assertTrue(result.success());
        assertTrue(result.summary().contains("confirmedTerm=La Pergola->La Pergola CN"));
        assertTrue(result.summary().contains("confirmedTermLookupMiss=[Le Bouquet]"));
        PostDraftReviewSession session = result.nextRuntime().currentFocusSession().orElseThrow();
        assertTrue(session.evidenceBundle().keyEvidenceSummaries().stream()
                .anyMatch(entry -> entry.contains("confirmedTermLookupMiss=[Le Bouquet]")));
        assertTrue(session.transcriptStore().entries().stream()
                .anyMatch(entry -> entry.contains("confirmedTermLookupMiss=[Le Bouquet]")));
    }

    @Test
    void shouldRejectRepeatedSuccessfulReadConfirmedTermsWithSameSourceTerms() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        reader.confirmedTerms = Map.of("Le Conde", "Conde Cafe");
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

        ProjectReviewRuntimeSession afterFirst = executor.execute(
                runtime,
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Le Conde")), "lookup term")
        ).nextRuntime();

        ReviewToolExecutionResult second = executor.execute(
                afterFirst,
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of(" le conde ")), "lookup again")
        );

        assertFalse(second.success());
        assertTrue(second.rejection().rejectionReason().contains("redundant_successful_tool_call"));
        PostDraftReviewSession session = second.nextRuntime().currentFocusSession().orElseThrow();
        assertTrue(session.transcriptStore().entries().stream()
                .anyMatch(entry -> entry.contains("local_replan_hint")));
        assertTrue(session.transcriptStore().entries().stream()
                .anyMatch(entry -> entry.contains("read_confirmed_terms:sourceTerms=[le conde]")
                        && entry.contains("arguments.sourceTerms")));
    }

    @Test
    void shouldRejectRepeatedReadConfirmedTermsAfterLookupMiss() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        reader.confirmedTerms = Map.of();
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

        ProjectReviewRuntimeSession afterFirst = executor.execute(
                runtime,
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Unknown Place")), "lookup term")
        ).nextRuntime();

        ReviewToolExecutionResult second = executor.execute(
                afterFirst,
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of(" unknown place ")), "lookup again")
        );

        assertFalse(second.success());
        assertTrue(second.rejection().rejectionReason().contains("redundant_successful_tool_call"));
    }

    @Test
    void shouldRejectReadConfirmedTermsWhenMixedListContainsPreviouslySuccessfulHit() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        reader.confirmedTerms = Map.of("Le Conde", "Conde Cafe");
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

        ProjectReviewRuntimeSession afterFirst = executor.execute(
                runtime,
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Le Conde")), "lookup term")
        ).nextRuntime();

        ReviewToolExecutionResult second = executor.execute(
                afterFirst,
                new ReviewToolDecision(
                        "read_confirmed_terms",
                        Map.of("sourceTerms", List.of("Le Conde", "Le Bouquet")),
                        "only lookup Le Bouquet"
                )
        );

        assertFalse(second.success());
        assertTrue(second.rejection().rejectionReason().contains("redundant_successful_term_lookup"));
        assertTrue(second.rejection().rejectionReason().contains("le conde"));
        PostDraftReviewSession session = second.nextRuntime().currentFocusSession().orElseThrow();
        assertTrue(session.transcriptStore().entries().stream()
                .anyMatch(entry -> entry.contains("sourceTerm") && entry.contains("Le Bouquet")));
    }

    @Test
    void shouldRejectReadConfirmedTermsWhenMixedListContainsPreviouslySuccessfulMiss() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        reader.confirmedTerms = Map.of();
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

        ProjectReviewRuntimeSession afterFirst = executor.execute(
                runtime,
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Unknown Place")), "lookup term")
        ).nextRuntime();

        ReviewToolExecutionResult second = executor.execute(
                afterFirst,
                new ReviewToolDecision(
                        "read_confirmed_terms",
                        Map.of("sourceTerms", List.of("Unknown Place", "Le Bouquet")),
                        "lookup another term"
                )
        );

        assertFalse(second.success());
        assertTrue(second.rejection().rejectionReason().contains("redundant_successful_term_lookup"));
        assertTrue(second.rejection().rejectionReason().contains("unknown place"));
    }

    @Test
    void shouldFailNoProgressAfterRepeatedDuplicateSuccessfulReadConfirmedTerms() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        reader.confirmedTerms = Map.of("Le Conde", "Conde Cafe");
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession current = initialRuntime(reader, "chunk-1");

        current = executor.execute(
                current,
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Le Conde")), "lookup term")
        ).nextRuntime();

        for (int i = 0; i < 3; i++) {
            current = executor.execute(
                    current,
                    new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Le Conde")), "lookup again")
            ).nextRuntime();
        }

        assertEquals(ProjectReviewStatus.FAILED, current.status());
        assertEquals(ReviewProjectStopReason.NO_PROGRESS, current.stopReason());
        assertTrue(current.humanReviewRequest().isEmpty());
    }

    @Test
    void shouldRecordConfirmedTermsThroughTermWriter() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunkWithConfirmedTermUpdates("chunk-1", "source text", "translated-1", Map.of("Louki", "Louki CN"))
        ));
        RecordingTermWriter termWriter = new RecordingTermWriter();
        ReviewToolExecutor executor = newExecutor(reader, termWriter);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Louki", "Louki CN")), "record term")
        );

        assertTrue(result.success());
        assertEquals(Map.of("Louki", "Louki CN"), termWriter.lastEntries);
        PostDraftReviewSession nextSession = result.nextRuntime().currentFocusSession().orElseThrow();
        assertTrue(nextSession.evidenceBundle().keyEvidenceSummaries().stream()
                .anyMatch(entry -> entry.contains("recordedConfirmedTerm=Louki->Louki CN")));
    }

    @Test
    void shouldRejectRecordConfirmedTermsWhenOnlyDrivenByLookupMissAndLowAuthorityNotes() {
        InMemoryReader reader = new InMemoryReader(List.of(chunkWithTexts(
                "chunk-41",
                "Bernolle entered the room.",
                "Bernolle translated text"
        )));
        RecordingTermWriter termWriter = new RecordingTermWriter();
        ReviewToolExecutor executor = newExecutor(reader, termWriter);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-41");

        ProjectReviewRuntimeSession afterLookupMiss = executor.execute(
                runtime,
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Bernolle")), "lookup Bernolle")
        ).nextRuntime();

        ReviewToolExecutionResult result = executor.execute(
                afterLookupMiss,
                new ReviewToolDecision(
                        "record_confirmed_terms",
                        Map.of("entries", Map.of("Bernolle", "Bernolle translated text")),
                        "confirmedTermLookupMiss plus decisionNotes means the term should be registered"
                )
        );

        assertFalse(result.success());
        assertTrue(result.rejection().rejectionReason().contains("invalid_record_confirmed_terms_basis"));
        assertTrue(termWriter.lastEntries.isEmpty());
        PostDraftReviewSession nextSession = result.nextRuntime().currentFocusSession().orElseThrow();
        assertTrue(nextSession.transcriptStore().entries().stream()
                .anyMatch(entry -> entry.contains("Review Agent does not fill D missing confirmedTermUpdates")));
    }

    @Test
    void shouldAllowLowPrioritySignalsToSupportInvestigationAndEvaluateFocus() {
        InMemoryReader reader = new InMemoryReader(List.of(chunkWithTexts(
                "chunk-45",
                "Bernolle entered the room.",
                "贝尔诺勒走进了房间。"
        )));
        ReviewToolExecutor executor = newExecutor(
                reader,
                new PostDraftRevisionService(
                        new PromptBackedRevisionDraftProvider(new io.quillloom.application.postdraft.review.prompt.RevisionPromptBuilder(), new StubGenerationPort()),
                        (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
                )
        );
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-45");

        ReviewToolExecutionResult readNotes = executor.execute(
                runtime,
                new ReviewToolDecision("read_decision_notes", Map.of(), "decisionNotes suggest checking whether the name stays consistent")
        );
        assertTrue(readNotes.success());

        ReviewToolExecutionResult evaluate = executor.execute(
                readNotes.nextRuntime(),
                new ReviewToolDecision("evaluate_focus", Map.of(), "decisionNotes and translatorCommentary justify another evaluation pass")
        );

        assertTrue(evaluate.success());
    }

    @Test
    void shouldRejectDraftRevisionWhenReasonOnlyUsesLowPrioritySignals() {
        InMemoryReader reader = new InMemoryReader(List.of(chunkWithTexts(
                "chunk-46",
                "Bernolle entered the room.",
                "贝尔诺勒走进了房间。"
        )));
        ReviewToolExecutor executor = newExecutor(
                reader,
                new PostDraftRevisionService(
                        new PromptBackedRevisionDraftProvider(new io.quillloom.application.postdraft.review.prompt.RevisionPromptBuilder(), new StubGenerationPort()),
                        (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
                )
        );
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-46");
        PostDraftReviewSession revisableSession = runtime.currentFocusSession().orElseThrow()
                .withEvidenceBundle(runtime.currentFocusSession().orElseThrow().evidenceBundle().merge(
                        new io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle(
                                List.of(),
                                List.of("decision=name-consistency-risk"),
                                List.of(),
                                List.of(),
                                List.of()
                        )
                ))
                .withStrategy(ReviewStrategy.LIGHT_EDIT)
                .withEvaluatingState();
        runtime = runtime.withCurrentFocusSession(revisableSession, runtime.currentFocusRound(), ReviewAgentState.EVALUATING);

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision(
                        "draft_revision",
                        Map.of(),
                        "decisionNotes plus translatorCommentary plus transitionNote suggest revising"
                )
        );

        assertFalse(result.success());
        assertEquals(
                "invalid_high_risk_action_basis:draft_revision:low_priority_signals_only",
                result.rejection().rejectionReason()
        );
    }

    @Test
    void shouldAllowDraftRevisionWhenSessionAlreadyHasHighAuthorityRuntimeEvidence() {
        InMemoryReader reader = new InMemoryReader(List.of(chunkWithTexts(
                "chunk-46b",
                "Bernolle entered the room.",
                "贝尔诺勒走进了房间。"
        )));
        reader.confirmedTerms = Map.of("Bernolle", "贝尔诺勒");
        ReviewToolExecutor executor = newExecutor(
                reader,
                new PostDraftRevisionService(
                        new PromptBackedRevisionDraftProvider(new io.quillloom.application.postdraft.review.prompt.RevisionPromptBuilder(), new StubGenerationPort()),
                        (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
                )
        );
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-46b");
        ProjectReviewRuntimeSession afterLookup = executor.execute(
                runtime,
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Bernolle")), "lookup visible term")
        ).nextRuntime();
        PostDraftReviewSession revisableSession = afterLookup.currentFocusSession().orElseThrow()
                .withStrategy(ReviewStrategy.LIGHT_EDIT)
                .withEvaluatingState();
        afterLookup = afterLookup.withCurrentFocusSession(revisableSession, afterLookup.currentFocusRound(), ReviewAgentState.EVALUATING);

        ReviewToolExecutionResult result = executor.execute(
                afterLookup,
                new ReviewToolDecision(
                        "draft_revision",
                        Map.of(),
                        "decisionNotes plus translatorCommentary suggest revising"
                )
        );

        assertTrue(result.success());
    }

    @Test
    void shouldRejectHumanReviewWhenReasonOnlyUsesLowPrioritySignalsAndLookupMiss() {
        InMemoryReader reader = new InMemoryReader(List.of(chunkWithTexts(
                "chunk-47",
                "Bernolle entered the room.",
                "贝尔诺勒走进了房间。"
        )));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-47");
        PostDraftReviewSession lowPriorityOnlySession = runtime.currentFocusSession().orElseThrow()
                .withEvidenceBundle(runtime.currentFocusSession().orElseThrow().evidenceBundle().merge(
                        new io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle(
                                List.of(),
                                List.of("confirmedTermLookupMiss=[Bernolle]", "decision=name-consistency-risk"),
                                List.of(),
                                List.of(),
                                List.of()
                        )
                ));
        runtime = runtime.withCurrentFocusSession(lowPriorityOnlySession, runtime.currentFocusRound(), ReviewAgentState.INVESTIGATING);

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision(
                        "request_human_review",
                        Map.of(),
                        "confirmedTermLookupMiss plus decisionNotes plus translatorCommentary means we need human review"
                )
        );

        assertFalse(result.success());
        assertEquals(
                "invalid_high_risk_action_basis:request_human_review:low_priority_signals_only",
                result.rejection().rejectionReason()
        );
    }

    @Test
    void shouldAllowHumanReviewWhenSessionAlreadyHasHighAuthorityConflictingEvidence() {
        InMemoryReader reader = new InMemoryReader(List.of(chunkWithTexts(
                "chunk-47b",
                "Bernolle entered the room.",
                "贝尔诺勒走进了房间。"
        )));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-47b");
        PostDraftReviewSession sessionWithConflict = runtime.currentFocusSession().orElseThrow()
                .withEvidenceBundle(runtime.currentFocusSession().orElseThrow().evidenceBundle().merge(
                        new io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle(
                                List.of(),
                                List.of("contextChunk={chunkId=chunk-47b, sourceText=Bernolle entered the room.}"),
                                List.of("contextChunk={chunkId=chunk-47b, sourceText=Bernolle entered the room.}"),
                                List.of("confirmed term conflict remains unresolved"),
                                List.of()
                        )
                ));
        runtime = runtime.withCurrentFocusSession(sessionWithConflict, runtime.currentFocusRound(), ReviewAgentState.INVESTIGATING);

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision(
                        "request_human_review",
                        Map.of(),
                        "decisionNotes and translatorCommentary still look risky"
                )
        );

        assertTrue(result.success());
    }

    @Test
    void shouldGenerateQuestionForHumanWhenRequestingHumanReview() {
        InMemoryReader reader = new InMemoryReader(List.of(chunkWithTexts(
                "chunk-47c",
                "Louki entered the cafe.",
                "闇插Κ璧拌繘浜嗗挅鍟″簵銆?"
        )));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-47c");
        PostDraftReviewSession sessionWithConflict = runtime.currentFocusSession().orElseThrow()
                .withEvidenceBundle(runtime.currentFocusSession().orElseThrow().evidenceBundle().merge(
                        new io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle(
                                List.of(),
                                List.of("contextChunk={chunkId=chunk-47c, sourceText=Louki entered the cafe.}"),
                                List.of("contextChunk={chunkId=chunk-47c, sourceText=Louki entered the cafe.}"),
                                List.of("confirmed term conflict remains unresolved"),
                                List.of()
                        )
                ));
        runtime = runtime.withCurrentFocusSession(sessionWithConflict, runtime.currentFocusRound(), ReviewAgentState.INVESTIGATING);

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision(
                        "request_human_review",
                        Map.of(),
                        "naming conflict remains unresolved for Louki"
                )
        );

        assertTrue(result.success());
        assertTrue(result.nextRuntime().humanReviewRequest().isPresent());
        assertFalse(result.nextRuntime().humanReviewRequest().orElseThrow().questionForHuman().isBlank());
        assertTrue(result.nextRuntime().humanReviewRequest().orElseThrow().questionForHuman().contains("Louki"));
    }

    @Test
    void shouldGenerateNamingQuestionTemplateForHumanReview() {
        InMemoryReader reader = new InMemoryReader(List.of(chunkWithTexts(
                "chunk-47d",
                "Louki entered the cafe.",
                "露琪走进了咖啡馆。"
        )));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-47d");
        PostDraftReviewSession sessionWithConflict = runtime.currentFocusSession().orElseThrow()
                .withEvidenceBundle(runtime.currentFocusSession().orElseThrow().evidenceBundle().merge(
                        new io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle(
                                List.of(),
                                List.of("contextChunk={chunkId=chunk-47d, sourceText=Louki entered the cafe.}"),
                                List.of("contextChunk={chunkId=chunk-47d, sourceText=Louki entered the cafe.}"),
                                List.of("naming conflict remains unresolved"),
                                List.of()
                        )
                ));
        runtime = runtime.withCurrentFocusSession(sessionWithConflict, runtime.currentFocusRound(), ReviewAgentState.INVESTIGATING);

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision(
                        "request_human_review",
                        Map.of(),
                        "naming consistency for Louki remains unresolved"
                )
        );

        assertTrue(result.success());
        String question = result.nextRuntime().humanReviewRequest().orElseThrow().questionForHuman();
        assertTrue(question.contains("Louki"));
        assertTrue(question.contains("译名") || question.contains("术语"));
    }

    @Test
    void shouldFallbackToGenericHumanQuestionWithoutLeakingInternalReason() {
        InMemoryReader reader = new InMemoryReader(List.of(chunkWithTexts(
                "chunk-47e",
                "The room fell silent.",
                "房间突然安静下来。"
        )));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-47e");
        PostDraftReviewSession sessionWithConflict = runtime.currentFocusSession().orElseThrow()
                .withEvidenceBundle(runtime.currentFocusSession().orElseThrow().evidenceBundle().merge(
                        new io.quillloom.application.postdraft.review.model.ReviewEvidenceBundle(
                                List.of(),
                                List.of("contextChunk={chunkId=chunk-47e, sourceText=The room fell silent.}"),
                                List.of("contextChunk={chunkId=chunk-47e, sourceText=The room fell silent.}"),
                                List.of("unresolved semantic conflict"),
                                List.of()
                        )
                ));
        runtime = runtime.withCurrentFocusSession(sessionWithConflict, runtime.currentFocusRound(), ReviewAgentState.INVESTIGATING);

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision(
                        "request_human_review",
                        Map.of(),
                        "self_check_budget_exhausted"
                )
        );

        assertTrue(result.success());
        String question = result.nextRuntime().humanReviewRequest().orElseThrow().questionForHuman();
        assertFalse(question.isBlank());
        assertFalse(question.contains("self_check_budget_exhausted"));
        assertTrue(question.contains("请") || question.contains("审阅"));
    }

    @Test
    void shouldRejectBernolleWriteTableAttemptWhenOnlyMissNotesAndCommentaryAreCited() {
        InMemoryReader reader = new InMemoryReader(List.of(chunkWithTexts(
                "chunk-48",
                "Bernolle entered the room.",
                "贝尔诺勒走进了房间。"
        )));
        RecordingTermWriter termWriter = new RecordingTermWriter();
        ReviewToolExecutor executor = newExecutor(reader, termWriter);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-48");

        ProjectReviewRuntimeSession afterLookupMiss = executor.execute(
                runtime,
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Bernolle")), "lookup Bernolle")
        ).nextRuntime();

        ReviewToolExecutionResult result = executor.execute(
                afterLookupMiss,
                new ReviewToolDecision(
                        "record_confirmed_terms",
                        Map.of("entries", Map.of("Bernolle", "贝尔诺勒")),
                        "confirmedTermLookupMiss plus decisionNotes plus translatorCommentary means Bernolle should be registered"
                )
        );

        assertFalse(result.success());
        assertTrue(result.rejection().rejectionReason().contains("invalid_record_confirmed_terms_basis"));
        assertTrue(termWriter.lastEntries.isEmpty());
    }

    @Test
    void shouldRejectRecordConfirmedTermsWhenOnlySupportedBySourceTargetAlignment() {
        InMemoryReader reader = new InMemoryReader(List.of(chunkWithTexts(
                "chunk-42",
                "Bernolle entered the room.",
                "Bernolle entered the room in translation."
        )));
        RecordingTermWriter termWriter = new RecordingTermWriter();
        ReviewToolExecutor executor = newExecutor(reader, termWriter);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-42");

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision(
                        "record_confirmed_terms",
                        Map.of("entries", Map.of("Bernolle", "Bernolle CN")),
                        "sourceText/translatedText align to the same term"
                )
        );

        assertFalse(result.success());
        assertTrue(result.rejection().rejectionReason().contains("invalid_record_confirmed_terms_basis"));
        assertTrue(termWriter.lastEntries.isEmpty());
    }

    @Test
    void shouldAllowRecordConfirmedTermsWhenSupportedByCurrentWorkingSetConfirmedTermUpdates() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunkWithConfirmedTermUpdates("chunk-43", "source text", "translated text", Map.of("Bernolle", "Bernolle CN")),
                chunkWithTexts("chunk-44", "other source", "other translation")
        ));
        RecordingTermWriter termWriter = new RecordingTermWriter();
        ReviewToolExecutor executor = newExecutor(reader, termWriter);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-43");
        PostDraftReviewSession expandedSession = runtime.currentFocusSession().orElseThrow()
                .withWorkingSet(io.quillloom.application.postdraft.review.model.ReviewWorkingSet.fromAnchor("chunk-43")
                        .expandTo(List.of("chunk-43", "chunk-44")));
        runtime = runtime.withInvestigatingFocusSession(expandedSession, runtime.currentFocusRound());

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision(
                        "record_confirmed_terms",
                        Map.of("entries", Map.of("Bernolle", "Bernolle CN")),
                        "record supported confirmed term"
                )
        );

        assertTrue(result.success());
        assertEquals(Map.of("Bernolle", "Bernolle CN"), termWriter.lastEntries);
    }

    @Test
    void shouldExposeFullContextChunkSnapshotWhenReadingAdjacentChunks() {
        InMemoryReader reader = new InMemoryReader(List.of(new PostDraftChunkRecord(
                "chunk-1",
                1,
                "block-1",
                "Louki looked back.",
                "Louki looked back in translation.",
                "keep action continuity",
                List.of(new TranslationDecisionNote("FLOW", "anchor", "need street context", "read adjacent chunk")),
                Map.of("Louki", "Louki CN"),
                List.of(new TranslationCandidateUpdate("Harbor Master", "Harbor Master CN", "keep consistent", false)),
                new ChunkTransitionNote("previous sentence ends motion", "next sentence introduces title", false)
        )));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision("read_next_chunks", Map.of("count", 1), "need more context")
        );

        assertTrue(result.success());
        PostDraftReviewSession nextSession = result.nextRuntime().currentFocusSession().orElseThrow();
        assertTrue(nextSession.evidenceBundle().evidenceSummaries().stream()
                .anyMatch(entry -> entry.contains("contextChunk={chunkId=chunk-1")));
        assertTrue(nextSession.evidenceBundle().evidenceSummaries().stream()
                .anyMatch(entry -> entry.contains("sourceText=Louki looked back.")));
        assertTrue(nextSession.evidenceBundle().evidenceSummaries().stream()
                .anyMatch(entry -> entry.contains("translatedText=")));
        assertTrue(nextSession.evidenceBundle().evidenceSummaries().stream()
                .anyMatch(entry -> entry.contains("decisionNotes=[{type=FLOW")));
        assertTrue(nextSession.evidenceBundle().evidenceSummaries().stream()
                .noneMatch(entry -> entry.contains("candidateUpdates")));
    }

    @Test
    void shouldReadNextChunksFromBoundaryWindowRightEdgeInsteadOfFocusChunk() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunk("chunk-1", 1, "translated-1"),
                chunk("chunk-2", 2, "translated-2"),
                chunk("chunk-3", 3, "translated-3"),
                chunk("chunk-4", 4, "translated-4")
        ));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.initialize(
                "project-1",
                List.of("chunk-2", "chunk-3", "chunk-4")
        ).withSelectedFocus("chunk-2");
        PostDraftReviewSession session = new PostDraftReviewSessionFactory().createProjectFocusSession(
                "project-1",
                "operator note",
                reader.loadChunkById("project-1", "chunk-2").orElseThrow(),
                new PostDraftReviewProblemClassifier().classify(reader.loadChunkById("project-1", "chunk-2").orElseThrow()),
                List.of("seed")
        ).withWorkingSet(io.quillloom.application.postdraft.review.model.ReviewWorkingSet.fromAnchor("chunk-2")
                .expandTo(List.of("chunk-2", "chunk-3")))
                .withBoundaryWindow(new ReviewBoundaryWindow(List.of(
                        new ReviewContextChunkSnapshot("chunk-2", 2, "source-2", "translated-2", "", List.of(), List.of(), "", true),
                        new ReviewContextChunkSnapshot("chunk-3", 3, "source-3", "translated-3", "", List.of(), List.of(), "", false)
                )));
        runtime = runtime.withInvestigatingFocusSession(session, 0);

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision("read_next_chunks", Map.of("count", 1), "need next boundary chunk")
        );

        assertTrue(result.success());
        assertEquals("chunk-3", reader.lastReadAdjacentChunkId);
        assertEquals(List.of("chunk-2", "chunk-3", "chunk-4"),
                result.nextRuntime().currentFocusSession().orElseThrow().workingSet().chunkIds());
    }

    @Test
    void shouldTrackReadInFocusChunkIdsWhenReadingAdjacentChunks() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunk("chunk-1", 1, "translated-1"),
                chunk("chunk-2", 2, "translated-2")
        ));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision("read_next_chunks", Map.of("count", 1), "need right context")
        );

        assertTrue(result.success());
        PostDraftReviewSession nextSession = result.nextRuntime().currentFocusSession().orElseThrow();
        assertEquals(Set.of("chunk-1", "chunk-2"), nextSession.readInFocusChunkIds());
        assertTrue(nextSession.verifiedInFocusChunkIds().isEmpty());
    }

    @Test
    void shouldWriteReadChunksIntoWorkingSetContextAndOverwriteDuplicateSnapshot() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunk("chunk-1", 1, "translated-1-old"),
                chunk("chunk-2", 2, "translated-2")
        ));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");
        PostDraftReviewSession seeded = runtime.currentFocusSession().orElseThrow()
                .withWorkingSetContext(new io.quillloom.application.postdraft.review.model.ReviewWorkingSetContext(List.of(
                        new io.quillloom.application.postdraft.review.model.ReviewContextChunkSnapshot(
                                "chunk-1", 1, "source-1-old", "translated-1-old", "", List.of(), List.of(), "", true)
                )));
        runtime = runtime.withCurrentFocusSession(seeded, runtime.currentFocusRound(), ReviewAgentState.INVESTIGATING);

        ReviewToolExecutionResult result = executor.execute(
                runtime,
                new ReviewToolDecision("read_next_chunks", Map.of("count", 1), "need right context")
        );

        assertTrue(result.success());
        PostDraftReviewSession nextSession = result.nextRuntime().currentFocusSession().orElseThrow();
        assertEquals(2, nextSession.workingSetContext().snapshots().size());
        assertTrue(nextSession.workingSetContext().snapshots().stream()
                .anyMatch(snapshot -> snapshot.chunkId().equals("chunk-1")
                        && snapshot.sourceText().equals("source text 1")));
        assertTrue(nextSession.workingSetContext().snapshots().stream()
                .anyMatch(snapshot -> snapshot.chunkId().equals("chunk-2")
                        && snapshot.translatedText().equals("translated-2")));
    }

    @Test
    void shouldFailWithNoProgressAfterRepeatedRedundantDraftRevision() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        ReviewToolExecutor executor = newExecutor(reader);
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");
        PostDraftReviewSession revisedSession = runtime.currentFocusSession().orElseThrow()
                .withStrategy(ReviewStrategy.LIGHT_EDIT)
                .appendToolTrace(new ReviewToolTrace("draft_revision", "done", List.of(
                        "finalTranslation=revised text",
                        "revisionMode=LIGHT_EDIT",
                        "selfCheckPassed=true"
                )))
                .appendTranscript("revision_ready_for_completion -> complete_working_set chunkIds=[chunk-1]")
                .withEvaluatingState();
        runtime = runtime.withCurrentFocusSession(revisedSession, runtime.currentFocusRound(), ReviewAgentState.EVALUATING);

        ProjectReviewRuntimeSession current = runtime;
        for (int i = 0; i < 3; i++) {
            current = executor.execute(
                    current,
                    new ReviewToolDecision("draft_revision", Map.of(), "try again")
            ).nextRuntime();
        }

        assertEquals(ProjectReviewStatus.FAILED, current.status());
        assertEquals(ReviewProjectStopReason.NO_PROGRESS, current.stopReason());
        assertTrue(current.humanReviewRequest().isEmpty());
        PostDraftReviewSession nextSession = current.currentFocusSession().orElseThrow();
        assertTrue(nextSession.transcriptStore().entries().stream()
                .anyMatch(entry -> entry.contains("stop_reason -> no_progress")));
    }

    @Test
    void shouldRejectRevisionDraftGenerationFailureWithoutCrashing() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        ReviewToolExecutor executor = newExecutor(
                reader,
                new PostDraftRevisionService(
                        (session, chunk, strategy) -> {
                            throw new IllegalStateException("revision draft generation failed after retry");
                        },
                        (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
                )
        );
        ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");
        PostDraftReviewSession revisableSession = runtime.currentFocusSession().orElseThrow()
                .withStrategy(ReviewStrategy.LIGHT_EDIT)
                .withEvaluatingState();
        runtime = runtime.withCurrentFocusSession(revisableSession, runtime.currentFocusRound(), ReviewAgentState.EVALUATING);

        ProjectReviewRuntimeSession nextRuntime = executor.execute(
                runtime,
                new ReviewToolDecision("draft_revision", Map.of(), "revise now")
        ).nextRuntime();

        assertEquals(ReviewAgentState.EVALUATING, nextRuntime.state());
        assertTrue(nextRuntime.humanReviewRequest().isEmpty());
        PostDraftReviewSession nextSession = nextRuntime.currentFocusSession().orElseThrow();
        assertTrue(nextSession.transcriptStore().entries().stream()
                .anyMatch(entry -> entry.contains("revision_draft_generation_failed")));
    }

    private static ProjectReviewRuntimeSession initialRuntime(InMemoryReader reader, String chunkId) {
        PostDraftChunkRecord chunk = reader.loadChunkById("project-1", chunkId).orElseThrow();
        PostDraftReviewSession session = new PostDraftReviewSessionFactory().createProjectFocusSession(
                "project-1",
                "operator note",
                chunk,
                new PostDraftReviewProblemClassifier().classify(chunk),
                List.of("translatedTextLength=" + chunk.translatedText().length())
        );
        return ProjectReviewRuntimeSession.initialize("project-1", List.of(chunkId))
                .withSelectedFocus(chunkId)
                .withInvestigatingFocusSession(session, 0);
    }

    private static ReviewToolExecutor newExecutor(InMemoryReader reader) {
        return newExecutor(reader, PostDraftReviewAgentTermWriter.noop());
    }

    private static ReviewToolExecutor newExecutor(InMemoryReader reader,
                                                  PostDraftReviewAgentTermWriter termWriter) {
        return newExecutor(
                reader,
                termWriter,
                new PostDraftRevisionService(
                        new PromptBackedRevisionDraftProvider(),
                        (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
                )
        );
    }

    private static ReviewToolExecutor newExecutor(InMemoryReader reader,
                                                  PostDraftRevisionService revisionService) {
        return newExecutor(reader, PostDraftReviewAgentTermWriter.noop(), revisionService);
    }

    private static ReviewToolExecutor newExecutor(InMemoryReader reader,
                                                  PostDraftReviewAgentTermWriter termWriter,
                                                  PostDraftRevisionService revisionService) {
        return new ReviewToolExecutor(
                ReviewToolRegistry.defaultRegistry(),
                new ReviewToolGuardrail(),
                reader,
                termWriter,
                new PromptBackedStrategyEvaluationService(new EvaluationPromptBuilder(), new StubGenerationPort()),
                revisionService,
                new WorkingSetCompletionHandler(reader, new PostDraftReviewProcessSummaryAssembler()),
                new PostDraftReviewProcessSummaryAssembler(),
                new FocusHumanStopPolicy(1, 1)
        );
    }

    private static PostDraftChunkRecord chunk(String chunkId, String translatedText) {
        return chunkWithTexts(chunkId, "source text", translatedText);
    }

    private static PostDraftChunkRecord chunk(String chunkId, int sequence, String translatedText) {
        return new PostDraftChunkRecord(
                chunkId,
                sequence,
                "block-1",
                "source text " + sequence,
                translatedText,
                "commentary",
                List.of(),
                Map.of(),
                List.<TranslationCandidateUpdate>of(),
                null
        );
    }

    private static PostDraftChunkRecord chunkWithTexts(String chunkId, String sourceText, String translatedText) {
        return chunkWithConfirmedTermUpdates(chunkId, sourceText, translatedText, Map.of());
    }

    private static PostDraftChunkRecord chunkWithConfirmedTermUpdates(String chunkId,
                                                                      String sourceText,
                                                                      String translatedText,
                                                                      Map<String, String> confirmedTermUpdates) {
        return new PostDraftChunkRecord(
                chunkId,
                1,
                "block-1",
                sourceText,
                translatedText,
                "commentary",
                List.of(),
                confirmedTermUpdates,
                List.<TranslationCandidateUpdate>of(),
                null
        );
    }

    private static final class StubGenerationPort implements ReviewAgentStructuredGenerationPort {

        @Override
        public ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt) {
            return new ReviewAgentEvaluation(
                    ReviewStrategy.KEEP,
                    "enough evidence",
                    io.quillloom.application.postdraft.review.model.EvidenceSufficiency.SUFFICIENT,
                    false
            );
        }

        @Override
        public RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt) {
            return new RevisionDraft("revised", RevisionMode.LIGHT_EDIT, List.of(), List.of());
        }

        @Override
        public RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt) {
            return new RevisionSelfCheckResult(true, "", List.of());
        }
    }

    private static final class InMemoryReader implements PostDraftReviewAgentReader {
        private final List<PostDraftChunkRecord> chunks;
        private Map<String, String> confirmedTerms = Map.of();
        private String lastReadAdjacentChunkId = "";

        private InMemoryReader(List<PostDraftChunkRecord> chunks) {
            this.chunks = List.copyOf(chunks);
        }

        @Override
        public PostDraftContinuationContext loadContinuationContext(String projectId, ReviewFocus focus) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PostDraftChunkRecord> readContinuousChunks(String projectId, String chunkId, io.quillloom.application.postdraft.review.model.ReviewReadDirection direction, int steps) {
            return chunks;
        }

        @Override
        public List<PostDraftChunkRecord> expandByBlock(String projectId, String chunkId) {
            return chunks;
        }

        @Override
        public List<TranslationDecisionNote> readDecisionNotes(String projectId, String chunkId) {
            return List.of();
        }

        @Override
        public Optional<ChunkTransitionNote> readTransitionNote(String projectId, String chunkId) {
            return Optional.empty();
        }

        @Override
        public List<KnowledgeCard> lookupKnowledgeCards(String projectId, String chunkId, List<String> queryTerms) {
            return List.of();
        }

        @Override
        public List<PostDraftChunkRecord> readAdjacentChunks(String projectId, String chunkId, int before, int after) {
            lastReadAdjacentChunkId = chunkId;
            int index = indexOf(chunkId);
            int from = Math.max(0, index - before);
            int to = Math.min(chunks.size(), index + after + 1);
            return chunks.subList(from, to);
        }

        @Override
        public List<PostDraftChunkRecord> searchChunksByKeyword(String projectId, String keyword) {
            return List.of();
        }

        @Override
        public List<String> listChunkIdsByProject(String projectId) {
            return chunks.stream().map(PostDraftChunkRecord::chunkId).toList();
        }

        @Override
        public Optional<PostDraftChunkRecord> loadChunkById(String projectId, String chunkId) {
            return chunks.stream().filter(chunk -> chunk.chunkId().equals(chunkId)).findFirst();
        }

        @Override
        public Map<String, String> readConfirmedTerms(String projectId, List<String> sourceTerms) {
            if (sourceTerms == null) {
                return Map.of();
            }
            return confirmedTerms.entrySet().stream()
                    .filter(entry -> sourceTerms.contains(entry.getKey()))
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (left, right) -> left,
                            java.util.LinkedHashMap::new
                    ));
        }

        private int indexOf(String chunkId) {
            for (int index = 0; index < chunks.size(); index++) {
                if (chunks.get(index).chunkId().equals(chunkId)) {
                    return index;
                }
            }
            throw new IllegalArgumentException("Unknown chunkId: " + chunkId);
        }
    }

    private static final class RecordingTermWriter implements PostDraftReviewAgentTermWriter {
        private Map<String, String> lastEntries = Map.of();

        @Override
        public Map<String, String> recordConfirmedTerms(String projectId, Map<String, String> entries) {
            lastEntries = Map.copyOf(entries);
            return lastEntries;
        }
    }
}

