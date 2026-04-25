# Review Agent Stability Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Before implementation, re-read `docs/superpowers/plans/2026-04-18-review-agent-direction-anchor.md` and `docs/superpowers/plans/2026-04-19-review-agent-structured-tool-memory-plan.md`.

**Goal:** Reduce Review Agent prompt-following drift during long autonomous review runs by moving critical constraints from prompt-only guidance into explicit runtime validation and local replanning hints.

**2026-04-20 execution status:** User approved the initial hardening set. Implemented items: per-sourceTerm duplicate guard for `read_confirmed_terms`, formal guidance sync for that guard, runtime guard against `record_confirmed_terms` driven only by lookup miss plus low-authority notes, and console diagnostic output for `LLM_CALL_FAILED`.

**Architecture:** Keep the existing D -> PostDraftReviewPackage -> Review Agent field contract unchanged. Do not add tools, do not add `ReviewToolExecutor` switch cases, and do not create global tool history. Strengthen the current-focus runtime guardrails, prompt field semantics, and failure handling inside existing Review Agent boundaries.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Maven, existing Review Agent service/model/prompt classes.

---

## Scope And Red Lines

This plan is the single review document for the current Review Agent stability issues observed during the `book-draft-20260419151435` smoke run.

Do not implement anything in this plan until the user explicitly approves the relevant task.

Hard constraints:

- Do not add a Review Tool.
- Do not add a `ReviewToolExecutor` switch case.
- Do not expose `candidateUpdates` in prompt or tools.
- Do not change database schema.
- Do not change D -> PostDraftReviewPackage -> persistence -> Review Agent field contract.
- Do not convert `NO_PROGRESS` to HITL.
- Do not turn HITL into automatic fallback.
- Do not write runtime temporary state back into stable domain objects.
- Prompt and user-facing correction hints should be Chinese-first.

---

## Observed Problems

### P1: `read_confirmed_terms` reason/arguments mismatch

Observed log:

```text
reason=本次必须只查 ['Le Bouquet']，剔除已确认的 'Le Condé' 和 'La Pergola'
arguments={sourceTerms=[Le Condé, La Pergola, Le Bouquet]}
```

Root cause:

- The LLM reason text and structured `arguments` are not guaranteed to be internally consistent.
- Current validation only checks that `sourceTerms` is a legal list.
- Current duplicate guard checks exact call signature only. `[Le Condé]` and `[Le Condé, La Pergola, Le Bouquet]` are different signatures, so mixed repeated terms can pass.

Impact:

- Wastes tool calls.
- Reinforces already-known terms in context.
- Can keep the agent attracted to stale high-frequency terms.
- Makes the agent appear to contradict itself.

Recommended fix:

- Add a current-focus **per-sourceTerm successful lookup guard** for `read_confirmed_terms`.
- If any requested normalized source term has already been successfully looked up or missed in the current focus, reject the call even when the full list signature is new.
- Keep exact same-signature guard for backward-compatible rejection messages.

Approval note:

- This is intentionally stronger than the original D-08-lite same-signature-only duplicate guard. The live smoke run showed same-signature-only is insufficient.

### P2: Low-authority D self-notes still over-influence action choice

Observed patterns:

- `decisionNotes` saying `first-name-confirmation-missing` caused the agent to treat descriptive phrases as mandatory project-level term registration candidates.
- `transitionNote` caused the agent to query terms not present in the current chunk.
- `confirmedTermLookupMiss` sometimes led the agent toward `draft_revision` or `record_confirmed_terms`.

Current mitigation already present:

- `ReviewAgentSystemPromptBuilder` contains `[输入字段语义与权威级别]`.
- `PromptBackedStrategyEvaluationService` says low-authority notes cannot alone trigger human review or revision.
- `candidateUpdates` has been removed from Review Agent prompt rendering.

Remaining risk:

- Prompt guidance reduces probability but does not prevent invalid action choices.
- Tool-level validation should block the most expensive or harmful cases.

Recommended fix:

- Keep prompt guidance.
- Add targeted hard guards only where the behavior is objectively invalid:
  - duplicate per-term `read_confirmed_terms`;
  - repeated successful `draft_revision`;
  - `complete_working_set` pending-only validation already exists.

### P3: Invalid structured tool decision can still stop a run

Observed log:

```text
LlmStructuredOutputException: Review agent invalid structured tool decision: invalid_argument:entries
```

