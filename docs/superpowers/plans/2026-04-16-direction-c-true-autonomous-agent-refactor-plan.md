# [OUTDATED - 已被 2026-04-18-review-agent-direction-anchor.md 取代] Direction C True Autonomous Agent Refactor Implementation Plan

> ⚠️ 本文档已被 [04-18 方向锚定文档](./2026-04-18-review-agent-direction-anchor.md) 取代。本文档中描述的部分技术方案（FocusWorkingMemory / ProjectRollingMemory / CompletedChunkMemorySummary / legacyFallback / allowedActions）已全部消除，仅作历史参考。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current allowlist/state-machine post-draft review loop with a true autonomous agent that can expand from a focus anchor into a multi-chunk working set, produce per-chunk outcomes, and reserve a global issue backlog for later revisits.

**Architecture:** Keep `PostDraftReviewAgentService.reviewProject(...)` as the external entry, but move loop control into a new autonomous agent core driven by dynamic tool decisions. Runtime state lives in new session models (`TranscriptStore`, `HistoryLog`, `ReviewWorkingSet`, `ProjectIssueBacklog`), while formal outputs still land as per-chunk `ProjectChunkReviewOutcome`.

**Tech Stack:** Java 21, JUnit 5, Maven, existing `io.quillloom.application.postdraft.review` package structure.

---

## File Map

### Create

- `src/main/java/io/quillloom/application/postdraft/review/model/HistoryEvent.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/HistoryLog.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/TranscriptStore.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/UsageSummary.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/UsageBudget.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentStopReason.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentConfig.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewEvidenceBundle.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewVisitedObjects.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolTrace.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewWorkingSet.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/DeferredReviewIssue.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ProjectIssueBacklog.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDefinition.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolCall.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDecision.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolExecutionResult.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewGuardrailRejection.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/StoredReviewSession.java`
- `src/main/java/io/quillloom/application/postdraft/review/port/out/ReviewSessionStore.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolGuardrail.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedStrategyEvaluationService.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/LlmBackedRevisionSelfCheckService.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/WorkingSetCompletionHandler.java`
- `src/main/java/io/quillloom/infrastructure/postdraft/review/FileReviewSessionStore.java`
- `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewToolRegistryTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewWorkingSetModelTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ProjectIssueBacklogModelTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/TranscriptStoreModelTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/FileReviewSessionStoreTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/WorkingSetCompletionHandlerTest.java`

### Modify

- `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewSession.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentState.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ProjectChunkReviewOutcome.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewSessionFactory.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftRevisionService.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ProjectFocusSelector.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/SequenceProjectFocusSelector.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ProjectReviewOutputAssembler.java`
- `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- `src/main/java/io/quillloom/application/postdraft/review/prompt/EvaluationPromptBuilder.java`
- `src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftProjectRuntimeSessionModelTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewStructuredResultModelTest.java`

### Delete

- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentActionType.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/FocusWorkingMemory.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ProjectRollingMemory.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/CompletedChunkMemorySummary.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAllowedActionPlanner.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewLoopRunner.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedInvestigationDecisionProvider.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedEvaluationDecisionProvider.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ProjectMemoryCompressor.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAllowedActionPlannerTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewLoopRunnerTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftProjectUnifiedLoopRunnerTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PromptBackedInvestigationDecisionProviderTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PromptBackedEvaluationDecisionProviderTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ProjectMemoryCompressorTest.java`

## Task 1: Replace Runtime Models With Working Set and Backlog Primitives

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/HistoryEvent.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/HistoryLog.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/TranscriptStore.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/UsageSummary.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/UsageBudget.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentStopReason.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentConfig.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewEvidenceBundle.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewVisitedObjects.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolTrace.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewWorkingSet.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/DeferredReviewIssue.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectIssueBacklog.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewSession.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentState.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewWorkingSetModelTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ProjectIssueBacklogModelTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/TranscriptStoreModelTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftProjectRuntimeSessionModelTest.java`

- [ ] **Step 1: Write failing model tests for working set, backlog, and transcript compaction**

```java
@Test
void shouldExpandWorkingSetFromAnchorChunk() {
    ReviewWorkingSet workingSet = ReviewWorkingSet.fromAnchor("chunk-10")
            .expandTo(List.of("chunk-10", "chunk-11", "chunk-12"));

    assertEquals("chunk-10", workingSet.anchorChunkId());
    assertEquals(List.of("chunk-10", "chunk-11", "chunk-12"), workingSet.chunkIds());
}

