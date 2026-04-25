# Review Agent Two-Phase Repair Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `record_confirmed_terms` 的 proposal 子阶段并入统一 repair/retry 框架，修正 `NOT_APPLICABLE` 不应炸链路的问题，并把旧的一阶段 `entries` 修复逻辑收窄为 next-step 直接决策层的兼容修复，而不是 proposal 主路径的修复手段。

**Architecture:** 保持现有两阶段主路径不回滚：先普通 `generateNextToolDecision`，只有当 next-step 已选中 `record_confirmed_terms` 且当前 session 已具备稳定 pair 信号时才进入 proposal；proposal 仍只负责 `action + pair DTO`，最终 `arguments.entries` 继续由本地组装。修复重点是把 next-step 与 proposal 两个阶段都纳入同一套统一错误回灌与总预算重试框架，并把 `NOT_APPLICABLE` 视为可恢复的 bounded replan，而不是异常终点或 proposal 越权改写主调查顺序。

**Tech Stack:** Java 21, Spring Boot, LangChain4j, Jackson, JUnit 5, Maven.

---

## Problem Statement

当前实现已经把 `record_confirmed_terms` 主调用改成了两阶段，但失败恢复链路仍停留在旧的一阶段语义，形成三处明确脱节：

1. `record_confirmed_terms` 主路径现在是：
   - 普通 `generateNextToolDecision`
   - 若 `toolName == record_confirmed_terms`
   - 再进入 `generateRecordConfirmedTermsProposal`
   - 本地组装最终 `arguments.entries`

2. 但 `next-step` repair 仍保留旧的 `[entries repair]`：
   - 它仍在教 LLM 直接修最终 `arguments.entries`
   - 这与两阶段 proposal DTO 契约冲突
   - 它会把失败恢复路径拉回旧的一阶段心智模型
   - 同时又不能简单宣称它可以立刻彻底删除，因为 stage A 当前仍然直接产出 `ReviewToolDecision`，理论上仍可能出现 `record_confirmed_terms + invalid_argument:entries`

3. proposal 子阶段没有并入统一 repair/retry：
   - proposal 结构化失败直接抛 `RecordConfirmedTermsProposalException`
   - proposal 返回 `NOT_APPLICABLE` 也直接抛异常
   - proposal -> assembly 失败直接抛 `RecordConfirmedTermsAssemblyException`
   - 这些都没有像 next-step 那样带着错误信息和修正方案回灌给 LLM 再试

这会造成两个直接后果：

- agent 一旦在 proposal 子阶段犯一次格式错误，就可能直接终止当前决策链，抗错性不足。
- 两阶段主协议和 repair 协议并存两套不一致的输出教学，LLM 会在失败后被拉回旧协议。

本轮修复目标就是把这两处脱节收口成一个一致的两阶段 repair 模式。

---

## Scope And Non-Goals

### In Scope

1. `PromptBackedNextStepDecisionProvider` 的统一 retry/replan 主循环。
2. `generateRecordConfirmedTermsProposal(...)` 的 repair prompt、重试语义、总预算接入。
3. `NOT_APPLICABLE` 的非异常化处理。
4. 旧 `entries repair` 的收窄：不再作为 proposal 主路径修复手段，只保留或重写为 next-step 直接决策层的兼容修复逻辑。
5. 对应 provider / prompt / runtime containment 测试更新。
6. `docs/handoff.md` 同步本轮状态。

### Out Of Scope

1. 不回滚两阶段主路径。
2. 不改 executor 的工具执行协议。
3. 不引入全工具通用 proposal framework。
4. 不扩展到 `evaluate_focus` / `draft_revision` / `request_human_review` 的独立 repair 设计。
5. 不改项目整体 orchestration。

---

## File Structure