Current mitigation already present:

- `AutonomousProjectReviewAgent` catches `LlmStructuredOutputException` from next-step decision.
- Session transitions to `FAILED` / `LLM_CALL_FAILED` instead of crashing the whole Spring process.

Remaining risk:

- Need keep regression tests covering this behavior.

### P4: Working set can include completed context chunks

Observed log:

```text
workingSet=[chunk-7, chunk-6]
complete_working_set arguments={chunkIds=[chunk-7, chunk-6]}
status=rejected summary=complete_working_set chunkIds must still be pending, offendingChunkId=chunk-6
```

Current mitigation already present:

- `complete_working_set` rejects non-pending chunks.
- Local correction hint tells the agent to submit only pending chunks.

Remaining risk:

- This behavior is acceptable. Do not auto-prune completed chunks silently because that would hide model mistakes.

---

## Files

Main code:

- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
  - Add per-sourceTerm duplicate detection for `read_confirmed_terms`.
  - Add a specific local correction hint for mixed repeated term lookup.

- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ToolCallSignature.java`
  - Optional: expose normalized read-confirmed-term keys in a helper if needed.
  - Keep existing `key()` and `display()` behavior for exact-signature compatibility.

- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
  - Only if prompt wording needs to mirror the new hard guard.
  - Do not add candidateUpdates back.

- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
  - Only if formal `read_confirmed_terms` guidance needs one sentence saying repeated terms are rejected per source term.

Tests:

- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`
  - Add tests for mixed repeated hit and mixed repeated miss.

- Modify: `src/test/java/io/quillloom/application/postdraft/review/ToolCallSignatureTest.java`
  - Add tests only if `ToolCallSignature` exposes normalized read term keys.

- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
  - Add tests only if prompt text changes.

Docs:

- Modify: `docs/superpowers/plans/2026-04-20-review-agent-stability-hardening-plan.md`
  - Append newly observed issues and approved changes here before coding.

- Modify: `docs/handoff.md`
  - After implementation, record the final behavior and verification commands.

---

## Task 1: Current-Focus Per-Term Duplicate Guard For `read_confirmed_terms`

**Status:** Completed on 2026-04-20 after user approval.

**Purpose:** Block calls where `arguments.sourceTerms` contains any term already successfully queried in the current focus, even if the full argument list is a new combination.

**Files:**

- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- Optional modify: `src/main/java/io/quillloom/application/postdraft/review/model/ToolCallSignature.java`

- [ ] **Step 1: Write failing test for mixed repeated hit**

Add a test like this to `ReviewToolExecutorGuardrailTest`:

```java
@Test
void shouldRejectReadConfirmedTermsWhenRequestContainsPreviouslySuccessfulHitInMixedList() {
    ReviewToolExecutor executor = new ReviewToolExecutor();
    PostDraftReviewSession session = sessionWithConfirmedTerms(Map.of(
            "Le Conde", "孔代咖啡馆",
            "La Pergola", "拉佩尔戈拉"
    ));
    PostDraftReviewRuntime runtime = runtimeFor(session, "chunk-7");

    ReviewToolExecutionResult first = executor.execute(runtime,
            new ReviewToolDecision("read_confirmed_terms",
                    Map.of("sourceTerms", List.of("Le Conde")),
                    "lookup known term"));
    assertTrue(first.isSuccess());

    ReviewToolExecutionResult second = executor.execute(first.runtime(),
            new ReviewToolDecision("read_confirmed_terms",
                    Map.of("sourceTerms", List.of("Le Conde", "Le Bouquet")),
                    "本次必须只查 Le Bouquet"));

    assertTrue(second.isRejected());
    assertTrue(second.rejection().rejectionReason().contains("redundant_successful_term_lookup"));
    assertTrue(second.rejection().rejectionReason().contains("le conde"));
    assertTrue(second.runtime().session().historyLog().entries().stream()
            .anyMatch(entry -> entry.contains("只保留尚未查过的 sourceTerm")));
}
```

Expected initial result:

```text
FAIL: second call currently succeeds because [Le Conde, Le Bouquet] is a new full signature.
```

- [ ] **Step 2: Write failing test for mixed repeated miss**

Add a test like this:

```java
@Test
void shouldRejectReadConfirmedTermsWhenRequestContainsPreviouslySuccessfulMissInMixedList() {
    ReviewToolExecutor executor = new ReviewToolExecutor();
    PostDraftReviewSession session = sessionWithConfirmedTerms(Map.of());
    PostDraftReviewRuntime runtime = runtimeFor(session, "chunk-20");

    ReviewToolExecutionResult first = executor.execute(runtime,
            new ReviewToolDecision("read_confirmed_terms",
                    Map.of("sourceTerms", List.of("le brun à veste de daim")),
                    "lookup descriptive label"));
    assertTrue(first.isSuccess());
    assertTrue(first.summary().contains("confirmedTermLookupMiss"));

    ReviewToolExecutionResult second = executor.execute(first.runtime(),
            new ReviewToolDecision("read_confirmed_terms",
                    Map.of("sourceTerms", List.of("le brun à veste de daim", "Le Bouquet")),
                    "lookup another term"));

    assertTrue(second.isRejected());
    assertTrue(second.rejection().rejectionReason().contains("redundant_successful_term_lookup"));
    assertTrue(second.rejection().rejectionReason().contains("le brun à veste de daim"));
}
```

Expected initial result:

```text
FAIL: miss currently counts as success only for exact same signature, not mixed lists.
```

- [ ] **Step 3: Run red tests**

Run:

```powershell
mvn -q "-Dtest=ReviewToolExecutorGuardrailTest#shouldRejectReadConfirmedTermsWhenRequestContainsPreviouslySuccessfulHitInMixedList,ReviewToolExecutorGuardrailTest#shouldRejectReadConfirmedTermsWhenRequestContainsPreviouslySuccessfulMissInMixedList" test
```

Expected:

```text
BUILD FAILURE
Both new tests fail because mixed repeated source terms are not rejected yet.
```

- [ ] **Step 4: Implement minimal per-term duplicate detection**

Implementation shape in `ReviewToolExecutor.executeReadConfirmedTerms(...)`:

```java
ToolCallSignature signature = ToolCallSignature.forReadConfirmedTerms(sourceTerms);
if (hasSuccessfulToolCall(runtime.session(), signature)) {
    String detail = "redundant_successful_tool_call:" + signature.key();
    return rejected(runtime, call, detail);
}

List<String> repeatedSourceKeys = successfulReadConfirmedTermKeys(runtime.session()).stream()
        .filter(requestedSourceKeys(sourceTerms)::contains)
        .sorted()
        .toList();
if (!repeatedSourceKeys.isEmpty()) {
    String detail = "redundant_successful_term_lookup:sourceTerms=[" + String.join(", ", repeatedSourceKeys) + "]";
    return rejected(runtime, call, detail);
}
```

Implementation notes:

- Normalize requested terms with `TermTextNormalizer.keyText(...)`.
- Existing successful traces can be read from `ReviewToolTrace.callSignature()`.
- Only consider traces where:
  - `trace.toolName().equals("read_confirmed_terms")`;
  - `trace.callSignature()` starts with `read_confirmed_terms:sourceTerms=[`;
  - trace is a successful tool trace, not a rejection transcript.
- Keep exact signature check first so existing duplicate tests and messages remain stable.

- [ ] **Step 5: Add local correction hint**

Add a branch in `ReviewToolExecutor.buildLocalCorrectionHint(...)`:

```java
if (detail.startsWith("redundant_successful_term_lookup:")) {
    return "local_replan_hint -> read_confirmed_terms 中包含已经成功查过或已确认 miss 的 sourceTerm："
            + detail.substring("redundant_successful_term_lookup:".length())
            + "。如果确实需要继续查询，只能从 arguments.sourceTerms 删除已查过项，"
            + "只保留尚未查过且实际出现在当前 workingSet 的 sourceTerm；"
            + "如果证据已经足够，优先 complete_working_set 或 evaluate_focus。";
}
```

- [ ] **Step 6: Run targeted tests**

Run:

```powershell
mvn -q "-Dtest=ReviewToolExecutorGuardrailTest" test
```

Expected:

```text
BUILD SUCCESS
```

---

## Task 2: Formal Tool Guidance Mirrors Per-Term Duplicate Guard

**Status:** Completed on 2026-04-20 after Task 1 implementation.

**Purpose:** Make the prompt/tool contract say the same thing as the runtime guard, without relying on prompt as the only enforcement layer.

**Files:**

- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolRegistryTest.java`
- Optional modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

- [ ] **Step 1: Add failing registry test**

Add assertion to the existing `read_confirmed_terms` definition test:

```java
ReviewToolDefinition definition = registry.require("read_confirmed_terms");
assertTrue(definition.repeatPolicy().contains("同一 focus"));
assertTrue(definition.repeatPolicy().contains("sourceTerm"));
assertTrue(definition.repeatPolicy().contains("hit 或 miss"));
```

Expected initial result:

```text
FAIL if current repeatPolicy only mentions same-signature duplicate behavior.
```

- [ ] **Step 2: Update formal definition**

Update only the `read_confirmed_terms` `repeatPolicy` / `nextStepGuidance` text. Do not change allowed arguments.

Required meaning:

```text
同一 focus 内，某个 sourceTerm 一旦 read_confirmed_terms 返回 hit 或 confirmedTermLookupMiss，
后续 read_confirmed_terms 的 arguments.sourceTerms 不得再次包含该 sourceTerm；
如果要查其他词，只保留尚未查过的 sourceTerm。
```

- [ ] **Step 3: Run prompt/registry tests**

Run:

```powershell
mvn -q "-Dtest=ReviewToolRegistryTest,ReviewPromptBuilderTest" test
```

Expected:

```text
BUILD SUCCESS
```

---

## Task 3: Keep Low-Authority Notes From Triggering Term Registration

**Status:** Mostly implemented; keep as regression task if new logs show relapse.

**Purpose:** Prevent `decisionNotes` / `transitionNote` / `translatorCommentary` from acting like commands.

**Current expected behavior:**

- `transitionNote` is a low-weight continuity hint.
- `decisionNotes` is a low-weight risk hint.
- `translatorCommentary` is a low-weight D-stage self-explanation.
- These fields cannot alone trigger:
  - `record_confirmed_terms`;
  - `draft_revision`;
  - `request_human_review`.
- `confirmedTermLookupMiss` is not a reason to register a project-level confirmed term.
- Descriptive labels such as clothing, appearance, role, or vague identity phrases are not project-level proper names by default.

**Files:**

- Existing: `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- Existing: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedStrategyEvaluationService.java`
- Existing: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

- [ ] **Step 1: If relapse is observed, paste the log under "New Observations" in this document**

Required log details:

```text
anchor chunk
workingSet
toolName
arguments
reason
previous tool_result lines
final state
```

- [ ] **Step 2: Add one targeted failing prompt or e2e test**

Example assertion:

```java
assertTrue(prompt.contains("decisionNotes 是 D 阶段风险提示，低权重参考"));
assertTrue(prompt.contains("confirmedTermLookupMiss 不是登记 confirmed term 的理由"));
assertTrue(prompt.contains("描述性称呼、外貌、衣着、身份短语，不默认视为项目级专名"));
```

- [ ] **Step 3: Run targeted prompt/evaluation tests**

Run:

```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest,PromptBackedStrategyEvaluationServiceTest" test
```

Expected:

```text
BUILD SUCCESS
```

If `PromptBackedStrategyEvaluationServiceTest` does not exist, do not silently skip it. Either add the test or replace the command with the existing test class that covers evaluation prompt rendering.

---

## Task 4: Invalid Structured Tool Decision Must Fail Gracefully, Not Crash Spring

**Status:** Implemented; keep as regression task.

**Purpose:** Bad LLM structured output should stop the review run with `FAILED / LLM_CALL_FAILED`, not throw through Spring Boot startup.

**Files:**

- Existing: `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- Existing: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java`
- Existing: `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`

- [ ] **Step 1: Ensure regression test exists**

Expected test name:

```java
shouldFailProjectGracefullyWhenToolDecisionStructuredOutputIsInvalidAfterRepair
```

- [ ] **Step 2: Run regression test**

Run:

```powershell
mvn -q "-Dtest=AutonomousProjectReviewAgentTest#shouldFailProjectGracefullyWhenToolDecisionStructuredOutputIsInvalidAfterRepair" test
```

Expected:

```text
BUILD SUCCESS
```

---

## Task 5: Final Verification For Any Approved Code Change

Run after implementing any approved task in this plan.

- [ ] **Step 1: Tool guardrail and memory tests**

Run:

```powershell
mvn -q "-Dtest=ReviewToolExecutorGuardrailTest,ToolCallSignatureTest,ReviewToolMemoryFormatterTest" test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 2: Prompt/registry/agent regression tests**

