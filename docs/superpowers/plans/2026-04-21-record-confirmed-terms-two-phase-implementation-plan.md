# Review Agent `record_confirmed_terms` Two-Phase + Runtime Containment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `record_confirmed_terms` 改成专项两阶段参数成形，并在同一轮落地 runtime containment，确保 `entries:{}` 不再把整项目升级为 `LLM_CALL_FAILED`。

**Architecture:** 保留现有 review-agent 主闭环，不做全工具协议重构。仅为 `record_confirmed_terms` 增加 proposal DTO + client schema + provider 窄路由 + 本地 `entries` 组装，同时把 proposal/assembly 失败与旧的 next-step structured-output failure 一并收束为当前 focus 的局部失败，由 runtime 继续推进后续 chunk。

**Tech Stack:** Java 21, Spring Boot, Jackson, LangChain4j JSON schema API, JUnit 5, Maven.

---

## File Structure

**Create:**
- `src/main/java/io/quillloom/application/postdraft/review/model/RecordConfirmedTermEntry.java`
  - `record_confirmed_terms` proposal 中单个 pair DTO，承载 `sourceTerm` / `targetTerm`。
- `src/main/java/io/quillloom/application/postdraft/review/model/RecordConfirmedTermsProposal.java`
  - 两阶段第一阶段输出 DTO，承载 `action` / `reason` / `entries`。
- `src/main/java/io/quillloom/application/postdraft/review/prompt/RecordConfirmedTermsProposalPromptBuilder.java`
  - 专项 proposal prompt，只负责 pair 抽取和 `NOT_APPLICABLE` 判定。

**Modify:**
- `src/main/java/io/quillloom/application/postdraft/review/port/out/ReviewAgentStructuredGenerationPort.java`
  - 增加 `generateRecordConfirmedTermsProposal(...)` 接口。
- `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
  - 增加 proposal schema、proposal 解析、proposal 结构校验。
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`
  - 接入 B3 窄路由、proposal 调用、本地 `entries` 组装、proposal/assembly 失败语义。
- `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
  - 把 next-step/proposal 相关 `LlmStructuredOutputException` 收束为当前 focus 失败，而不是项目级 `LLM_CALL_FAILED`。
- `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java`
  - 增加当前 focus 局部失败并继续项目所需的状态迁移方法，复用 process trail / issue backlog / selecting focus 切换。
- `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
  - 从主 prompt 减少 `record_confirmed_terms.entries` map 细节教学，只保留全局边界。
- `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
  - 同步全局边界表达，避免主 prompt 继续承担最终 `entries` 形状教学。
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java`
  - 可选：把空对象细化为 `invalid_argument:entries_empty_map`，但保持 `invalid_argument:entries` 前缀兼容；如果改动会扩大面，则保留现状。
- `docs/handoff.md`
  - 同步实施状态与落地约束。

**Test:**
- `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewToolDecisionContractValidatorTest.java` (only if validator error code is refined)

---

### Task 1: Define Proposal DTOs And Port Contract

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/RecordConfirmedTermEntry.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/RecordConfirmedTermsProposal.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/port/out/ReviewAgentStructuredGenerationPort.java`
- Test: `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java`

- [ ] **Step 1: Write the failing tests for proposal DTO parsing and contract**

```java
@Test
void shouldParseRecordConfirmedTermsProposal() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
            .aiMessage(AiMessage.from("""
                    {
                      "action": "RECORD_CONFIRMED_TERMS",
                      "reason": "stable pair found",
                      "entries": [
                        {"sourceTerm": "Le Bouquet", "targetTerm": "布凯咖啡馆"}
                      ]
                    }
                    """))
            .build());

    OpenAiCompatibleReviewAgentStructuredGenerationClient client =
            new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

    RecordConfirmedTermsProposal proposal = client.generateRecordConfirmedTermsProposal("system", "user");

    assertEquals(RecordConfirmedTermsProposal.Action.RECORD_CONFIRMED_TERMS, proposal.action());
    assertEquals(1, proposal.entries().size());
    assertEquals("Le Bouquet", proposal.entries().get(0).sourceTerm());
}