**Modify:**
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`
  - 主修复文件。统一 next-step/proposal 两阶段 retry、repair prompt、总预算控制、`NOT_APPLICABLE` bounded replan、proposal 入口保护。
- `src/main/java/io/quillloom/application/postdraft/review/service/RecordConfirmedTermsProposalException.java`
  - 如果需要，补充更稳定的构造方式/错误上下文承载，但不改变异常边界用途。
- `src/main/java/io/quillloom/application/postdraft/review/service/RecordConfirmedTermsAssemblyException.java`
  - 如果需要，补充稳定错误信息或 raw output 透传字段。
- `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
  - 如有相关提示仍要求 proposal 主路径直接成形最终 map，则一并清理；不主动删除 next-step 兼容 repair 所需的边界提示。
- `docs/handoff.md`
  - 同步“proposal 已并入统一 repair；`NOT_APPLICABLE` 不再直接炸链路；旧 `entries repair` 已收窄为 next-step 兼容逻辑”。

**Test:**
- `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
  - 主测试面：next-step/proposal 共享 6 次预算、`NOT_APPLICABLE` bounded replan、proposal structured repair、assembly repair、旧 entries repair 收窄为 next-step 兼容逻辑。
- `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`
  - runtime 层验证两条都必须保留：
    - proposal 失败在可恢复场景下，provider 应继续得出合法 next-step
    - proposal 修复预算耗尽时，仍必须 containment 为 current-focus local failure，而不是 project-fatal
- `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
  - 只验证 system/investigation prompt 不再错误教授旧的一阶段心智；不承担 provider repair prompt 的主锁定职责。

---

### Task 1: Lock The New Failure Semantics In Tests First