@Test
void shouldKeepRecentTranscriptEntriesWhenCompacted() {
    TranscriptStore store = new TranscriptStore(new ArrayList<>(), false);
    store.append("turn-1");
    store.append("turn-2");
    store.append("turn-3");

    store.compact(2);

    assertEquals(List.of("turn-2", "turn-3"), store.replay());
}

@Test
void shouldRegisterDeferredIssueWithoutClosingProject() {
    ProjectIssueBacklog backlog = ProjectIssueBacklog.empty()
            .add(new DeferredReviewIssue("issue-1", "chunk-10", "alias unresolved"));

    assertEquals(1, backlog.openIssues().size());
    assertFalse(backlog.isEmpty());
}
```

- [ ] **Step 2: Run targeted tests to verify the missing types fail**

Run: `mvn -q "-Dtest=ReviewWorkingSetModelTest,ProjectIssueBacklogModelTest,TranscriptStoreModelTest,PostDraftProjectRuntimeSessionModelTest" test`  
Expected: FAIL with missing symbols such as `ReviewWorkingSet`, `ProjectIssueBacklog`, and updated `ProjectReviewRuntimeSession` fields.

- [ ] **Step 3: Implement the new runtime model layer and rewrite the session records**

```java
public record ReviewWorkingSet(
        String anchorChunkId,
        List<String> chunkIds
) {
    public static ReviewWorkingSet fromAnchor(String chunkId) { ... }
    public ReviewWorkingSet expandTo(List<String> nextChunkIds) { ... }
}

public record ProjectIssueBacklog(
        List<DeferredReviewIssue> openIssues
) {
    public static ProjectIssueBacklog empty() { ... }
    public ProjectIssueBacklog add(DeferredReviewIssue issue) { ... }
}

public record PostDraftReviewSession(
        String projectId,
        ReviewFocus focus,
        ReviewWorkingSet workingSet,
        ProjectIssueBacklog issueBacklog,
        TranscriptStore transcriptStore,
        HistoryLog historyLog,
        ReviewEvidenceBundle evidenceBundle,
        List<ReviewToolTrace> toolTraces,
        ReviewAgentConfig config,
        ReviewAgentState state,
        ReviewAgentStopReason stopReason
) { ... }
```

- [ ] **Step 4: Run the model tests again**

Run: `mvn -q "-Dtest=ReviewWorkingSetModelTest,ProjectIssueBacklogModelTest,TranscriptStoreModelTest,PostDraftProjectRuntimeSessionModelTest" test`  
Expected: PASS

- [ ] **Step 5: Commit the model rewrite**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/model src/test/java/io/quillloom/application/postdraft/review/ReviewWorkingSetModelTest.java src/test/java/io/quillloom/application/postdraft/review/ProjectIssueBacklogModelTest.java src/test/java/io/quillloom/application/postdraft/review/TranscriptStoreModelTest.java src/test/java/io/quillloom/application/postdraft/review/PostDraftProjectRuntimeSessionModelTest.java
git commit -m "refactor: introduce working set runtime models"
```

## Task 2: Replace Action Enum With Dynamic Tool Contracts

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDefinition.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolCall.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDecision.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolExecutionResult.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewGuardrailRejection.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewStructuredResultModelTest.java`
- Create: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolRegistryTest.java`
- Delete: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentActionType.java`

- [ ] **Step 1: Write failing tests for registry lookup and complete_working_set registration**

```java
@Test
void shouldExposeCompleteWorkingSetTool() {
    ReviewToolRegistry registry = ReviewToolRegistry.defaultRegistry();

    ReviewToolDefinition tool = registry.require("complete_working_set");

    assertEquals("complete_working_set", tool.toolName());
    assertTrue(tool.description().contains("chunk"));
}

@Test
void shouldRejectUnknownToolName() {
    ReviewToolRegistry registry = ReviewToolRegistry.defaultRegistry();

    assertThrows(IllegalArgumentException.class, () -> registry.require("unknown_tool"));
}
```

- [ ] **Step 2: Run targeted tests to verify enum-based code no longer matches the desired API**

Run: `mvn -q "-Dtest=ReviewToolRegistryTest,ReviewStructuredResultModelTest" test`  
Expected: FAIL because `ReviewToolRegistry` and the new tool decision records do not exist yet.

- [ ] **Step 3: Implement the dynamic tool contracts and register the initial local tool set**

```java
public record ReviewToolDefinition(
        String toolName,
        String description,
        Set<String> requiredArguments
) { ... }