@Test
void shouldRejectRecordConfirmedTermsProposalWhenApplicableButEntriesEmpty() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
            .aiMessage(AiMessage.from("""
                    {
                      "action": "RECORD_CONFIRMED_TERMS",
                      "reason": "stable pair found",
                      "entries": []
                    }
                    """))
            .build());

    OpenAiCompatibleReviewAgentStructuredGenerationClient client =
            new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

    assertThrows(
            LlmStructuredOutputException.class,
            () -> client.generateRecordConfirmedTermsProposal("system", "user")
    );
}
```

- [ ] **Step 2: Run the client test class and verify these proposal tests fail**

Run:
```bash
mvn -q "-Dtest=OpenAiCompatibleReviewAgentStructuredGenerationClientTest" test
```

Expected:
- FAIL because `generateRecordConfirmedTermsProposal(...)` does not exist yet.
- FAIL because proposal DTO classes do not exist yet.

- [ ] **Step 3: Add minimal proposal DTOs and port method**

```java
public record RecordConfirmedTermEntry(
        String sourceTerm,
        String targetTerm
) {
    public RecordConfirmedTermEntry {
        if (sourceTerm == null || sourceTerm.isBlank()) {
            throw new IllegalArgumentException("sourceTerm must not be blank");
        }
        if (targetTerm == null || targetTerm.isBlank()) {
            throw new IllegalArgumentException("targetTerm must not be blank");
        }
        sourceTerm = sourceTerm.trim();
        targetTerm = targetTerm.trim();
    }
}
```

```java
public record RecordConfirmedTermsProposal(
        Action action,
        String reason,
        List<RecordConfirmedTermEntry> entries
) {
    public enum Action {
        RECORD_CONFIRMED_TERMS,
        NOT_APPLICABLE
    }

    public RecordConfirmedTermsProposal {
        action = Objects.requireNonNull(action, "action");
        reason = reason == null ? "" : reason.trim();
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (action == Action.RECORD_CONFIRMED_TERMS && entries.isEmpty()) {
            throw new IllegalArgumentException("RECORD_CONFIRMED_TERMS requires non-empty entries");
        }
        if (action == Action.NOT_APPLICABLE && !entries.isEmpty()) {
            throw new IllegalArgumentException("NOT_APPLICABLE requires empty entries");
        }
    }
}
```

```java
public interface ReviewAgentStructuredGenerationPort {

    ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt);

    RecordConfirmedTermsProposal generateRecordConfirmedTermsProposal(String systemPrompt, String userPrompt);

    ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt);

    RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt);

    RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt);
}
```

- [ ] **Step 4: Re-run the client test class and verify the remaining failures move into client implementation gaps**

Run:
```bash
mvn -q "-Dtest=OpenAiCompatibleReviewAgentStructuredGenerationClientTest" test
```

Expected:
- FAIL because client implementation/schema for proposal is still missing.
- DTO/port compile errors should be resolved.

- [ ] **Step 5: Commit the DTO and port contract scaffold**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/model/RecordConfirmedTermEntry.java src/main/java/io/quillloom/application/postdraft/review/model/RecordConfirmedTermsProposal.java src/main/java/io/quillloom/application/postdraft/review/port/out/ReviewAgentStructuredGenerationPort.java src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java
git commit -m "feat: add record confirmed terms proposal contract"
```

### Task 2: Implement Proposal Generation Client

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- Test: `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java`

- [ ] **Step 1: Add failing tests for schema text and invalid proposal combinations**

```java
@Test
void shouldRequestRecordConfirmedTermsProposalSchema() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
            .aiMessage(AiMessage.from("""
                    {
                      "action": "NOT_APPLICABLE",
                      "reason": "not enough evidence",
                      "entries": []
                    }
                    """))
            .build());

    OpenAiCompatibleReviewAgentStructuredGenerationClient client =
            new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

    client.generateRecordConfirmedTermsProposal("system", "user");

    ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(chatModel).chat(captor.capture());
    String schemaText = String.valueOf(captor.getValue().responseFormat().jsonSchema());

    assertTrue(schemaText.contains("sourceTerm"));
    assertTrue(schemaText.contains("targetTerm"));
    assertTrue(schemaText.contains("action"));
}

@Test
void shouldRejectRecordConfirmedTermsProposalWhenActionIsUnknown() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
            .aiMessage(AiMessage.from("""
                    {
                      "action": "UNKNOWN_ACTION",
                      "reason": "bad action",
                      "entries": []
                    }
                    """))
            .build());

    OpenAiCompatibleReviewAgentStructuredGenerationClient client =
            new OpenAiCompatibleReviewAgentStructuredGenerationClient(chatModel, new ObjectMapper());

    assertThrows(
            LlmStructuredOutputException.class,
            () -> client.generateRecordConfirmedTermsProposal("system", "user")
    );
}
```