**Files:**
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`

- [ ] **Step 1: Add failing test for `NOT_APPLICABLE` replan instead of exception**

```java
@Test
void shouldReplanNextStepWhenProposalReturnsNotApplicable() {
    RecordingGenerationPort generationPort = new RecordingGenerationPort(
            new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Patrick Modiano", "placeholder")), "record confirmed term"),
            new RecordConfirmedTermsProposal(
                    RecordConfirmedTermsProposal.Action.NOT_APPLICABLE,
                    "pair not stable enough",
                    List.of()
            ),
            new ReviewToolDecision("evaluate_focus", Map.of(), "proposal denied term recording; continue with ordinary focus evaluation")
    );
    PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
            new InvestigationPromptBuilder(),
            new ReviewAgentSystemPromptBuilder(),
            ReviewToolRegistry.defaultRegistry(),
            generationPort,
            new ReviewToolDecisionContractValidator()
    );

    ReviewToolDecision decision = provider.decide(sampleSessionWithEvidence(
            List.of("confirmedTerm=Patrick Modiano->PatricZh"),
            List.of(),
            List.of()
    ));

    assertEquals("evaluate_focus", decision.toolName());
    assertEquals(2, generationPort.prompts().size());
    assertEquals(1, generationPort.proposalPrompts().size());
    assertTrue(generationPort.prompts().get(1).contains("proposal_not_applicable"));
}
```

- [ ] **Step 2: Add failing test for proposal structured-output repair**

```java
@Test
void shouldRetryProposalStructuredFailureWithinUnifiedRepairLoop() {
    RecordingGenerationPort generationPort = new RecordingGenerationPort(
            new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Patrick Modiano", "placeholder")), "record confirmed term"),
            new LlmStructuredOutputException("proposal rawOutput=not-json"),
            new RecordConfirmedTermsProposal(
                    RecordConfirmedTermsProposal.Action.RECORD_CONFIRMED_TERMS,
                    "stable pair",
                    List.of(new RecordConfirmedTermEntry("Patrick Modiano", "PatricZh"))
            )
    );

    PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
            new InvestigationPromptBuilder(),
            new ReviewAgentSystemPromptBuilder(),
            ReviewToolRegistry.defaultRegistry(),
            generationPort,
            new ReviewToolDecisionContractValidator()
    );

    ReviewToolDecision decision = provider.decide(sampleSessionWithEvidence(
            List.of("confirmedTerm=Patrick Modiano->PatricZh"),
            List.of(),
            List.of()
    ));

    assertEquals("record_confirmed_terms", decision.toolName());
    assertEquals(2, generationPort.proposalPrompts().size());
    assertTrue(generationPort.proposalPrompts().get(1).contains("proposal rawOutput=not-json"));
}
```

- [ ] **Step 3: Add failing test for proposal assembly repair**

```java
@Test
void shouldRetryProposalAssemblyFailureWithinUnifiedRepairLoop() {
    RecordingGenerationPort generationPort = new RecordingGenerationPort(
            new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Patrick Modiano", "placeholder")), "record confirmed term"),
            new RecordConfirmedTermsProposal(
                    RecordConfirmedTermsProposal.Action.RECORD_CONFIRMED_TERMS,
                    "conflicting pair",
                    List.of(
                            new RecordConfirmedTermEntry("Patrick Modiano", "PatricZh"),
                            new RecordConfirmedTermEntry("patrick modiano", "OtherZh")
                    )
            ),
            new RecordConfirmedTermsProposal(
                    RecordConfirmedTermsProposal.Action.RECORD_CONFIRMED_TERMS,
                    "stable pair",
                    List.of(new RecordConfirmedTermEntry("Patrick Modiano", "PatricZh"))
            )
    );

    PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
            new InvestigationPromptBuilder(),
            new ReviewAgentSystemPromptBuilder(),
            ReviewToolRegistry.defaultRegistry(),
            generationPort,
            new ReviewToolDecisionContractValidator()
    );

    ReviewToolDecision decision = provider.decide(sampleSessionWithEvidence(
            List.of("confirmedTerm=Patrick Modiano->PatricZh"),
            List.of(),
            List.of()
    ));

    assertEquals("record_confirmed_terms", decision.toolName());
    assertEquals(2, generationPort.proposalPrompts().size());
    assertTrue(generationPort.proposalPrompts().get(1).contains("proposal_assembly_error"));
}
```

- [ ] **Step 4: Add failing test for shared 6-attempt budget across both stages**

```java
@Test
void shouldApplySingleSixAttemptBudgetAcrossNextStepAndProposal() {
    RecordingGenerationPort generationPort = new RecordingGenerationPort(
            new LlmStructuredOutputException("structured generation output cannot be parsed as structured JSON; rawOutput=bad-next-step-1"),
            new LlmStructuredOutputException("structured generation output cannot be parsed as structured JSON; rawOutput=bad-next-step-2"),
            new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Patrick Modiano", "placeholder")), "record confirmed term"),
            new LlmStructuredOutputException("proposal rawOutput=bad-proposal-1"),
            new LlmStructuredOutputException("proposal rawOutput=bad-proposal-2"),
            new RecordConfirmedTermsProposal(
                    RecordConfirmedTermsProposal.Action.NOT_APPLICABLE,
                    "still not stable",
                    List.of()
            ),
            new ReviewToolDecision("evaluate_focus", Map.of(), "continue investigation")
    );

    PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
            new InvestigationPromptBuilder(),
            new ReviewAgentSystemPromptBuilder(),
            ReviewToolRegistry.defaultRegistry(),
            generationPort,
            new ReviewToolDecisionContractValidator()
    );

    ReviewToolDecision decision = provider.decide(sampleSessionWithEvidence(
            List.of("confirmedTerm=Patrick Modiano->PatricZh"),
            List.of(),
            List.of()
    ));

    assertEquals("evaluate_focus", decision.toolName());
    assertEquals(4, generationPort.prompts().size());
    assertEquals(3, generationPort.proposalPrompts().size());
}
```

- [ ] **Step 5: Add failing test that legacy `[entries repair]` no longer participates in proposal main-path repair, but next-step compatibility repair may still exist**

```java
@Test
void shouldNotUseLegacyEntriesRepairAsProposalMainPathRepair() {
    RecordingGenerationPort generationPort = new RecordingGenerationPort(
            new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of()), "record term"),
            new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Bernolle")), "continue investigation")
    );

    PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
            new InvestigationPromptBuilder(),
            new ReviewAgentSystemPromptBuilder(),
            ReviewToolRegistry.defaultRegistry(),
            generationPort,
            new ReviewToolDecisionContractValidator()
    );

    provider.decide(sampleSession());

    String repairPrompt = generationPort.prompts().get(1);
    assertTrue(repairPrompt.contains("validationError: invalid_argument:entries"));
    assertFalse(repairPrompt.contains("[Record Confirmed Terms Proposal Repair]"));
}
```

- [ ] **Step 6: Run provider tests and verify they fail on current implementation**

Run:
```bash
mvn -q "-Dtest=PromptBackedNextStepDecisionProviderTest" test
```

Expected:
- FAIL because current provider still throws on proposal failure / `NOT_APPLICABLE`.
- FAIL because current provider still does not distinguish next-step compatibility repair from proposal-stage repair.
- FAIL because no shared 6-attempt budget exists yet.

- [ ] **Step 7: Commit the failing tests**

```bash
git add src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java
git commit -m "test: lock two-phase unified repair behavior"
```

### Task 2: Refactor Provider Into A Single Stage-Aware Repair Loop

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`