public final class ReviewToolRegistry {
    public static ReviewToolRegistry defaultRegistry() {
        return new ReviewToolRegistry(List.of(
                new ReviewToolDefinition("read_previous_chunks", "Read previous chunk context", Set.of("count")),
                new ReviewToolDefinition("lookup_knowledge_cards", "Read knowledge cards", Set.of()),
                new ReviewToolDefinition("complete_working_set", "Complete multiple chunk outcomes", Set.of("chunkIds"))
        ));
    }
}
```

- [ ] **Step 4: Run registry and structured-result tests**

Run: `mvn -q "-Dtest=ReviewToolRegistryTest,ReviewStructuredResultModelTest" test`  
Expected: PASS

- [ ] **Step 5: Commit the dynamic tool contract layer**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDefinition.java src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolCall.java src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDecision.java src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolExecutionResult.java src/main/java/io/quillloom/application/postdraft/review/model/ReviewGuardrailRejection.java src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java src/test/java/io/quillloom/application/postdraft/review/ReviewToolRegistryTest.java src/test/java/io/quillloom/application/postdraft/review/ReviewStructuredResultModelTest.java
git commit -m "refactor: replace review action enum with tool registry"
```

## Task 3: Build the Autonomous Agent Core and Working Set Loop

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedStrategyEvaluationService.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ProjectFocusSelector.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/SequenceProjectFocusSelector.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/EvaluationPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentState.java`
- Create: `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`
- Delete: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAllowedActionPlanner.java`
- Delete: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewLoopRunner.java`
- Delete: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAllowedActionPlannerTest.java`
- Delete: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewLoopRunnerTest.java`
- Delete: `src/test/java/io/quillloom/application/postdraft/review/PostDraftProjectUnifiedLoopRunnerTest.java`

- [ ] **Step 1: Write failing tests for multi-chunk working set expansion and autonomous stop reasons**

```java
@Test
void shouldExpandFromAnchorToWorkingSetAndCompleteTwoChunks() {
    AutonomousProjectReviewAgent agent = fixtures.agentReturning(
            new ReviewToolDecision("read_next_chunks", Map.of("count", 1), "need more context"),
            new ReviewToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-1", "chunk-2")), "done")
    );

    ProjectReviewRuntimeSession result = agent.run(fixtures.runtimeWithPending("chunk-1", "chunk-2", "chunk-3"));

    assertEquals(List.of("chunk-3"), result.pendingChunkIds());
    assertEquals(2, result.completedChunkOutcomes().size());
}
```

- [ ] **Step 2: Run the new autonomous-agent tests**

Run: `mvn -q "-Dtest=AutonomousProjectReviewAgentTest" test`  
Expected: FAIL because `AutonomousProjectReviewAgent` does not exist and the old loop still owns control flow.

- [ ] **Step 3: Implement the new agent core and remove the external switch-case loop**

```java
public final class AutonomousProjectReviewAgent {
    public ProjectReviewRuntimeSession run(ProjectReviewRuntimeSession runtime) {
        ProjectReviewRuntimeSession current = runtime;
        while (true) {
            ReviewToolDecision decision = nextStepDecisionProvider.decide(current);
            ReviewToolExecutionResult result = toolExecutor.execute(current, decision);
            current = current.apply(result);
            if (current.stopReason().isTerminal()) {
                return current;
            }
        }
    }
}
```

- [ ] **Step 4: Run autonomous agent tests and prompt builder tests**

Run: `mvn -q "-Dtest=AutonomousProjectReviewAgentTest,ReviewPromptBuilderTest" test`  
Expected: PASS

- [ ] **Step 5: Commit the loop replacement**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedStrategyEvaluationService.java src/main/java/io/quillloom/application/postdraft/review/service/ProjectFocusSelector.java src/main/java/io/quillloom/application/postdraft/review/service/SequenceProjectFocusSelector.java src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java src/main/java/io/quillloom/application/postdraft/review/prompt/EvaluationPromptBuilder.java src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentState.java src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java
git commit -m "refactor: replace review loop with autonomous agent core"
```