- [ ] **Step 2: Run only the client test class**

Run:
```bash
mvn -q "-Dtest=OpenAiCompatibleReviewAgentStructuredGenerationClientTest" test
```

Expected:
- FAIL because proposal schema and proposal method implementation are missing.

- [ ] **Step 3: Implement minimal client schema + proposal validation**

```java
private static final JsonSchema RECORD_CONFIRMED_TERMS_PROPOSAL_SCHEMA = JsonSchema.builder()
        .name("review_agent_record_confirmed_terms_proposal")
        .rootElement(JsonObjectSchema.builder()
                .addProperty("action", JsonStringSchema.builder().build())
                .addProperty("reason", JsonStringSchema.builder().build())
                .addProperty("entries", JsonArraySchema.builder()
                        .items(JsonObjectSchema.builder()
                                .addProperty("sourceTerm", JsonStringSchema.builder().build())
                                .addProperty("targetTerm", JsonStringSchema.builder().build())
                                .required("sourceTerm", "targetTerm")
                                .additionalProperties(false)
                                .build())
                        .build())
                .required("action", "reason", "entries")
                .additionalProperties(false)
                .build())
        .build();

@Override
public RecordConfirmedTermsProposal generateRecordConfirmedTermsProposal(String systemPrompt, String userPrompt) {
    RecordConfirmedTermsProposal proposal =
            invoke(systemPrompt, userPrompt, RECORD_CONFIRMED_TERMS_PROPOSAL_SCHEMA, RecordConfirmedTermsProposal.class);
    validateRecordConfirmedTermsProposal(proposal);
    return proposal;
}

private void validateRecordConfirmedTermsProposal(RecordConfirmedTermsProposal proposal) {
    if (proposal == null) {
        throw new LlmStructuredOutputException("Review agent invalid record_confirmed_terms proposal: null_proposal");
    }
    try {
        new RecordConfirmedTermsProposal(proposal.action(), proposal.reason(), proposal.entries());
    } catch (IllegalArgumentException ex) {
        throw new LlmStructuredOutputException(
                "Review agent invalid record_confirmed_terms proposal: " + ex.getMessage(),
                ex
        );
    }
}
```

- [ ] **Step 4: Re-run the client test class and verify it passes**

Run:
```bash
mvn -q "-Dtest=OpenAiCompatibleReviewAgentStructuredGenerationClientTest" test
```

Expected:
- PASS for proposal parsing/schema tests.
- Existing next-step tests still PASS.

- [ ] **Step 5: Commit the proposal generation client**

```bash
git add src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java
git commit -m "feat: add record confirmed terms proposal generation"
```

### Task 3: Add Proposal Prompt Builder And Provider Two-Phase Routing

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/prompt/RecordConfirmedTermsProposalPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`

- [ ] **Step 1: Add failing provider tests for B3 routing and local assembly**

```java
@Test
void shouldUseProposalPathWhenStablePairCandidateSignalsArePresent() {
    RecordingGenerationPort generationPort = new RecordingGenerationPort(
            new RecordConfirmedTermsProposal(
                    RecordConfirmedTermsProposal.Action.RECORD_CONFIRMED_TERMS,
                    "stable pair found",
                    List.of(new RecordConfirmedTermEntry("Le Bouquet", "布凯咖啡馆"))
            )
    );
    PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
            new InvestigationPromptBuilder(),
            new ReviewAgentSystemPromptBuilder(),
            ReviewToolRegistry.defaultRegistry(),
            generationPort,
            new ReviewToolDecisionContractValidator()
    );

    ReviewToolDecision decision = provider.decide(sessionWithStablePairSignals());

    assertEquals("record_confirmed_terms", decision.toolName());
    assertEquals(Map.of("Le Bouquet", "布凯咖啡馆"), decision.arguments().get("entries"));
}