- [ ] **Step 1: Introduce a single 6-attempt loop shared by next-step and proposal**

```java
private static final int MAX_TOTAL_ATTEMPTS = 6;

public ReviewToolDecision decide(PostDraftReviewSession session) {
    Objects.requireNonNull(session, "session");
    String systemPrompt = systemPromptBuilder.build(toolRegistry.definitions());
    RepairLoopState state = RepairLoopState.start(session, systemPrompt,
            promptBuilder.build(session, toolRegistry.definitions(), session.evidenceSummaries()));

    for (int attempt = 0; attempt < MAX_TOTAL_ATTEMPTS; attempt++) {
        LoopOutcome outcome = executeCurrentStage(session, state, attempt);
        if (outcome.isFinalDecision()) {
            return outcome.decision();
        }
        state = outcome.nextState();
    }
    throw state.toTerminalException();
}
```

实现约束：
1. `RepairLoopState.toTerminalException()` 不得返回泛型 `IllegalStateException` 或新的未接入 runtime containment 的异常类型。
2. 当共享 repair 预算耗尽时，终点异常类型必须按最终失败阶段/原因稳定落在现有 containment 边界内：
   - next-step 终态失败 -> `ReviewAgentNextStepStructuredOutputException`
   - proposal structured / proposal NOT_APPLICABLE replan exhausted -> `RecordConfirmedTermsProposalException`
   - proposal assembly exhausted -> `RecordConfirmedTermsAssemblyException`
3. 这样 runtime 仍可沿用当前 type-based containment，而不会把 current-focus local failure 回归成 project-level `LLM_CALL_FAILED`。

- [ ] **Step 2: Add stage-aware execution paths**

```java
private LoopOutcome executeCurrentStage(PostDraftReviewSession session,
                                        RepairLoopState state,
                                        int attempt) {
    return switch (state.stage()) {
        case NEXT_STEP -> executeNextStepStage(session, state, attempt);
        case RECORD_CONFIRMED_TERMS_PROPOSAL -> executeProposalStage(session, state, attempt);
    };
}
```

- [ ] **Step 3: Keep ordinary next-step decision as stage A only, and preserve stable-pair-signal protection before entering proposal**

```java
private LoopOutcome executeNextStepStage(PostDraftReviewSession session,
                                         RepairLoopState state,
                                         int attempt) {
    ReviewToolDecision decision = generationPort.generateNextToolDecision(state.systemPrompt(), state.userPrompt());
    Optional<String> validationError = contractValidator.validate(decision, toolRegistry);
    if (validationError.isPresent()) {
        return LoopOutcome.repair(state.toNextStepDecisionRepair(decision, validationError.orElseThrow()));
    }
    if (!"record_confirmed_terms".equals(decision.toolName())) {
        return LoopOutcome.finalDecision(decision);
    }
    List<RecordConfirmedTermEntry> stablePairSignals = collectStablePairSignals(session);
    if (stablePairSignals.isEmpty()) {
        return LoopOutcome.finalDecision(decision);
    }
    return LoopOutcome.repair(state.toProposalStage(decision, buildRecordConfirmedTermsProposalPrompt(session, stablePairSignals)));
}
```

