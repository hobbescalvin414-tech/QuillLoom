package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.HumanReviewRequest;
import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome;
import io.quillloom.application.postdraft.review.model.ProjectIssueBacklog;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ReviewProjectStopReason;
import io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation;
import io.quillloom.application.postdraft.review.model.ReviewAgentState;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.RecordConfirmedTermEntry;
import io.quillloom.application.postdraft.review.model.RecordConfirmedTermsProposal;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionMode;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.application.postdraft.review.port.out.LlmStructuredOutputException;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentTermWriter;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;
import io.quillloom.application.postdraft.review.prompt.EvaluationPromptBuilder;
import io.quillloom.application.postdraft.review.prompt.InvestigationPromptBuilder;
import io.quillloom.application.postdraft.review.service.AutonomousProjectReviewAgent;
import io.quillloom.application.postdraft.review.service.FocusHumanStopPolicy;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProblemClassifier;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProcessSummaryAssembler;
import io.quillloom.application.postdraft.review.service.PostDraftReviewSessionFactory;
import io.quillloom.application.postdraft.review.service.PostDraftRevisionService;
import io.quillloom.application.postdraft.review.service.PromptBackedNextStepDecisionProvider;
import io.quillloom.application.postdraft.review.service.PromptBackedRevisionDraftProvider;
import io.quillloom.application.postdraft.review.service.PromptBackedStrategyEvaluationService;
import io.quillloom.application.postdraft.review.service.ReviewToolExecutor;
import io.quillloom.application.postdraft.review.service.ReviewToolGuardrail;
import io.quillloom.application.postdraft.review.service.ReviewToolRegistry;
import io.quillloom.application.postdraft.review.service.ReviewRuntimeVisualizer;
import io.quillloom.application.postdraft.review.service.SequenceProjectFocusSelector;
import io.quillloom.application.postdraft.review.service.WorkingSetCompletionHandler;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftContinuationContext;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.translation.TranslationDecisionNote;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomousProjectReviewAgentTest {

    @Test
    void shouldExpandFromAnchorToWorkingSetAndCompleteTwoChunks() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunk("chunk-1", "translated-1"),
                chunk("chunk-2", "translated-2"),
                chunk("chunk-3", "translated-3")
        ));
        SequenceGenerationPort generationPort = new SequenceGenerationPort(
                new ReviewToolDecision("read_next_chunks", Map.of("count", 1), "need more context"),
                new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-1", "chunk-2")), "done"),
                new ReviewToolDecision("request_human_review", Map.of(), "stop after chunk-3")
        );


        AutonomousProjectReviewAgent agent = new AutonomousProjectReviewAgent(
                reader,
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new SequenceProjectFocusSelector(),
                new PromptBackedNextStepDecisionProvider(
                        new InvestigationPromptBuilder(),
                        ReviewToolRegistry.defaultRegistry(),
                        generationPort
                ),
                new ReviewToolExecutor(
                        ReviewToolRegistry.defaultRegistry(),
                        new ReviewToolGuardrail(),
                        reader,
                        PostDraftReviewAgentTermWriter.noop(),
                        new PromptBackedStrategyEvaluationService(new EvaluationPromptBuilder(), generationPort),
                        new PostDraftRevisionService(
                                new PromptBackedRevisionDraftProvider(),
                                (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
                        ),
                        new WorkingSetCompletionHandler(reader, new PostDraftReviewProcessSummaryAssembler()),
                        new PostDraftReviewProcessSummaryAssembler(),
                        new FocusHumanStopPolicy(1, 1)
                ),
                ReviewRuntimeVisualizer.noop()
        );

        ProjectReviewRuntimeSession result = agent.run(
                ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1", "chunk-2", "chunk-3")),
                "operator note"
        );

        assertEquals(List.of("chunk-3"), result.pendingChunkIds());
        assertEquals(2, result.completedChunkOutcomes().size());
        assertEquals(ReviewAgentState.WAITING_HUMAN, result.state());
        assertTrue(result.humanReviewRequest().isPresent());
    }

    @Test
    void shouldRequireExplicitCompleteProjectAfterLastWorkingSet() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        SequenceGenerationPort generationPort = new SequenceGenerationPort(
                new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-1")), "done"),
                new ReviewToolDecision("complete_project", Map.of(), "all chunks done")
        );

        AutonomousProjectReviewAgent agent = new AutonomousProjectReviewAgent(
                reader,
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new SequenceProjectFocusSelector(),
                new PromptBackedNextStepDecisionProvider(
                        new InvestigationPromptBuilder(),
                        ReviewToolRegistry.defaultRegistry(),
                        generationPort
                ),
                new ReviewToolExecutor(
                        ReviewToolRegistry.defaultRegistry(),
                        new ReviewToolGuardrail(),
                        reader,
                        PostDraftReviewAgentTermWriter.noop(),
                        new PromptBackedStrategyEvaluationService(new EvaluationPromptBuilder(), generationPort),
                        new PostDraftRevisionService(
                                new PromptBackedRevisionDraftProvider(),
                                (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
                        ),
                        new WorkingSetCompletionHandler(reader, new PostDraftReviewProcessSummaryAssembler()),
                        new PostDraftReviewProcessSummaryAssembler(),
                        new FocusHumanStopPolicy(1, 1)
                ),
                ReviewRuntimeVisualizer.noop()
        );

        ProjectReviewRuntimeSession result = agent.run(
                ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1")),
                "operator note"
        );

        assertEquals(0, result.pendingChunkIds().size());
        assertEquals(1, result.completedChunkOutcomes().size());
        assertEquals(ReviewAgentState.COMPLETED, result.state());
    }

    @Test
    void shouldInjectFullAnchorChunkSnapshotIntoInitialPrompt() {
        InMemoryReader reader = new InMemoryReader(List.of(new PostDraftChunkRecord(
                "chunk-1",
                1,
                "block-1",
                "Louki looked back.",
                "露姬回头看了一眼。",
                "需要确认人名是否统一",
                List.of(new TranslationDecisionNote("NAME", "Louki", "人名译法待确认", "优先沿用已确认译名")),
                Map.of("Louki", "露姬"),
                List.of(new TranslationCandidateUpdate("Harbor Master", "港务长", "项目内保持一致", false)),
                new ChunkTransitionNote("上一句是动作承接", "下一句引出书名", false)
        )));
        SequenceGenerationPort generationPort = new SequenceGenerationPort(
                new ReviewToolDecision("request_human_review", Map.of(), "stop")
        );

        AutonomousProjectReviewAgent agent = new AutonomousProjectReviewAgent(
                reader,
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new SequenceProjectFocusSelector(),
                new PromptBackedNextStepDecisionProvider(
                        new InvestigationPromptBuilder(),
                        ReviewToolRegistry.defaultRegistry(),
                        generationPort
                ),
                new ReviewToolExecutor(
                        ReviewToolRegistry.defaultRegistry(),
                        new ReviewToolGuardrail(),
                        reader,
                        PostDraftReviewAgentTermWriter.noop(),
                        new PromptBackedStrategyEvaluationService(new EvaluationPromptBuilder(), generationPort),
                        new PostDraftRevisionService(
                                new PromptBackedRevisionDraftProvider(),
                                (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
                        ),
                        new WorkingSetCompletionHandler(reader, new PostDraftReviewProcessSummaryAssembler()),
                        new PostDraftReviewProcessSummaryAssembler(),
                        new FocusHumanStopPolicy(1, 1)
                ),
                ReviewRuntimeVisualizer.noop()
        );

        agent.run(
                ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1")),
                "operator note"
        );

        String prompt = generationPort.latestPrompt();
        assertTrue(prompt.contains("anchorChunk={chunkId=chunk-1"));
        assertTrue(prompt.contains("sourceText=Louki looked back."));
        assertTrue(prompt.contains("translatedText=露姬回头看了一眼。"));
        assertTrue(prompt.contains("translatorCommentary=需要确认人名是否统一"));
        assertTrue(prompt.contains("decisionNotes=[{type=NAME"));
        assertTrue(prompt.contains("confirmedTermUpdates={Louki=露姬}"));
        assertFalse(prompt.contains("candidateUpdates"));
        assertTrue(prompt.contains("transitionNote={previousChunkConnection=上一句是动作承接"));
    }

    @Test
    void shouldFailWhenWallClockTimeoutIsReached() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        SequenceGenerationPort generationPort = new SequenceGenerationPort(
                new ReviewToolDecision("read_next_chunks", Map.of("count", 1), "keep going")
        );
        AtomicLong nanos = new AtomicLong(0L);

        AutonomousProjectReviewAgent agent = new AutonomousProjectReviewAgent(
                reader,
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new SequenceProjectFocusSelector(),
                new PromptBackedNextStepDecisionProvider(
                        new InvestigationPromptBuilder(),
                        ReviewToolRegistry.defaultRegistry(),
                        generationPort
                ),
                new ReviewToolExecutor(
                        ReviewToolRegistry.defaultRegistry(),
                        new ReviewToolGuardrail(),
                        reader,
                        PostDraftReviewAgentTermWriter.noop(),
                        new PromptBackedStrategyEvaluationService(new EvaluationPromptBuilder(), generationPort),
                        new PostDraftRevisionService(
                                new PromptBackedRevisionDraftProvider(),
                                (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
                        ),
                        new WorkingSetCompletionHandler(reader, new PostDraftReviewProcessSummaryAssembler()),
                        new PostDraftReviewProcessSummaryAssembler(),
                        new FocusHumanStopPolicy(1, 1)
                ),
                ReviewRuntimeVisualizer.noop(),
                io.quillloom.application.postdraft.review.service.ProjectReviewRuntimePersistenceHook.noop(),
                io.quillloom.application.postdraft.review.model.ReviewAgentConfig.defaultConfig(),
                1,
                () -> nanos.getAndAdd(java.time.Duration.ofMinutes(2).toNanos())
        );

        ProjectReviewRuntimeSession result = agent.run(
                ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1")),
                "operator note"
        );

        assertEquals(ReviewAgentState.FAILED, result.state());
        assertEquals(ReviewProjectStopReason.WALL_CLOCK_TIMEOUT, result.stopReason());
    }

    @Test
    void shouldTreatZeroWallClockTimeoutAsDisabled() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        SequenceGenerationPort generationPort = new SequenceGenerationPort(
                new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-1")), "done"),
                new ReviewToolDecision("complete_project", Map.of(), "finish")
        );

        AutonomousProjectReviewAgent agent = new AutonomousProjectReviewAgent(
                reader,
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new SequenceProjectFocusSelector(),
                new PromptBackedNextStepDecisionProvider(
                        new InvestigationPromptBuilder(),
                        ReviewToolRegistry.defaultRegistry(),
                        generationPort
                ),
                new ReviewToolExecutor(
                        ReviewToolRegistry.defaultRegistry(),
                        new ReviewToolGuardrail(),
                        reader,
                        PostDraftReviewAgentTermWriter.noop(),
                        new PromptBackedStrategyEvaluationService(new EvaluationPromptBuilder(), generationPort),
                        new PostDraftRevisionService(
                                new PromptBackedRevisionDraftProvider(),
                                (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
                        ),
                        new WorkingSetCompletionHandler(reader, new PostDraftReviewProcessSummaryAssembler()),
                        new PostDraftReviewProcessSummaryAssembler(),
                        new FocusHumanStopPolicy(1, 1)
                ),
                ReviewRuntimeVisualizer.noop(),
                io.quillloom.application.postdraft.review.service.ProjectReviewRuntimePersistenceHook.noop(),
                io.quillloom.application.postdraft.review.model.ReviewAgentConfig.defaultConfig(),
                0
        );

        ProjectReviewRuntimeSession result = agent.run(
                ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1")),
                "operator note"
        );

        assertEquals(ReviewAgentState.COMPLETED, result.state());
        assertEquals(ReviewProjectStopReason.PROJECT_COMPLETED, result.stopReason());
    }

    @Test
    void shouldContainCurrentFocusWhenNextStepStructuredOutputFails() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunk("chunk-1", "translated-1"),
                chunk("chunk-2", "translated-2")
        ));
        ReviewAgentStructuredGenerationPort generationPort = new MixedGenerationPort(
                List.of(
                        new LlmStructuredOutputException("next step malformed; rawOutput={\"toolName\":\"record_confirmed_terms\"}"),
                        new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-2")), "done"),
                        new ReviewToolDecision("complete_project", Map.of(), "finish")
                ),
                List.of()
        );

        AutonomousProjectReviewAgent agent = new AutonomousProjectReviewAgent(
                reader,
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new SequenceProjectFocusSelector(),
                new PromptBackedNextStepDecisionProvider(
                        new InvestigationPromptBuilder(),
                        ReviewToolRegistry.defaultRegistry(),
                        generationPort
                ),
                new ReviewToolExecutor(
                        ReviewToolRegistry.defaultRegistry(),
                        new ReviewToolGuardrail(),
                        reader,
                        PostDraftReviewAgentTermWriter.noop(),
                        new PromptBackedStrategyEvaluationService(new EvaluationPromptBuilder(), generationPort),
                        new PostDraftRevisionService(
                                new PromptBackedRevisionDraftProvider(),
                                (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
                        ),
                        new WorkingSetCompletionHandler(reader, new PostDraftReviewProcessSummaryAssembler()),
                        new PostDraftReviewProcessSummaryAssembler(),
                        new FocusHumanStopPolicy(1, 1)
                ),
                ReviewRuntimeVisualizer.noop()
        );

        ProjectReviewRuntimeSession result = agent.run(
                ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1", "chunk-2")),
                "operator note"
        );

        assertEquals(ReviewAgentState.COMPLETED, result.state());
        assertEquals(ReviewProjectStopReason.PROJECT_COMPLETED, result.stopReason());
        assertEquals(List.of("chunk-2"), result.completedChunkOutcomes().stream().map(ProjectChunkReviewOutcome::chunkId).toList());
        assertTrue(result.issueBacklog().openIssues().stream()
                .anyMatch(issue -> issue.relatedChunkId().equals("chunk-1")
                        && issue.summary().contains("failureCode=NEXT_STEP_STRUCTURED_OUTPUT_FAILED")
                        && issue.summary().contains("rawOutput={\"toolName\":\"record_confirmed_terms\"}")));
        assertTrue(result.processTrail().stream()
                .anyMatch(entry -> entry.contains("focusFailed=chunk-1")
                        && entry.contains("failureCode=NEXT_STEP_STRUCTURED_OUTPUT_FAILED")));
    }

    @Test
    void shouldContainCurrentFocusWhenRecordConfirmedTermsProposalRepairBudgetIsExhausted() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunkWithConfirmedTerms("chunk-1", "translated-1", Map.of("Le Bouquet", "布凯咖啡馆")),
                chunk("chunk-2", "translated-2")
        ));
        ReviewAgentStructuredGenerationPort generationPort = new MixedGenerationPort(
                List.of(
                        new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Le Bouquet", "placeholder")), "record confirmed term"),
                        new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-2")), "done"),
                        new ReviewToolDecision("complete_project", Map.of(), "finish")
                ),
                List.of(
                        new LlmStructuredOutputException("proposal rawOutput=bad-1"),
                        new LlmStructuredOutputException("proposal rawOutput=bad-2"),
                        new LlmStructuredOutputException("proposal rawOutput=bad-3"),
                        new LlmStructuredOutputException("proposal rawOutput=bad-4"),
                        new LlmStructuredOutputException("proposal rawOutput=bad-5"),
                        new LlmStructuredOutputException("proposal rawOutput=bad-6")
                )
        );

        AutonomousProjectReviewAgent agent = new AutonomousProjectReviewAgent(
                reader,
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new SequenceProjectFocusSelector(),
                new PromptBackedNextStepDecisionProvider(
                        new InvestigationPromptBuilder(),
                        ReviewToolRegistry.defaultRegistry(),
                        generationPort
                ),
                new ReviewToolExecutor(
                        ReviewToolRegistry.defaultRegistry(),
                        new ReviewToolGuardrail(),
                        reader,
                        PostDraftReviewAgentTermWriter.noop(),
                        new PromptBackedStrategyEvaluationService(new EvaluationPromptBuilder(), generationPort),
                        new PostDraftRevisionService(
                                new PromptBackedRevisionDraftProvider(),
                                (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
                        ),
                        new WorkingSetCompletionHandler(reader, new PostDraftReviewProcessSummaryAssembler()),
                        new PostDraftReviewProcessSummaryAssembler(),
                        new FocusHumanStopPolicy(1, 1)
                ),
                ReviewRuntimeVisualizer.noop()
        );

        ProjectReviewRuntimeSession result = agent.run(
                ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1", "chunk-2")),
                "operator note"
        );

        assertEquals(ReviewAgentState.COMPLETED, result.state());
        assertEquals(ReviewProjectStopReason.PROJECT_COMPLETED, result.stopReason());
        assertTrue(result.issueBacklog().openIssues().stream()
                .anyMatch(issue -> issue.relatedChunkId().equals("chunk-1")
                        && issue.summary().contains("failureCode=RECORD_CONFIRMED_TERMS_PROPOSAL_FAILED")
                        && issue.summary().contains("rawOutput=bad-6")));
        assertTrue(result.processTrail().stream()
                .anyMatch(entry -> entry.contains("focusFailed=chunk-1")
                        && entry.contains("failureCode=RECORD_CONFIRMED_TERMS_PROPOSAL_FAILED")));
    }

    @Test
    void shouldContinueDecisionCycleAfterSingleProposalMistake() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunkWithConfirmedTerms("chunk-1", "translated-1", Map.of("Patrick Modiano", "PatricZh"))
        ));
        ReviewAgentStructuredGenerationPort generationPort = new MixedGenerationPort(
                List.of(
                        new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Patrick Modiano", "placeholder")), "record confirmed term"),
                        new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-1")), "done"),
                        new ReviewToolDecision("complete_project", Map.of(), "finish")
                ),
                List.of(
                        new LlmStructuredOutputException("proposal rawOutput=not-json"),
                        new RecordConfirmedTermsProposal(
                                RecordConfirmedTermsProposal.Action.RECORD_CONFIRMED_TERMS,
                                "stable pair",
                                List.of(new RecordConfirmedTermEntry("Patrick Modiano", "PatricZh"))
                        )
                )
        );

        AutonomousProjectReviewAgent agent = buildAgent(reader, generationPort);
        ProjectReviewRuntimeSession result = agent.run(
                ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1")),
                "operator note"
        );

        assertEquals(ReviewAgentState.COMPLETED, result.state());
        assertEquals(ReviewProjectStopReason.PROJECT_COMPLETED, result.stopReason());
    }

    @Test
    void shouldContainCurrentFocusWhenUnifiedTwoPhaseRepairBudgetIsExhausted() {
        InMemoryReader reader = new InMemoryReader(List.of(
                chunkWithConfirmedTerms("chunk-1", "translated-1", Map.of("Patrick Modiano", "PatricZh")),
                chunk("chunk-2", "translated-2")
        ));
        ReviewAgentStructuredGenerationPort generationPort = new MixedGenerationPort(
                List.of(
                        new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Patrick Modiano", "placeholder")), "record confirmed term"),
                        new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-2")), "done"),
                        new ReviewToolDecision("complete_project", Map.of(), "finish")
                ),
                List.of(
                        new LlmStructuredOutputException("proposal rawOutput=bad-1"),
                        new LlmStructuredOutputException("proposal rawOutput=bad-2"),
                        new LlmStructuredOutputException("proposal rawOutput=bad-3"),
                        new LlmStructuredOutputException("proposal rawOutput=bad-4"),
                        new LlmStructuredOutputException("proposal rawOutput=bad-5"),
                        new LlmStructuredOutputException("proposal rawOutput=bad-6")
                )
        );

        AutonomousProjectReviewAgent agent = buildAgent(reader, generationPort);
        ProjectReviewRuntimeSession result = agent.run(
                ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1", "chunk-2")),
                "operator note"
        );

        assertEquals(ReviewAgentState.COMPLETED, result.state());
        assertNotEquals(ReviewProjectStopReason.LLM_CALL_FAILED, result.stopReason());
        assertTrue(result.processTrail().stream().anyMatch(entry -> entry.contains("focusFailed=chunk-1")));
        assertTrue(result.processTrail().stream()
                .anyMatch(entry -> entry.contains("focusFailed=chunk-1")
                        && entry.contains("failureCode=RECORD_CONFIRMED_TERMS_PROPOSAL_FAILED")));
    }

    @Test
    void shouldKeepProjectFatalForNonContainableStructuredFailure() {
        InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
        PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
                new InvestigationPromptBuilder(),
                ReviewToolRegistry.defaultRegistry(),
                new MixedGenerationPort(List.of(), List.of())
        ) {
            @Override
            public ReviewToolDecision decide(PostDraftReviewSession session) {
                throw new LlmStructuredOutputException("evaluation structured output failed; rawOutput=nope");
            }
        };

        AutonomousProjectReviewAgent agent = new AutonomousProjectReviewAgent(
                reader,
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new SequenceProjectFocusSelector(),
                provider,
                new ReviewToolExecutor(
                        ReviewToolRegistry.defaultRegistry(),
                        new ReviewToolGuardrail(),
                        reader,
                        PostDraftReviewAgentTermWriter.noop(),
                        new PromptBackedStrategyEvaluationService(new EvaluationPromptBuilder(), new MixedGenerationPort(List.of(), List.of())),
                        new PostDraftRevisionService(
                                new PromptBackedRevisionDraftProvider(),
                                (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
                        ),
                        new WorkingSetCompletionHandler(reader, new PostDraftReviewProcessSummaryAssembler()),
                        new PostDraftReviewProcessSummaryAssembler(),
                        new FocusHumanStopPolicy(1, 1)
                ),
                ReviewRuntimeVisualizer.noop()
        );

        ProjectReviewRuntimeSession result = agent.run(
                ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1")),
                "operator note"
        );

        assertEquals(ReviewAgentState.FAILED, result.state());
        assertEquals(ReviewProjectStopReason.LLM_CALL_FAILED, result.stopReason());
        assertTrue(result.processTrail().stream().anyMatch(entry -> entry.contains("llmCallFailed=evaluation structured output failed; rawOutput=nope")));
    }

    private static AutonomousProjectReviewAgent buildAgent(PostDraftReviewAgentReader reader,
                                                           ReviewAgentStructuredGenerationPort generationPort) {
        return new AutonomousProjectReviewAgent(
                reader,
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new SequenceProjectFocusSelector(),
                new PromptBackedNextStepDecisionProvider(
                        new InvestigationPromptBuilder(),
                        ReviewToolRegistry.defaultRegistry(),
                        generationPort
                ),
                new ReviewToolExecutor(
                        ReviewToolRegistry.defaultRegistry(),
                        new ReviewToolGuardrail(),
                        reader,
                        PostDraftReviewAgentTermWriter.noop(),
                        new PromptBackedStrategyEvaluationService(new EvaluationPromptBuilder(), generationPort),
                        new PostDraftRevisionService(
                                new PromptBackedRevisionDraftProvider(),
                                (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
                        ),
                        new WorkingSetCompletionHandler(reader, new PostDraftReviewProcessSummaryAssembler()),
                        new PostDraftReviewProcessSummaryAssembler(),
                        new FocusHumanStopPolicy(1, 1)
                ),
                ReviewRuntimeVisualizer.noop()
        );
    }

    private static PostDraftChunkRecord chunk(String chunkId, String translatedText) {
        return new PostDraftChunkRecord(
                chunkId,
                1,
                "block-1",
                "source text",
                translatedText,
                "commentary",
                List.of(),
                Map.of(),
                List.<TranslationCandidateUpdate>of(),
                null
        );
    }

    private static PostDraftChunkRecord chunkWithConfirmedTerms(String chunkId,
                                                                String translatedText,
                                                                Map<String, String> confirmedTerms) {
        return new PostDraftChunkRecord(
                chunkId,
                1,
                "block-1",
                "source text",
                translatedText,
                "commentary",
                List.of(),
                confirmedTerms,
                List.<TranslationCandidateUpdate>of(),
                null
        );
    }

    private static final class SequenceGenerationPort implements ReviewAgentStructuredGenerationPort {
        private final ArrayDeque<ReviewToolDecision> nextToolDecisions;
        private String latestPrompt;

        private SequenceGenerationPort(ReviewToolDecision... nextToolDecisions) {
            this.nextToolDecisions = new ArrayDeque<>(List.of(nextToolDecisions));
        }

        @Override
        public ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
            latestPrompt = userPrompt;
            return nextToolDecisions.removeFirst();
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

        @Override
        public RecordConfirmedTermsProposal generateRecordConfirmedTermsProposal(String systemPrompt, String userPrompt) {
            return new RecordConfirmedTermsProposal(
                    RecordConfirmedTermsProposal.Action.NOT_APPLICABLE,
                    "not used",
                    List.of()
            );
        }

        private String latestPrompt() {
            return latestPrompt;
        }
    }

    private static final class MixedGenerationPort implements ReviewAgentStructuredGenerationPort {
        private final ArrayDeque<Object> nextToolOutputs;
        private final ArrayDeque<Object> proposalOutputs;

        private MixedGenerationPort(List<?> nextToolOutputs, List<?> proposalOutputs) {
            this.nextToolOutputs = new ArrayDeque<>(nextToolOutputs);
            this.proposalOutputs = new ArrayDeque<>(proposalOutputs);
        }

        @Override
        public ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
            Object next = nextToolOutputs.removeFirst();
            if (next instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            return (ReviewToolDecision) next;
        }

        @Override
        public RecordConfirmedTermsProposal generateRecordConfirmedTermsProposal(String systemPrompt, String userPrompt) {
            if (proposalOutputs.isEmpty()) {
                return new RecordConfirmedTermsProposal(
                        RecordConfirmedTermsProposal.Action.NOT_APPLICABLE,
                        "not applicable",
                        List.of()
                );
            }
            Object next = proposalOutputs.removeFirst();
            if (next instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            return (RecordConfirmedTermsProposal) next;
        }

        @Override
        public ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt) {
            return new ReviewAgentEvaluation(
                    ReviewStrategy.KEEP,
                    "not used",
                    io.quillloom.application.postdraft.review.model.EvidenceSufficiency.SUFFICIENT,
                    false
            );
        }

        @Override
        public RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt) {
            return new RevisionDraft("not used", RevisionMode.LIGHT_EDIT, List.of(), List.of());
        }

        @Override
        public RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt) {
            return new RevisionSelfCheckResult(true, "", List.of());
        }
    }

    private static final class InMemoryReader implements PostDraftReviewAgentReader {
        private final List<PostDraftChunkRecord> chunks;

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
            int index = indexOf(chunkId);
            int from = Math.max(0, index - before);
            int to = Math.min(chunks.size(), index + after + 1);
            return chunks.subList(from, to);
        }

        @Override
        public List<PostDraftChunkRecord> searchChunksByKeyword(String projectId, String keyword) {
            return chunks;
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
            return Map.of();
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
}