@Test
void shouldNotEnterProposalPathWhenOnlyLowPrioritySignalsArePresent() {
    RecordingGenerationPort generationPort = new RecordingGenerationPort(
            new ReviewToolDecision("evaluate_focus", Map.of(), "continue")
    );

    PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
            new InvestigationPromptBuilder(),
            new ReviewAgentSystemPromptBuilder(),
            ReviewToolRegistry.defaultRegistry(),
            generationPort,
            new ReviewToolDecisionContractValidator()
    );

    ReviewToolDecision decision = provider.decide(sessionWithLowPriorityOnlySignals());

    assertEquals("evaluate_focus", decision.toolName());
    assertEquals(1, generationPort.nextStepDecisionCallCount());
    assertEquals(0, generationPort.recordConfirmedTermsProposalCallCount());
}

@Test
void shouldFallBackToOrdinaryNextStepWhenProposalReturnsNotApplicable() {
    RecordingGenerationPort generationPort = new RecordingGenerationPort(
            new RecordConfirmedTermsProposal(
                    RecordConfirmedTermsProposal.Action.NOT_APPLICABLE,
                    "not enough evidence",
                    List.of()
            ),
            new ReviewToolDecision("evaluate_focus", Map.of(), "continue")
    );

    PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
            new InvestigationPromptBuilder(),
            new ReviewAgentSystemPromptBuilder(),
            ReviewToolRegistry.defaultRegistry(),
            generationPort,
            new ReviewToolDecisionContractValidator()
    );

    ReviewToolDecision decision = provider.decide(sessionWithStablePairSignals());

    assertEquals("evaluate_focus", decision.toolName());
}

@Test
void shouldKeepEntryOrderWhenProposalReturnsMultiplePairs() {
    RecordingGenerationPort generationPort = new RecordingGenerationPort(
            new RecordConfirmedTermsProposal(
                    RecordConfirmedTermsProposal.Action.RECORD_CONFIRMED_TERMS,
                    "stable pairs found",
                    List.of(
                            new RecordConfirmedTermEntry("Le Bouquet", "布凯咖啡馆"),
                            new RecordConfirmedTermEntry("La Pergola", "拉佩尔戈拉咖啡馆")
                    )
            )
    );

    PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
            new InvestigationPromptBuilder(),
            new ReviewAgentSystemPromptBuilder(),
            ReviewToolRegistry.defaultRegistry(),
            generationPort,
            new ReviewToolDecisionContractValidator()
    );

    ReviewToolDecision decision = provider.decide(sessionWithStablePairSignals());

    assertEquals(
            List.of("Le Bouquet", "La Pergola"),
            new ArrayList<>(((Map<String, String>) decision.arguments().get("entries")).keySet())
    );
}