- [ ] **Step 4: Re-run provider tests and verify failures move into proposal-stage behavior gaps**

Run:
```bash
mvn -q "-Dtest=PromptBackedNextStepDecisionProviderTest" test
```

Expected:
- Some tests still FAIL, but failures should now concentrate on `NOT_APPLICABLE` handling, proposal repair prompt content, and legacy entries repair removal.

- [ ] **Step 5: Commit the shared-loop scaffold**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java
git commit -m "refactor: add shared repair loop for two-phase next step"
```

### Task 3: Adapt Proposal Failures To Unified Repair Instead Of Throwing Immediately

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`

- [ ] **Step 1: Convert proposal structured failures into proposal-stage repair prompts**

```java
private LoopOutcome executeProposalStage(PostDraftReviewSession session,
                                         RepairLoopState state,
                                         int attempt) {
    try {
        RecordConfirmedTermsProposal proposal = generationPort.generateRecordConfirmedTermsProposal(
                state.systemPrompt(),
                state.userPrompt()
        );
        return handleProposalResult(session, state, proposal);
    } catch (LlmStructuredOutputException ex) {
        return LoopOutcome.repair(state.toProposalRepair(
                "proposal_structured_output_error",
                ex.getMessage(),
                extractRawOutput(ex.getMessage())
        ));
    }
}
```

- [ ] **Step 2: Convert `NOT_APPLICABLE` into bounded next-step replan, not exception and not proposal-side route override**

```java
private LoopOutcome handleProposalResult(PostDraftReviewSession session,
                                         RepairLoopState state,
                                         RecordConfirmedTermsProposal proposal) {
    if (proposal.action() == RecordConfirmedTermsProposal.Action.NOT_APPLICABLE) {
        return LoopOutcome.repair(state.toNextStepReplan(
                "proposal_not_applicable",
                proposal.reason(),
                proposal
        ));
    }
    try {
        return LoopOutcome.finalDecision(assembleRecordConfirmedTermsDecision(proposal));
    } catch (RecordConfirmedTermsAssemblyException ex) {
        return LoopOutcome.repair(state.toProposalRepair(
                "proposal_assembly_error",
                ex.getMessage(),
                proposal.toString()
        ));
    }
}
```

- [ ] **Step 3: Add proposal-specific repair prompt content**

```java
private String buildRecordConfirmedTermsProposalRepairPrompt(PostDraftReviewSession session,
                                                             String originalPrompt,
                                                             String errorType,
                                                             String errorMessage,
                                                             String rawOutput) {
    return originalPrompt + """

            [Record Confirmed Terms Proposal Repair]
            The previous proposal output is not usable.
            - proposalErrorType: %s
            - proposalErrorMessage: %s
            - rawOutput: %s
            - anchorChunkId: %s
            - currentWorkingSet: %s

            Return exactly one valid JSON object for proposal only:
            - action: RECORD_CONFIRMED_TERMS or NOT_APPLICABLE
            - reason: short justification
            - entries: [{"sourceTerm":"...","targetTerm":"..."}]

            Rules:
            1. Do not return final tool arguments.
            2. Do not return arguments.entries map here.
            3. If action=RECORD_CONFIRMED_TERMS, entries must be non-empty.
            4. If action=NOT_APPLICABLE, entries must be [].
            5. Keep pair extraction explicit and conflict-free.
            """.formatted(errorType, errorMessage, rawOutput, session.focus().chunkId(), session.workingSet().chunkIds());
}
```