## Task 4: Implement Tool Execution, Guardrails, and Fail-Fast LLM Decisions

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolGuardrail.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewSessionFactory.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- Delete: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedInvestigationDecisionProvider.java`
- Delete: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedEvaluationDecisionProvider.java`
- Delete: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedInvestigationDecisionProviderTest.java`
- Delete: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedEvaluationDecisionProviderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`

- [ ] **Step 1: Extend tests to assert fail-fast behavior when no structured generation port exists**

```java
@Test
void shouldFailFastWhenGenerationPortIsMissing() {
    IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            fixtures.serviceWithoutGenerationPort().reviewProject(fixtures.projectCommand()));

    assertTrue(ex.getMessage().contains("ReviewAgentStructuredGenerationPort"));
}
```

- [ ] **Step 2: Run service and autonomous-agent tests to confirm legacy fallback behavior is still present**

Run: `mvn -q "-Dtest=AutonomousProjectReviewAgentTest,PostDraftReviewAgentServiceTest" test`  
Expected: FAIL because current providers still contain `legacyFallback` and do not route through the new guardrail/executor path.

- [ ] **Step 3: Implement guardrail validation and tool execution result wiring**

```java
public final class ReviewToolGuardrail {
    public ReviewGuardrailRejection validate(ReviewToolCall call, ReviewToolRegistry registry) {
        if (!registry.contains(call.toolName())) {
            return new ReviewGuardrailRejection(call.toolName(), "unregistered_tool");
        }
        return ReviewGuardrailRejection.none();
    }
}

public final class ReviewToolExecutor {
    public ReviewToolExecutionResult execute(ProjectReviewRuntimeSession runtime, ReviewToolDecision decision) {
        ReviewToolCall call = ReviewToolCall.fromDecision(decision);
        ReviewGuardrailRejection rejection = guardrail.validate(call, registry);
        if (rejection.rejected()) {
            return ReviewToolExecutionResult.rejected(rejection);
        }
        return handlers.get(call.toolName()).apply(runtime, call);
    }
}
```

- [ ] **Step 4: Run service and agent tests again**

Run: `mvn -q "-Dtest=AutonomousProjectReviewAgentTest,PostDraftReviewAgentServiceTest" test`  
Expected: PASS

- [ ] **Step 5: Commit the executor and fail-fast decision layer**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolGuardrail.java src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewSessionFactory.java src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java
git commit -m "refactor: route review decisions through guardrailed tools"
```

## Task 5: Add Working Set Completion and Real LLM Self-Check

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/LlmBackedRevisionSelfCheckService.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/WorkingSetCompletionHandler.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftRevisionService.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectChunkReviewOutcome.java`
- Create: `src/test/java/io/quillloom/application/postdraft/review/WorkingSetCompletionHandlerTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PostDraftRevisionServiceTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`

- [ ] **Step 1: Write failing tests for multi-chunk completion and one local self-check retry**

```java
@Test
void shouldCreatePerChunkOutcomesFromCompleteWorkingSet() {
    WorkingSetCompletionHandler handler = fixtures.completionHandler();

    List<ProjectChunkReviewOutcome> outcomes = handler.complete(
            fixtures.sessionForChunks("chunk-1", "chunk-2"),
            List.of("chunk-1", "chunk-2")
    );

    assertEquals(List.of("chunk-1", "chunk-2"),
            outcomes.stream().map(ProjectChunkReviewOutcome::chunkId).toList());
}

@Test
void shouldRetrySelfCheckOnceBeforeEscalating() {
    AtomicInteger calls = new AtomicInteger();
    LlmBackedRevisionSelfCheckService service = fixtures.selfCheckService(prompt -> {
        if (calls.incrementAndGet() == 1) {
            return new RevisionSelfCheckResult(false, "bad", List.of("risk"));
        }
        return new RevisionSelfCheckResult(true, "", List.of());
    });

    assertTrue(service.verify(fixtures.session(), fixtures.chunk(), ReviewStrategy.LIGHT_EDIT, fixtures.draft()).passed());
    assertEquals(2, calls.get());
}
```

- [ ] **Step 2: Run revision and completion tests**

Run: `mvn -q "-Dtest=WorkingSetCompletionHandlerTest,PostDraftRevisionServiceTest,PostDraftReviewAgentServiceTest" test`  
Expected: FAIL because completion still happens one chunk at a time and self-check is still stubbed.

- [ ] **Step 3: Implement completion handler and replace the hardcoded self-check lambda**