@Test
void shouldFailProposalAssemblyWhenSameSourceHasConflictingTargets() {
    RecordingGenerationPort generationPort = new RecordingGenerationPort(
            new RecordConfirmedTermsProposal(
                    RecordConfirmedTermsProposal.Action.RECORD_CONFIRMED_TERMS,
                    "conflicting pairs",
                    List.of(
                            new RecordConfirmedTermEntry("Le Bouquet", "布凯咖啡馆"),
                            new RecordConfirmedTermEntry("Le Bouquet", "勒布凯咖啡馆")
                    )
            )
    );

    PromptBackedNextStepDecisionProvider provider = new PromptBackedNextStepDecisionProvider(
            new InvestigationPromptBuilder(),
            new ReviewAgentSystemPromptBuilder(),
            ReviewToolRegistry.defaultRegistry(),
            generationPort,
            new ReviewToolDecisionContractValidator()
    );

    assertThrows(
            LlmStructuredOutputException.class,
            () -> provider.decide(sessionWithStablePairSignals())
    );
}
```

- [ ] **Step 2: Run the provider test class and verify these tests fail**

Run:
```bash
mvn -q "-Dtest=PromptBackedNextStepDecisionProviderTest" test
```

Expected:
- FAIL because proposal prompt builder, proposal routing, and proposal assembly do not exist.

- [ ] **Step 3: Implement proposal prompt builder and B3 routing in provider**

```java
if (shouldEnterRecordConfirmedTermsProposal(session)) {
    String proposalPrompt = recordConfirmedTermsProposalPromptBuilder.build(session);
    RecordConfirmedTermsProposal proposal = generationPort.generateRecordConfirmedTermsProposal(systemPrompt, proposalPrompt);
    if (proposal.action() == RecordConfirmedTermsProposal.Action.NOT_APPLICABLE) {
        return decideOrdinaryNextStep(session, systemPrompt, originalUserPrompt);
    }
    return assembleRecordConfirmedTermsDecision(proposal);
}
return decideOrdinaryNextStep(session, systemPrompt, originalUserPrompt);
```

```java
private boolean shouldEnterRecordConfirmedTermsProposal(PostDraftReviewSession session) {
    return hasHighAuthoritySourceSignal(session)
            && hasHighAuthorityTargetSignal(session)
            && hasExplicitPairCandidate(session)
            && !hasOnlyLowPrioritySignals(session)
            && !hasConfirmedTermHitForSameSource(session);
}
```

```java
private ReviewToolDecision assembleRecordConfirmedTermsDecision(RecordConfirmedTermsProposal proposal) {
    LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
    LinkedHashMap<String, String> entries = new LinkedHashMap<>();
    for (RecordConfirmedTermEntry entry : proposal.entries()) {
        String previous = entries.putIfAbsent(entry.sourceTerm(), entry.targetTerm());
        if (previous != null && !previous.equals(entry.targetTerm())) {
            throw new LlmStructuredOutputException(
                    "Review agent invalid record_confirmed_terms proposal: conflicting_target_for_sourceTerm:" + entry.sourceTerm()
            );
        }
    }
    arguments.put("entries", new LinkedHashMap<>(entries));
    return new ReviewToolDecision("record_confirmed_terms", arguments, proposal.reason());
}
```

- [ ] **Step 4: Re-run the provider test class and verify B3 routing passes**

Run:
```bash
mvn -q "-Dtest=PromptBackedNextStepDecisionProviderTest" test
```

Expected:
- PASS for proposal routing, low-priority-only exclusion, `NOT_APPLICABLE` fallback, multiple-pair ordering, and local assembly tests.
- Existing repair tests still PASS.

- [ ] **Step 5: Commit the provider two-phase routing**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/prompt/RecordConfirmedTermsProposalPromptBuilder.java src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java
git commit -m "feat: route record confirmed terms through proposal stage"
```

### Task 4: Adjust Main Prompt Responsibilities

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

- [ ] **Step 1: Add failing prompt tests for narrowed main-prompt responsibility**

```java
@Test
void shouldKeepRecordConfirmedTermsBoundaryButRemoveEntriesMapTeachingFromInvestigationPrompt() {
    String prompt = new InvestigationPromptBuilder().build(
            sampleSession(),
            ReviewToolRegistry.defaultRegistry().definitions(),
            List.of()
    );

    assertTrue(prompt.contains("record_confirmed_terms"));
    assertTrue(prompt.contains("not only in reason"));
    assertFalse(prompt.contains("{\"entries\":{\"<source-term>\":\"<target-term>\"}}"));
}
```

- [ ] **Step 2: Run the prompt test class**

Run:
```bash
mvn -q "-Dtest=ReviewPromptBuilderTest" test
```

Expected:
- FAIL because prompt text still contains final map-shape teaching or lacks proposal-specific wording.

- [ ] **Step 3: Update main prompts minimally**

```java
// InvestigationPromptBuilder output reminder
- When stable term registration is needed, use the dedicated record_confirmed_terms proposal path.
- Do not invent final arguments.entries in this step.
```

```java
// ReviewAgentSystemPromptBuilder tool rules
- record_confirmed_terms uses a dedicated proposal stage for pair extraction before final arguments assembly.
- Global boundaries still apply: no explicit pair -> do not record; low-priority-only basis -> do not record.
```

- [ ] **Step 4: Re-run the prompt test class and verify it passes**

Run:
```bash
mvn -q "-Dtest=ReviewPromptBuilderTest" test
```

Expected:
- PASS for updated prompt responsibility tests.
- Existing boundary assertions still PASS.

- [ ] **Step 5: Commit the prompt responsibility update**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java
git commit -m "feat: narrow main prompt responsibility for term recording"
```

### Task 5: Implement Runtime Containment For Proposal And Next-Step Failures

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`