Run:

```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest,ReviewToolRegistryTest,AutonomousProjectReviewAgentTest,PostDraftReviewAgentEndToEndSmokeTest" test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 3: Full non-IT suite**

Run:

```powershell
mvn -q test -DskipITs
```

Expected:

```text
BUILD SUCCESS
```

Known acceptable console noise:

```text
NovelTranslationWorkflowServiceTest.shouldPreserveBusinessExceptionWhenTraceFlushFails
```

That test intentionally logs a business failure stack while Maven still exits successfully.

---

## New Observations

Append future run issues here before changing code. Use this format:

```text
Date/time:
ProjectId:
Anchor:
WorkingSet:
Observed tool call:
Observed tool result:
Why this is wrong:
Proposed hard guard or prompt change:
Approved by user: yes/no
```

### 2026-04-20: Mixed repeated `read_confirmed_terms` terms

```text
ProjectId: book-draft-20260419151435
Anchor: chunk-7
WorkingSet: [chunk-7, chunk-6]
Observed tool call:
  tool=read_confirmed_terms
  arguments={sourceTerms=[Le Condé, La Pergola, Le Bouquet]}
  reason says: 本次必须只查 ['Le Bouquet']，剔除已确认的 'Le Condé' 和 'La Pergola'
Observed tool result:
  confirmedTerm=Le Condé->孔代咖啡馆
  confirmedTerm=La Pergola->拉佩尔戈拉
  confirmedTermLookupMiss=[Le Bouquet]
Why this is wrong:
  Le Condé and La Pergola were already known in the current focus.
  The reason text correctly identified the desired action, but structured arguments still included repeated terms.
Proposed hard guard:
  Add per-sourceTerm successful lookup guard for read_confirmed_terms within current focus.
Approved by user: yes
Implemented:
  Yes. `read_confirmed_terms` now rejects any current-focus request containing a sourceTerm that already returned hit or miss.
```

### 2026-04-20: Lookup miss plus low-authority note led toward term registration and LLM call failure

```text
ProjectId: book-draft-20260419151435
Anchor: chunk-41
WorkingSet: [chunk-41]
Observed tool call:
  tool=read_confirmed_terms
  arguments={sourceTerms=[Bernolle]}
  reason says:
    当前 workingSet 中明确出现高频核心人名 'Bernolle'，
    decisionNotes 明确指出 '高频核心人名尚未进入当前生效译名表'，
    若未命中（confirmedTermLookupMiss），则后续需在 record_confirmed_terms 中登记稳定译名。
Observed tool result:
  confirmedTermLookupMiss=[Bernolle]
Observed final state:
  project_finished state=FAILED stopReason=LLM_CALL_FAILED agentStopReason=FAILED
Why this is wrong:
  The lookup itself may be legitimate because Bernolle appears in the current workingSet.
  The faulty part is the reason chain: confirmedTermLookupMiss plus decisionNotes is treated as a path toward record_confirmed_terms.
  Existing prompt says lookup miss is not a reason to register a confirmed term, but the model still drifts toward that behavior.
  The following LLM call failed before a valid tool decision was produced, so the exact invalid structured output is not visible in the console excerpt.
Proposed hard guard or prompt change:
  Add a runtime guard for record_confirmed_terms: if the only support is previous confirmedTermLookupMiss plus low-authority notes,
  reject with a local_replan_hint saying Review Agent does not fill D missing confirmedTermUpdates.
  If record_confirmed_terms remains allowed, require entries to be supported by current sourceText/translatedText evidence and not merely by decisionNotes/transitionNote/translatorCommentary.
  Also improve LLM_CALL_FAILED console diagnostics to print the invalid structured-output reason if available, without exposing secrets or full prompts.
Approved by user: yes
Implemented:
  Yes. `record_confirmed_terms` now rejects calls for previously missed sourceTerms when the reason is driven by confirmedTermLookupMiss and low-authority notes.
  `project_finished` console output now includes the latest processTrail diagnostic for LLM_CALL_FAILED.
```

---

## Implementation Order

1. Task 1 only after explicit approval.
2. Task 2 only after Task 1 is accepted or if the user approves prompt/definition text sync separately.
3. Task 3 only if a new relapse log shows low-authority notes still cause wrong actions.
4. Task 4 is regression only unless the graceful failure test breaks.
5. Task 5 after any code change.