```java
public final class WorkingSetCompletionHandler {
    public List<ProjectChunkReviewOutcome> complete(PostDraftReviewSession session, List<String> chunkIds) {
        return chunkIds.stream()
                .map(chunkId -> new ProjectChunkReviewOutcome(chunkId, resolveFinalTranslation(session, chunkId), resolveStrategy(session, chunkId), resolveSummary(session, chunkId)))
                .toList();
    }
}

public final class LlmBackedRevisionSelfCheckService {
    public RevisionSelfCheckResult verify(...) {
        RevisionSelfCheckResult first = generationPort.generateRevisionSelfCheck(promptBuilder.build(...));
        if (first.passed()) {
            return first;
        }
        return generationPort.generateRevisionSelfCheck(promptBuilder.buildRetryPrompt(...));
    }
}
```

- [ ] **Step 4: Run the revision/completion suite again**

Run: `mvn -q "-Dtest=WorkingSetCompletionHandlerTest,PostDraftRevisionServiceTest,PostDraftReviewAgentServiceTest" test`  
Expected: PASS

- [ ] **Step 5: Commit working set completion and real self-check**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/service/LlmBackedRevisionSelfCheckService.java src/main/java/io/quillloom/application/postdraft/review/service/WorkingSetCompletionHandler.java src/main/java/io/quillloom/application/postdraft/review/service/PostDraftRevisionService.java src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java src/main/java/io/quillloom/application/postdraft/review/model/ProjectChunkReviewOutcome.java src/test/java/io/quillloom/application/postdraft/review/WorkingSetCompletionHandlerTest.java src/test/java/io/quillloom/application/postdraft/review/PostDraftRevisionServiceTest.java src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java
git commit -m "refactor: complete working sets and add llm self-check"
```

## Task 6: Persist Sessions, Update Project Output, and Reserve the Global Backlog

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/StoredReviewSession.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/port/out/ReviewSessionStore.java`
- Create: `src/main/java/io/quillloom/infrastructure/postdraft/review/FileReviewSessionStore.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ProjectReviewOutputAssembler.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`
- Create: `src/test/java/io/quillloom/application/postdraft/review/FileReviewSessionStoreTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`

- [ ] **Step 1: Write failing tests for local session persistence and project summary output without rolling memory**

```java
@Test
void shouldPersistSessionToLocalJsonFile() {
    ReviewSessionStore store = new FileReviewSessionStore(tempDir);
    PostDraftReviewSession session = fixtures.session();

    store.save(session);

    assertTrue(Files.exists(tempDir.resolve(session.projectId() + ".json")));
}

@Test
void shouldAssembleProjectSummaryFromCompletedOutcomesAndBacklog() {
    PostDraftReviewAgentResult result = fixtures.outputAssembler().assemble(fixtures.runtimeWithBacklog());

    assertTrue(result.processSummary().processNote().contains("completedChunkCount"));
    assertTrue(result.processSummary().evidenceSummaries().stream().anyMatch(text -> text.contains("backlog")));
}
```

- [ ] **Step 2: Run the persistence and output tests**

Run: `mvn -q "-Dtest=FileReviewSessionStoreTest,PostDraftReviewAgentServiceTest" test`  
Expected: FAIL because no local session store exists and `ProjectReviewOutputAssembler` still depends on removed rolling-memory classes.

- [ ] **Step 3: Implement the file-backed session store and rewrite output assembly**

```java
public final class FileReviewSessionStore implements ReviewSessionStore {
    @Override
    public void save(PostDraftReviewSession session) {
        Path path = root.resolve(session.projectId() + ".json");
        Files.writeString(path, serializer.toJson(StoredReviewSession.from(session)));
    }
}

String processNote = "completedChunkCount=" + runtime.completedChunkOutcomes().size()
        + ", pendingChunkCount=" + runtime.pendingChunkIds().size()
        + ", openIssueCount=" + runtime.openIssueBacklog().openIssues().size()
        + ", stopReason=" + runtime.stopReason().name().toLowerCase(Locale.ROOT);
```

- [ ] **Step 4: Run the persistence and service tests again**

Run: `mvn -q "-Dtest=FileReviewSessionStoreTest,PostDraftReviewAgentServiceTest" test`  
Expected: PASS