- [ ] **Step 4: Re-run provider tests and verify proposal-stage tests pass**

Run:
```bash
mvn -q "-Dtest=PromptBackedNextStepDecisionProviderTest" test
```

Expected:
- PASS for proposal structured failure retry.
- PASS for `NOT_APPLICABLE` replan.
- PASS for proposal assembly failure retry.
- Remaining failures, if any, should now be legacy prompt residue only.

- [ ] **Step 5: Commit proposal repair unification**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java
git commit -m "feat: unify proposal failures into shared repair loop"
```

### Task 4: Narrow Legacy `entries repair` To Next-Step Compatibility Scope

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

- [ ] **Step 1: Remove any claim that legacy `entriesRepairGuidance(...)` is the proposal main-path repair; keep only a next-step compatibility branch if stage A still directly emits invalid `record_confirmed_terms`**

```java
private String buildDecisionRepairPrompt(PostDraftReviewSession session,
                                         String originalPrompt,
                                         ReviewToolDecision invalidDecision,
                                         String validationError) {
    return originalPrompt + """

            [Decision Repair]
            ...
            Rules:
            1. Return JSON only.
            2. If you keep the same tool, satisfy all required arguments.
            3. If you switch tools, the new toolName/arguments/reason must already be valid together.
            """;
}
```

- [ ] **Step 2: Delete the obsolete helper only if stage A no longer needs direct `invalid_argument:entries` compatibility repair; otherwise rename and document it as next-step-only compatibility logic**

```java
// either remove entirely after stage-A contract change
// or keep in narrowed form
private String nextStepEntriesCompatibilityRepairGuidance(String validationError) { ... }
```

- [ ] **Step 3: Update prompt tests so they assert only prompt-builder scope, not provider repair scope**

```java
@Test
void shouldNotTeachLegacyEntriesRepairAsProposalMainPathContract() {
    String prompt = new ReviewAgentSystemPromptBuilder()
            .build(ReviewToolRegistry.defaultRegistry().definitions());

    assertFalse(prompt.contains("proposal main path must repair final arguments.entries directly"));
}
```

说明：
- `ReviewPromptBuilderTest` 只验证 system/investigation prompt 没有错误教授“proposal 主路径直接修最终 entries map”的心智。
- 真正锁定 provider repair prompt 是否仍出现旧 `[entries repair]` / `Option A` / `Option B` 的主测试，仍应放在 `PromptBackedNextStepDecisionProviderTest`。

- [ ] **Step 4: Re-run prompt + provider tests**

Run:
```bash
mvn -q "-Dtest=ReviewPromptBuilderTest,PromptBackedNextStepDecisionProviderTest" test
```

Expected:
- PASS with no proposal-stage dependence on legacy final-map repair.
- PASS with next-step compatibility repair either removed or explicitly narrowed.

- [ ] **Step 5: Commit the legacy repair narrowing**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java
git commit -m "refactor: narrow legacy entries repair to next-step compatibility"
```

### Task 5: Verify Runtime Behavior And Sync Docs

**Files:**
- Modify: `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`
- Modify: `docs/handoff.md`

- [ ] **Step 1: Add an integration-style test that one proposal mistake no longer kills the chain**

```java
@Test
void shouldContinueDecisionCycleAfterSingleProposalMistake() {
    InMemoryReader reader = new InMemoryReader(List.of(
            chunkWithConfirmedTerms("chunk-1", "translated-1", Map.of("Patrick Modiano", "PatricZh")),
            chunk("chunk-2", "translated-2")
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

    assertEquals(ReviewProjectStopReason.PROJECT_COMPLETED, result.stopReason());
}
```

- [ ] **Step 2: Add a failing test that budget exhaustion still falls into current-focus containment instead of re-escalating to project-fatal**