- [ ] **Step 1: Add failing runtime tests for current-focus failure containment**

```java
@Test
void shouldContinueProjectWhenRecordConfirmedTermsProposalFailsForCurrentFocus() {
    InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1"), chunk("chunk-2", "translated-2")));
    ReviewAgentStructuredGenerationPort generationPort = new ThrowingProposalGenerationPort(
            "Review agent invalid record_confirmed_terms proposal: conflicting_target_for_sourceTerm:Le Bouquet"
    );

    AutonomousProjectReviewAgent agent = buildAgent(reader, generationPort);

    ProjectReviewRuntimeSession result = agent.run(ProjectReviewRuntimeSession.start("project-1", List.of("chunk-1", "chunk-2")), "");

    assertNotEquals(ReviewProjectStopReason.LLM_CALL_FAILED, result.stopReason());
    assertTrue(result.processTrail().stream().anyMatch(it -> it.contains("chunk-1")));
    assertTrue(result.processTrail().stream().anyMatch(it -> it.contains("RECORD_CONFIRMED_TERMS_PROPOSAL_FAILED")));
    assertTrue(result.processTrail().stream().anyMatch(it -> it.contains("conflicting_target_for_sourceTerm")));
    assertTrue(result.issueBacklog().openIssues().stream().anyMatch(it -> it.contains("rawOutput={")));
}

@Test
void shouldStillFailProjectForNonContainableStructuredOutputFailure() {
    InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
    ReviewAgentStructuredGenerationPort generationPort = new ThrowingEvaluationGenerationPort(
            "Review agent evaluation structured generation output cannot be parsed as structured JSON"
    );

    AutonomousProjectReviewAgent agent = buildAgent(reader, generationPort);

    ProjectReviewRuntimeSession result = agent.run(ProjectReviewRuntimeSession.start("project-1", List.of("chunk-1")), "");

    assertEquals(ReviewProjectStopReason.LLM_CALL_FAILED, result.stopReason());
}
```

- [ ] **Step 2: Run the runtime test class and verify these tests fail**

Run:
```bash
mvn -q "-Dtest=AutonomousProjectReviewAgentTest" test
```

Expected:
- FAIL because runtime still upgrades `LlmStructuredOutputException` to project-wide `LLM_CALL_FAILED`.

- [ ] **Step 3: Implement minimal current-focus failure transition for next-step / proposal / proposal-assembly failures only**

```java
catch (LlmStructuredOutputException ex) {
    if (!isCurrentFocusContainableStructuredFailure(ex)) {
        ProjectReviewRuntimeSession previous = current;
        current = current.failLlmCall(summarizeLlmFailure(ex));
        persistenceHook.afterTransition(previous, current);
        runtimeVisualizer.projectFinished(current);
        return current;
    }
    ProjectReviewRuntimeSession previous = current;
    current = current.failCurrentFocusAndContinue(
            current.currentFocusChunkId().orElse("(unknown)"),
            summarizeLlmFailure(ex)
    );
    persistenceHook.afterTransition(previous, current);
    continue;
}
```

```java
private boolean isCurrentFocusContainableStructuredFailure(LlmStructuredOutputException ex) {
    return ex instanceof ReviewAgentNextStepStructuredOutputException
            || ex instanceof RecordConfirmedTermsProposalException
            || ex instanceof RecordConfirmedTermsAssemblyException;
}
```

```java
public ProjectReviewRuntimeSession failCurrentFocusAndContinue(String chunkId,
                                                               String failureCode,
                                                               String diagnosticSummary,
                                                               String rawOutput) {
    return withIssueBacklog(issueBacklog.addOpenIssue(
                    "focusFailure:" + chunkId + ":" + failureCode + ":" + diagnosticSummary + "; rawOutput=" + rawOutput))
            .appendProcess("focusFailed=" + chunkId + ":" + failureCode + ":" + normalizeProcess(diagnosticSummary))
            .enterSelectingFocus();
}
```

```java
throw new RecordConfirmedTermsAssemblyException(
        "conflicting_target_for_sourceTerm:" + entry.sourceTerm(),
        objectMapper.writeValueAsString(proposal)
);
```