- [ ] **Step 5: Commit persistence and output changes**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/model/StoredReviewSession.java src/main/java/io/quillloom/application/postdraft/review/port/out/ReviewSessionStore.java src/main/java/io/quillloom/infrastructure/postdraft/review/FileReviewSessionStore.java src/main/java/io/quillloom/application/postdraft/review/service/ProjectReviewOutputAssembler.java src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java src/test/java/io/quillloom/application/postdraft/review/FileReviewSessionStoreTest.java src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java
git commit -m "refactor: persist autonomous review sessions locally"
```

## Task 7: Delete Obsolete Memory/Loop Code and Run Redline Verification

**Files:**
- Delete: `src/main/java/io/quillloom/application/postdraft/review/model/FocusWorkingMemory.java`
- Delete: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectRollingMemory.java`
- Delete: `src/main/java/io/quillloom/application/postdraft/review/model/CompletedChunkMemorySummary.java`
- Delete: `src/main/java/io/quillloom/application/postdraft/review/service/ProjectMemoryCompressor.java`
- Modify: `docs/handoff.md`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`

- [ ] **Step 1: Delete obsolete runtime-memory classes and update any remaining imports**

```java
// Remove imports of:
// FocusWorkingMemory
// ProjectRollingMemory
// CompletedChunkMemorySummary
// Replace them with:
// ReviewWorkingSet
// ProjectIssueBacklog
// ProjectChunkReviewOutcome
```

- [ ] **Step 2: Run the full focused review-agent test suite**

Run: `mvn -q "-Dtest=ReviewWorkingSetModelTest,ProjectIssueBacklogModelTest,TranscriptStoreModelTest,ReviewToolRegistryTest,AutonomousProjectReviewAgentTest,WorkingSetCompletionHandlerTest,FileReviewSessionStoreTest,PostDraftReviewAgentServiceTest,PostDraftRevisionServiceTest,ReviewPromptBuilderTest,ReviewStructuredResultModelTest,PostDraftProjectRuntimeSessionModelTest" test`  
Expected: PASS

- [ ] **Step 3: Run explicit redline grep checks**

Run: `rg -n "allowedAction" src/main/java/io/quillloom/application/postdraft/review`  
Expected: no results

Run: `rg -n "legacyFallback" src/main/java/io/quillloom/application/postdraft/review`  
Expected: no results

Run: `rg -n "RevisionSelfCheckResult\\(true" src/main/java/io/quillloom/application/postdraft/review`  
Expected: no results

Run: `rg -n "enum ReviewAgentActionType" src/main/java/io/quillloom/application/postdraft/review`  
Expected: no results

Run: `rg -n "maxLoopRounds" src/main/java/io/quillloom/application/postdraft/review`  
Expected: no results

Run: `rg -n "INITIALIZING|SELECTING_FOCUS|INVESTIGATING|EVALUATING" src/main/java/io/quillloom/application/postdraft/review/service`  
Expected: no external switch-case loop controlling decisions

- [ ] **Step 4: Update `docs/handoff.md` with the new runtime model and verification status**

```markdown
## 2026-04-16 方向 C 真自主 agent 重构完成
1. 已切换到 focus anchor + working set。
2. `ProjectChunkReviewOutcome` 保持逐 chunk 正式产出。
3. `ProjectIssueBacklog` 已作为运行态全局问题列表落地。
4. R-01 到 R-08 已按 grep 和代码审查通过。
```

- [ ] **Step 5: Commit the cleanup and verification**

```bash
git add src/main/java/io/quillloom/application/postdraft/review src/test/java/io/quillloom/application/postdraft/review docs/handoff.md
git commit -m "refactor: remove legacy review loop and verify redlines"
```

## Self-Review

Spec coverage check:

- Autonomous dynamic decisions: covered by Tasks 2, 3, and 4.
- Working set multi-chunk freedom: covered by Tasks 1, 3, and 5.
- Per-chunk `ProjectChunkReviewOutcome`: covered by Task 5.
- Global issue backlog reservation: covered by Tasks 1, 3, and 6.
- Real LLM self-check: covered by Task 5.
- Local session persistence and transcript/history split: covered by Tasks 1 and 6.
- Redline cleanup and verification: covered by Task 7.

Placeholder scan:

- No `TODO`, `TBD`, or “similar to task N” placeholders remain.
- Every task names exact files and exact verification commands.

Type consistency check:

- Completion tool name is consistently `complete_working_set`.
- Stop reason is consistently `WORKING_SET_COMPLETED` / `PROJECT_COMPLETED`.
- Global issue list is consistently `ProjectIssueBacklog`.