```java
@Test
void shouldContainCurrentFocusWhenUnifiedTwoPhaseRepairBudgetIsExhausted() {
    InMemoryReader reader = new InMemoryReader(List.of(
            chunkWithConfirmedTerms("chunk-1", "translated-1", Map.of("Patrick Modiano", "PatricZh")),
            chunk("chunk-2", "translated-2")
    ));
    ReviewAgentStructuredGenerationPort generationPort = new MixedGenerationPort(
            List.of(
                    new ReviewToolDecision("record_confirmed_terms", Map.of("entries", Map.of("Patrick Modiano", "placeholder")), "record confirmed term")
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

    assertNotEquals(ReviewProjectStopReason.LLM_CALL_FAILED, result.stopReason());
    assertTrue(result.processTrail().stream().anyMatch(entry -> entry.contains("focusFailed=chunk-1")));
}
```

- [ ] **Step 3: Run the targeted suite**

Run:
```bash
mvn -q "-Dtest=PromptBackedNextStepDecisionProviderTest,AutonomousProjectReviewAgentTest,ReviewPromptBuilderTest" test
```

Expected:
- PASS.
- The provider should recover from one proposal mistake instead of hard-failing immediately.
- PASS.
- When the shared repair budget is exhausted, runtime should still contain the current focus instead of failing the whole project.
- PASS.
- Existing containment boundary for non-recoverable proposal failure must remain explicitly covered; do not replace it with only a “recover once and complete” happy-path assertion.

- [ ] **Step 4: Update handoff**

```markdown
## 2026-04-21 Two-Phase Repair Unification
1. `record_confirmed_terms` now uses one shared repair framework across next-step and proposal stages.
2. Proposal `NOT_APPLICABLE` is no longer treated as an immediate fatal exception; it now triggers next-step replan inside the same bounded repair loop.
3. Proposal structured-output failure and proposal assembly failure now return structured repair prompts to the LLM instead of failing after one mistake.
4. Next-step and proposal now share one total retry budget of 6 attempts per decision cycle.
5. Legacy one-phase `[entries repair]` guidance is no longer the repair path for two-phase proposal handling; if stage A still directly emits invalid `record_confirmed_terms` decisions, entries compatibility repair may remain in narrowed form there until that error surface is removed from the main contract.
```

- [ ] **Step 5: Re-run the same targeted suite after docs sync**

Run:
```bash
mvn -q "-Dtest=PromptBackedNextStepDecisionProviderTest,AutonomousProjectReviewAgentTest,ReviewPromptBuilderTest" test
```

Expected:
- PASS again.

- [ ] **Step 6: Commit runtime verification and docs sync**

```bash
git add src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java docs/handoff.md
git commit -m "docs: sync two-phase repair unification behavior"
```

---

## Self-Review

### Spec Coverage
- 当前问题定义已覆盖：两阶段主路径与 repair 语义脱节、`NOT_APPLICABLE` 误当异常、proposal 未并入统一 repair。
- 可实施方案已覆盖：单一 6 次总预算、proposal structured repair、proposal assembly repair、`NOT_APPLICABLE` bounded replan、旧 `entries repair` 收窄为 next-step 兼容逻辑。
- 验证面已覆盖：provider 单测、agent 级回归、prompt 残留检查、handoff 同步。

### Placeholder Scan
- 无 `TODO` / `TBD` / “后续实现”。
- 每个任务都写明了具体文件、命令、期望失败/通过结果。
- 没有“类似 Task N”式引用偷懒。

### Type Consistency
- 统一使用 `RecordConfirmedTermsProposal` / `RecordConfirmedTermEntry` / `record_confirmed_terms` proposal stage 命名。
- 统一使用 `NOT_APPLICABLE`、`proposal_not_applicable`、`proposal_structured_output_error`、`proposal_assembly_error` 作为计划中的阶段错误语义。
- 统一重试预算命名为 `MAX_TOTAL_ATTEMPTS = 6`。
- proposal 仍明确受“next-step 已选中 `record_confirmed_terms` 且 stable pair signals 非空”双重入口保护，不重新成为路由覆盖器。