实现约束：
1. containment 判定不得基于异常消息文本 `contains(...)`。
2. next-step 普通结构化失败、proposal 结构化失败、proposal 本地 assembly 失败，分别使用稳定异常类型：
   - `ReviewAgentNextStepStructuredOutputException`
   - `RecordConfirmedTermsProposalException`
   - `RecordConfirmedTermsAssemblyException`
3. `failCurrentFocusAndContinue(...)` 的稳定诊断落点固定为：
   - `processTrail`：保存摘要版 `chunkId + failureCode + diagnosticSummary`
   - `issueBacklog`：保存完整诊断，至少包含 `chunkId + failureCode + rawOutput`
4. 不要求把完整 rawOutput 塞进 `processTrail`；完整原始输出应进入 `issueBacklog` 或等价稳定诊断结构。

- [ ] **Step 4: Re-run the runtime test class and verify it passes**

Run:
```bash
mvn -q "-Dtest=AutonomousProjectReviewAgentTest" test
```

Expected:
- PASS for proposal failure containment and existing project progression tests.
- No assertion should still require project-wide `LLM_CALL_FAILED` for next-step / proposal / proposal-assembly structured-output failures tied to current focus.
- Other structured-output failures outside this scope should retain the original project-fatal behavior.

- [ ] **Step 5: Commit the runtime containment work**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java
git commit -m "feat: contain focus-level structured output failures"
```

### Task 6: Final Regression Sweep And Docs Sync

**Files:**
- Modify: `docs/handoff.md`
- Test: `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolDecisionContractValidatorTest.java` (if modified)

- [ ] **Step 1: Update handoff with implementation status and rollout dependency**

```markdown
## 2026-04-21 `record_confirmed_terms` Two-Phase Implementation Status
1. `record_confirmed_terms` now uses proposal DTO extraction plus local `arguments.entries` assembly.
2. Proposal `NOT_APPLICABLE` falls back to ordinary next-step decision.
3. Proposal structured failure and assembly failure are contained at current-focus scope and no longer directly escalate to project-wide `LLM_CALL_FAILED`.
4. Runtime containment shipped in the same rollout and remains a required safety boundary.
```

- [ ] **Step 2: Run the focused regression suite**

Run:
```bash
mvn -q "-Dtest=OpenAiCompatibleReviewAgentStructuredGenerationClientTest,PromptBackedNextStepDecisionProviderTest,AutonomousProjectReviewAgentTest,ReviewPromptBuilderTest" test
```

Expected:
- PASS for all focused suites.
- If validator error code changed, include `ReviewToolDecisionContractValidatorTest` in the same run and expect PASS.

- [ ] **Step 3: Run one integration-style follow-up for the combined rollout**

Run:
```bash
mvn -q "-Dtest=OpenAiCompatibleReviewAgentStructuredGenerationClientTest,PromptBackedNextStepDecisionProviderTest,AutonomousProjectReviewAgentTest" test
```

Expected:
- PASS with no `entries:{}` project-fatal regression.
- PASS with provider proposal path and runtime containment both active.

- [ ] **Step 4: Commit docs and regression lock-in**

```bash
git add docs/handoff.md src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java
git commit -m "docs: sync two-phase term recording rollout status"
```

---

## Self-Review

### Spec Coverage
- `record_confirmed_terms` 专项两阶段：Task 1-4 覆盖。
- B3 窄路由与高权重证据判定：Task 3 覆盖，包括 low-priority-only 不进入 proposal 的测试。
- proposal/assembly 失败不回退普通 next-step：Task 3 + Task 5 覆盖。
- 与 runtime containment 同轮落地：Task 5 + Task 6 覆盖，包括“containable vs non-containable”分流测试与稳定诊断落点。
- prompt 职责下沉：Task 4 覆盖。

### Placeholder Scan
- 无 `TODO` / `TBD` / “implement later” 占位。
- 每个任务包含具体文件、测试类、命令和期望结果。

### Type Consistency
- 新增 proposal 相关命名统一使用：`RecordConfirmedTermsProposal`、`RecordConfirmedTermEntry`、`generateRecordConfirmedTermsProposal(...)`。
- provider 落点统一使用“proposal path / ordinary next-step / current-focus failure containment”术语。
- containment 异常命名统一使用：`ReviewAgentNextStepStructuredOutputException`、`RecordConfirmedTermsProposalException`、`RecordConfirmedTermsAssemblyException`。
