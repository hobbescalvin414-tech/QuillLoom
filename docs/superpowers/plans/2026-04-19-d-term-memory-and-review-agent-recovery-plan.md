# D 术语记忆链路与 Review Agent 术语冲突恢复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 D 初稿阶段项目级术语记忆没有作为稳定产物传递给 post-draft review package 的问题，并修正 Review Agent 在术语冲突下卡在 `KEEP` 策略的行为，然后从 `book/` 重新生成干净 D 初稿，继续 127 chunk Review Agent 冒烟。

**Architecture:** 保持当前受控流水线边界：C0 负责项目级知识沉淀，D 负责受控 chunk 翻译与 `confirmedTermUpdates` 增量术语确认，Review Agent 负责 post-draft 审校与修订。修复重点是让 D 的最终 `ProjectMemorySnapshot` 成为一等输出，并让术语冲突在 D 阶段和 post-draft package 聚合阶段显式失败，而不是被 `putIfAbsent` 静默吞掉。

**Tech Stack:** Java 17, Spring Boot 3.5, Jackson, Maven, PostgreSQL, PowerShell smoke scripts.

---

## 1. 当前问题总览

### 1.1 D 的项目级术语记忆没有作为一等结果返回

理论链路应为：

```text
C0 初始项目级术语 / 知识
+ D chunk-1 confirmedTermUpdates
+ D chunk-2 confirmedTermUpdates
+ ...
= D 完整初稿结束后的最终 ProjectMemorySnapshot
```

当前 `TranslationApplicationService.translateChunks(...)` 内部确实维护了局部演化变量：

```java
ProjectMemorySnapshot effectiveProjectMemory = projectMemory;
...
effectiveProjectMemory = evolveProjectMemory(effectiveProjectMemory, draft);
```

这保证后续 chunk 在翻译过程中能看到前面 chunk 的 `confirmedTermUpdates`。但该方法最终只返回：

```java
return List.copyOf(completedDrafts);
```

最终 `effectiveProjectMemory` 没有返回、没有进入 workflow state、没有作为稳定产物保存。它不是 Review Agent 运行期临时状态，而是 D 阶段的稳定输出。

### 1.2 `runDraftWorkflow(...)` 保存 post-draft package 时仍使用初始 `projectMemory`

当前 `NovelTranslationWorkflowService.runDraftWorkflow(...)` 的关键路径是：

```java
state = draftAllChunks(state, projectMemory, chapterMemory, runtimeOptions);
state = compileDrafts(state);
savePostDraftReviewPackage(state, projectMemory);
```

如果入口传入的 `projectMemory` 为空，`savePostDraftReviewPackage(...)` 也只会看到空的初始记忆。当前 smoke 入口确实传入空项目记忆：

```java
new ProjectMemorySnapshot(projectId, Map.of(), List.of(), List.of())
```

这导致 `PostDraftReviewPackageAssembler.buildTermState(...)` 里的：

```java
confirmed.putAll(projectMemory.confirmedTerms());
```

在真实 smoke 路径中基本是空操作。

### 1.3 `Le Condé` 暴露了 D 输出内部不一致

真实 baseline 中暴露的问题：

```text
chunk-4 translatedText: ...孔代咖啡馆...
chunk-4 translatorCommentary: 声称 Le Condé => 孔代咖啡馆
chunk-4 confirmedTermUpdates: {}

chunk-9 translatedText: ...勒孔代咖啡馆...
chunk-9 confirmedTermUpdates: { Le Condé -> 勒孔代咖啡馆 }
```

因此 Review Agent 后续看到：

```text
chunk 正文: 孔代咖啡馆
项目术语表: Le Condé -> 勒孔代咖啡馆
```

这不是 Review Agent 的单点问题。根因是 D 阶段出现了“正文或 commentary 使用了一个稳定译名，但没有登记到 `confirmedTermUpdates`”的情况，后续 chunk 又确认了另一个译名。

### 1.4 当前术语合并逻辑会静默吞冲突

当前至少存在三类需要审计的合并点：

```java
// TranslationApplicationService.evolveProjectMemory(...)
confirmed.putIfAbsent(source, target);

// PostDraftReviewPackageAssembler.buildTermState(...)
confirmed.putAll(projectMemory.confirmedTerms());
draft.confirmedTermUpdates().forEach(confirmed::putIfAbsent);

// WorkflowDraftRunResponse / API projection 中的 glossary 聚合
confirmedTermUpdates.forEach(...putIfAbsent...);
```

`putIfAbsent` 的问题是：当后续 chunk 对同一 source term 给出不同 target term 时，系统会静默保留第一次结果，丢掉后续冲突。这个行为违反“不要兜底掩盖问题”。

必须修正的是 D 阶段自身的 `evolveProjectMemory(...)`。如果只在 Assembler 阶段检测冲突，那么 127 chunk 会完整跑完后才失败，代价太高。D 应在冲突发生的 chunk 当场失败，错误信息要包含冲突源词、已有译名、新译名、chunkId。

Assembler 仍需要保留聚合级冲突检测，作为第二道防线。原因是 post-draft package 可能由测试、迁移、旧数据或其他路径构造，不应假设所有输入都已经被 D 阶段校验过。

### 1.5 现有 validator 覆盖需要准确理解

当前 `ChunkTranslationResultValidator` 已有部分诊断能力：

- `confirmed-term-conflict`：当当前 chunk 尝试确认与已有 confirmed term 不同的译名时，产生 decision note，并会影响规范化后的输出。
- `first-name-confirmation-missing`：只覆盖 `looksLikeCorePersonName()` 判定的高频核心人名，条件偏人名，不等价于全局术语抽取。
- `glossary-entry-not-applied`：由 `GlossaryComplianceIssueDetector` 提供，检测给定 glossary 是否被正文应用。

本轮新增不是替代 validator，而是补上两层缺口：

- D 阶段聚合记忆的冲突不再静默吞掉，`evolveProjectMemory(...)` 必须 fail-fast。
- post-draft package 聚合时不再静默吞掉跨 chunk 冲突，`PostDraftReviewPackageAssembler` 必须 error 级阻断。

`Le Condé` 这类场所名不应依赖 `first-name-confirmation-missing`。即使它含大写和空格，也不应把“人名启发式”当成场所术语的一般保证。本轮不做复杂 NLP 自动抽取，只修复已结构化写出的 `confirmedTermUpdates` 冲突链路。

### 1.6 Review Agent 在 `KEEP` 策略下发现术语冲突后不会升级

Review Agent 当前工具 guardrail 正确禁止在 `KEEP` strategy 下直接 `draft_revision`。但当 LLM 发现：

```text
chunk 正文与 confirmed terms 不一致
```

它应先调用 `evaluate_focus` 升级策略，再 `draft_revision`。当前 prompt / harness 对这一路径约束不够明确，导致模型反复 `read_confirmed_terms`，最终卡死。

这不是要放松 guardrail。正确修复是：

```text
KEEP + 发现术语冲突
=> evaluate_focus
=> LIGHT_EDIT / DEEP_EDIT
=> draft_revision
```

## 2. 设计目标

1. D 阶段最终项目记忆必须作为稳定产物向下游传递。
2. D 阶段术语冲突必须当场显式失败，不允许在 `evolveProjectMemory(...)` 中静默 `putIfAbsent`。
3. Post-draft package 聚合阶段必须保留冲突检测作为防线。
4. 本轮只新增 `finalProjectMemory`，不新增 `finalChapterMemory`。
5. Review Agent 不改变 `KEEP` guardrail，只通过 `evaluate_focus` 自主升级策略。
6. 不新增 Review Tool，不往 `ReviewToolExecutor` 加 switch。
7. 不做全局译名替换工具；这是后置功能。
8. 不做 checkpoint 持久化，不做流式输出改造。

## 3. 解决方案

### 3.1 新增 `TranslationDraftRunResult`

新增文件：

```text
src/main/java/io/quillloom/application/translation/model/TranslationDraftRunResult.java
```

职责：

```java
public record TranslationDraftRunResult(
        List<ChunkTranslationDraft> drafts,
        ProjectMemorySnapshot finalProjectMemory
) {
    public TranslationDraftRunResult {
        drafts = drafts == null ? List.of() : List.copyOf(drafts);
    }
}
```

不包含 `finalChapterMemory`。本轮 `savePostDraftReviewPackage(...)` 和 `PostDraftReviewPackageAssembler.assemble(...)` 都不消费 chapter memory，把它加入 state 只会扩大构造点变更面。

### 3.2 `TranslationApplicationService` 返回完整 D 运行结果

新增方法：

```java
public TranslationDraftRunResult translateChunksWithMemory(
        PreprocessDossier dossier,
        ProjectMemorySnapshot projectMemory,
        ChapterMemorySnapshot chapterMemory,
        TranslationRuntimeOptions runtimeOptions
)
```

行为：

- 复用当前顺序翻译逻辑。
- 每个 chunk 翻译后继续演化 project memory。
- chapter memory 可继续按当前逻辑局部演化，但不作为本轮返回值。
- 最终返回 `TranslationDraftRunResult(drafts, finalProjectMemory)`。

保留现有 `translateChunks(...)` 作为兼容 wrapper：

```java
return translateChunksWithMemory(...).drafts();
```

### 3.3 `evolveProjectMemory(...)` 改为显式冲突检测

当前 `evolveProjectMemory(...)` 中的 `putIfAbsent` 必须替换为显式 merge。

推荐私有 helper：

```java
private static void mergeConfirmedTermOrThrow(
        Map<String, String> confirmed,
        String sourceTerm,
        String targetTerm,
        String evidence
) {
    if (sourceTerm == null || sourceTerm.isBlank() || targetTerm == null || targetTerm.isBlank()) {
        return;
    }
    String existing = confirmed.get(sourceTerm);
    if (existing == null) {
        confirmed.put(sourceTerm, targetTerm);
        return;
    }
    if (existing.equals(targetTerm)) {
        return;
    }
    throw new IllegalStateException(
            "confirmed_term_conflict: sourceTerm=%s, existing=%s, incoming=%s, evidence=%s"
                    .formatted(sourceTerm, existing, targetTerm, evidence));
}
```

调用证据应包含当前 chunk：

```text
chunkId=chunk-9
```

本轮选择 fail-fast，而不是只打 warning。理由：

- 冲突意味着后续 chunk 的输入记忆已经不可信。
- warning 不中断会让 127 chunk 跑完后才暴露，修复成本更高。
- 这符合 AGENTS.md 的“不兜底掩盖问题”。

如果现有 `ChunkTranslationResultValidator` 已经把冲突更新从 draft 中移除，`evolveProjectMemory(...)` 不会看到该冲突。这仍然可以接受，因为 validator 已经把 chunk 输出规范化了。但只要冲突进入 `draft.confirmedTermUpdates()`，`evolveProjectMemory(...)` 必须阻断。

### 3.4 Workflow state 只保存 `finalProjectMemory`

修改：

```text
src/main/java/io/quillloom/domain/workflow/NovelTranslationWorkflowState.java
```

增加字段：

```java
ProjectMemorySnapshot finalProjectMemory
```

不增加：

```java
ChapterMemorySnapshot finalChapterMemory
```

`advanceToDrafted(...)` 新签名：

```java
public NovelTranslationWorkflowState advanceToDrafted(
        List<ChunkTranslationDraft> chunkDrafts,
        ProjectMemorySnapshot finalProjectMemory
)
```

旧 overload 可保留以减少调用点改动，但应委托到新方法并传 `null`。

### 3.5 `NovelTranslationWorkflowService` 使用最终 project memory 保存 package

`draftAllChunks(...)` 改用：

```java
TranslationDraftRunResult result = translationApplicationService.translateChunksWithMemory(...);
return state.advanceToDrafted(result.drafts(), result.finalProjectMemory());
```

`runDraftWorkflow(...)` 保存时改为：

```java
savePostDraftReviewPackage(state, state.finalProjectMemory());
```

如果 `state.finalProjectMemory()` 为 null，应明确失败，而不是 fallback 到初始 `projectMemory`：

```java
if (state.finalProjectMemory() == null) {
    throw new IllegalStateException("final_project_memory_missing: projectId=" + state.projectId());
}
```

这可以避免未来某条旧路径重新引入“拿初始 memory 保存”的隐性 bug。

### 3.6 `PostDraftReviewPackageAssembler` 保留聚合级冲突检测

`buildTermState(...)` 当前逻辑：

```java
confirmed.putAll(projectMemory.confirmedTerms());
draft.confirmedTermUpdates().forEach(confirmed::putIfAbsent);
```

修改为：

```java
projectMemory.confirmedTerms().forEach((source, target) ->
        mergeConfirmedTerm(confirmed, source, target, "projectMemory"));

for (ChunkTranslationDraft draft : drafts) {
    draft.confirmedTermUpdates().forEach((source, target) ->
            mergeConfirmedTerm(confirmed, source, target, "chunkId=" + draft.chunkId()));
}
```

冲突错误格式与 `RepositoryBackedPostDraftReviewAgentTermWriter` 现有 `confirmed_term_conflict` 模式保持一致：

```text
confirmed_term_conflict: sourceTerm=Le Condé, existing=孔代咖啡馆, incoming=勒孔代咖啡馆, evidence=chunkId=chunk-9
```

说明：

- 即使 D 阶段已经 fail-fast，Assembler 仍要防御旧数据、测试夹具、手工构造数据。
- 不自动选择任一译名。
- 不做全局替换。

### 3.7 API projection 一致性审计

需要审计：

```text
src/main/java/io/quillloom/interfaces/api/dto/WorkflowDraftRunResponse.java
```

如果该类也用 `putIfAbsent` 聚合 glossary / confirmed terms，应改成与 Assembler 一致的显式冲突处理，或者明确它只是展示层投影，不承担术语状态构建。

推荐策略：

- 如果该 DTO 暴露的是“确认术语表”，冲突应显式失败，避免 API 返回一个静默吞冲突的假 glossary。
- 如果该 DTO 只是按 chunk 展示原始 `confirmedTermUpdates`，则不聚合，不使用 `putIfAbsent`。

### 3.8 Review Agent prompt / harness 修复

在 Review Agent system / investigation / evaluation prompt 中加入明确规则：

```text
当发现当前 chunk 译文与项目级 confirmed terms 不一致时：
1. 不要重复查询同一个术语。
2. 如果当前 strategy 是 KEEP，不允许直接 draft_revision。
3. 应先调用 evaluate_focus，说明术语冲突，并请求升级到 LIGHT_EDIT。
4. 升级后再调用 draft_revision 修复当前 chunk。
5. 如果无法判断项目术语表和 chunk 译文谁正确，可调用 request_human_review，而不是重复 read_confirmed_terms。
```

保持 guardrail：

- `KEEP` 下仍不允许 `draft_revision`。
- 模型必须通过 `evaluate_focus` 自主升级。
- 不新增工具。

### 3.9 Review Agent 测试覆盖术语冲突升级路径

新增 scripted e2e 或定向测试：

```text
runtime 当前 chunk strategy = KEEP
chunk effectiveTranslatedText 包含“孔代咖啡馆”
read_confirmed_terms 返回 Le Condé -> 勒孔代咖啡馆
LLM 下一步调用 evaluate_focus
evaluation 返回 LIGHT_EDIT
随后调用 draft_revision
最后 complete_working_set
```

断言：

- 不重复调用同一 `read_confirmed_terms` 超过 1 次。
- `draft_revision` 不会在 `KEEP` 下执行。
- strategy 升级后允许 `draft_revision`。
- 最终修订写入 `revisedTranslatedText`。

## 4. 受影响文件

### 4.1 D 术语记忆链路

Create:

- `src/main/java/io/quillloom/application/translation/model/TranslationDraftRunResult.java`

Modify:

- `src/main/java/io/quillloom/application/translation/service/TranslationApplicationService.java`
- `src/main/java/io/quillloom/domain/workflow/NovelTranslationWorkflowState.java`
- `src/main/java/io/quillloom/application/workflow/service/NovelTranslationWorkflowService.java`
- `src/main/java/io/quillloom/application/postdraft/assembler/PostDraftReviewPackageAssembler.java`
- `src/main/java/io/quillloom/interfaces/api/dto/WorkflowDraftRunResponse.java`

Tests:

- `src/test/java/io/quillloom/application/translation/service/TranslationApplicationServiceTest.java`
- `src/test/java/io/quillloom/application/workflow/service/NovelTranslationWorkflowServiceTest.java`
- `src/test/java/io/quillloom/application/workflow/service/NovelTranslationWorkflowServiceTraceTest.java`
- `src/test/java/io/quillloom/application/postdraft/PostDraftContinuationAssemblyTest.java`
- `src/test/java/io/quillloom/domain/postdraft/PostDraftReviewPackageContractTest.java`
- Any tests constructing `NovelTranslationWorkflowState` directly.

### 4.2 Review Agent 术语冲突恢复

Modify:

- `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- `src/main/java/io/quillloom/application/postdraft/review/prompt/EvaluationPromptBuilder.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java` only if prompt text is assembled there.

Tests:

- `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentEndToEndSmokeTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`

### 4.3 从 `book/` 重新跑 D 初稿的入口

Current options:

- Existing `BookWorkflowSmokeTest`
- Existing `BookWorkflowSampleSmokeTest`
- Existing `BookWorkflowFromC0SmokeTest`

Problem:

- Existing smoke tests may generate timestamp projectIds.
- Full-chain review needs a stable projectId to create baseline and start Review Agent.

Modify if needed:

- `src/test/java/io/quillloom/BookWorkflowSmokeTest.java`

Add property:

```text
quillloom.test.book-workflow.project-id
```

If non-blank, use it directly. Otherwise keep existing timestamp behavior.

## 5. Implementation Plan

### Task 1: Add D draft run result

**Files:**

- Create: `src/main/java/io/quillloom/application/translation/model/TranslationDraftRunResult.java`
- Modify: `src/main/java/io/quillloom/application/translation/service/TranslationApplicationService.java`
- Test: `src/test/java/io/quillloom/application/translation/service/TranslationApplicationServiceTest.java`

- [ ] **Step 1: Write failing test for final project memory**

Add a test where:

```text
chunk-1 returns confirmedTermUpdates={A=甲}
chunk-2 receives a project memory containing A=甲
final result contains finalProjectMemory.confirmedTerms().get("A") == "甲"
```

Expected before implementation: no `translateChunksWithMemory(...)` method.

- [ ] **Step 2: Implement `TranslationDraftRunResult`**

Use:

```java
public record TranslationDraftRunResult(
        List<ChunkTranslationDraft> drafts,
        ProjectMemorySnapshot finalProjectMemory
) {
    public TranslationDraftRunResult {
        drafts = drafts == null ? List.of() : List.copyOf(drafts);
    }
}
```

- [ ] **Step 3: Add `translateChunksWithMemory(...)`**

Move current `translateChunks(...)` loop into the new method and return final memory.

- [ ] **Step 4: Keep existing `translateChunks(...)` as compatibility wrapper**

Existing callers should not break.

- [ ] **Step 5: Run targeted tests**

```powershell
mvn -q "-Dtest=TranslationApplicationServiceTest" test
```

Expected: PASS.

### Task 2: Fail fast on D project memory confirmed term conflicts

**Files:**

- Modify: `src/main/java/io/quillloom/application/translation/service/TranslationApplicationService.java`
- Test: `src/test/java/io/quillloom/application/translation/service/TranslationApplicationServiceTest.java`

- [ ] **Step 1: Write failing conflict test**

Scenario:

```text
initial projectMemory.confirmedTerms = { Le Condé = 孔代咖啡馆 }
current draft.confirmedTermUpdates = { Le Condé = 勒孔代咖啡馆 }
```

Expected:

```text
IllegalStateException contains confirmed_term_conflict
message contains sourceTerm=Le Condé
message contains existing=孔代咖啡馆
message contains incoming=勒孔代咖啡馆
message contains chunkId=<current chunk>
```

- [ ] **Step 2: Replace `putIfAbsent` in `evolveProjectMemory(...)`**

Use `mergeConfirmedTermOrThrow(...)` and include chunk evidence.

- [ ] **Step 3: Confirm identical duplicate does not fail**

Two chunks both confirming `Le Condé=孔代咖啡馆` should pass.

- [ ] **Step 4: Run targeted tests**

```powershell
mvn -q "-Dtest=TranslationApplicationServiceTest" test
```

Expected: PASS.

### Task 3: Preserve final project memory in workflow state and save package from it

**Files:**

- Modify: `src/main/java/io/quillloom/domain/workflow/NovelTranslationWorkflowState.java`
- Modify: `src/main/java/io/quillloom/application/workflow/service/NovelTranslationWorkflowService.java`
- Test: `src/test/java/io/quillloom/application/workflow/service/NovelTranslationWorkflowServiceTest.java`

- [ ] **Step 1: Write failing workflow test**

Test should prove:

```text
D draft returns a confirmed term through chunk output
after draftAllChunks(...), state exposes finalProjectMemory
savePostDraftReviewPackage(...) uses state.finalProjectMemory(), not the initial memory
```

- [ ] **Step 2: Extend `NovelTranslationWorkflowState`**

Add only:

```java
ProjectMemorySnapshot finalProjectMemory
```

Do not add `finalChapterMemory`.

- [ ] **Step 3: Update `advanceToDrafted(...)`**

Add:

```java
advanceToDrafted(List<ChunkTranslationDraft> drafts,
                 ProjectMemorySnapshot finalProjectMemory)
```

Keep old overload only if needed for compatibility.

- [ ] **Step 4: Update `draftAllChunks(...)`**

Call `translateChunksWithMemory(...)` and store `result.finalProjectMemory()` in state.

- [ ] **Step 5: Update `runDraftWorkflow(...)`**

Use:

```java
if (state.finalProjectMemory() == null) {
    throw new IllegalStateException("final_project_memory_missing: projectId=" + state.projectId());
}
savePostDraftReviewPackage(state, state.finalProjectMemory());
```

- [ ] **Step 6: Run targeted workflow tests**

```powershell
mvn -q "-Dtest=NovelTranslationWorkflowServiceTest,TranslationApplicationServiceTest" test
```

Expected: PASS.

### Task 4: Add post-draft package aggregate conflict detection

**Files:**

- Modify: `src/main/java/io/quillloom/application/postdraft/assembler/PostDraftReviewPackageAssembler.java`
- Test: `src/test/java/io/quillloom/application/postdraft/PostDraftContinuationAssemblyTest.java`

- [ ] **Step 1: Write failing conflict test**

Create two `ChunkTranslationDraft` objects:

```text
chunk-1 confirmedTermUpdates={Le Condé=孔代咖啡馆}
chunk-2 confirmedTermUpdates={Le Condé=勒孔代咖啡馆}
```

Expected:

```text
IllegalStateException contains confirmed_term_conflict
```

- [ ] **Step 2: Replace Assembler `putIfAbsent` with explicit merge**

Use a private helper that detects source term conflicts and reports `projectMemory` or `chunkId` evidence.

- [ ] **Step 3: Confirm identical duplicate does not fail**

Two chunks both confirming `Le Condé=孔代咖啡馆` should pass.

- [ ] **Step 4: Run assembler tests**

```powershell
mvn -q "-Dtest=PostDraftContinuationAssemblyTest,PostDraftReviewPackageContractTest" test
```

Expected: PASS.

### Task 5: Audit API projection glossary aggregation

**Files:**

- Modify if needed: `src/main/java/io/quillloom/interfaces/api/dto/WorkflowDraftRunResponse.java`
- Test: existing API / DTO tests if present.

- [ ] **Step 1: Inspect whether `WorkflowDraftRunResponse` aggregates confirmed terms**

If it uses `putIfAbsent`, decide whether the response is an aggregate glossary or raw chunk projection.

- [ ] **Step 2A: If aggregate glossary, add conflict detection**

Use the same `confirmed_term_conflict` semantics.

- [ ] **Step 2B: If raw projection, remove aggregation**

Expose per-chunk `confirmedTermUpdates` rather than a silently merged glossary.

- [ ] **Step 3: Run compile / DTO tests**

```powershell
mvn -q "-Dtest=*Workflow*Response*,*Workflow*Controller*" test
```

If no matching tests exist, run:

```powershell
mvn -q test -DskipITs
```

### Task 6: Fix Review Agent strategy escalation for term conflicts

**Files:**

- Modify prompt builders under `src/main/java/io/quillloom/application/postdraft/review/prompt/`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentEndToEndSmokeTest.java`

- [ ] **Step 1: Add prompt rendering test**

Assert rendered prompt contains:

```text
chunk 译文与项目级 confirmed terms 不一致
KEEP
evaluate_focus
升级
不要重复查询同一术语
```

- [ ] **Step 2: Add scripted e2e for KEEP term conflict**

Script:

```text
read_confirmed_terms once
evaluate_focus returns LIGHT_EDIT
draft_revision succeeds
complete_working_set
```

Assert no second identical `read_confirmed_terms` call is needed.

- [ ] **Step 3: Update prompt text**

Add Chinese instructions from section 3.8.

- [ ] **Step 4: Run review tests**

```powershell
mvn -q "-Dtest=PromptBackedNextStepDecisionProviderTest,PostDraftReviewAgentEndToEndSmokeTest,ReviewToolExecutorGuardrailTest" test
```

Expected: PASS.

### Task 7: Add stable projectId support for full D smoke from `book/`

**Files:**

- Modify: `src/test/java/io/quillloom/BookWorkflowSmokeTest.java`
- Possibly modify: `scripts/` only if adding a helper script.

- [ ] **Step 1: Add property-based projectId**

Use:

```text
quillloom.test.book-workflow.project-id
```

If non-blank, use it directly. Otherwise keep timestamp behavior.

- [ ] **Step 2: Ensure selected book txt comes from `/book`**

Keep existing:

```text
quillloom.test.book-workflow.file
```

- [ ] **Step 3: Run compile-only targeted test disabled path**

```powershell
mvn -q "-Dtest=BookWorkflowSmokeTest" test
```

Expected: test skipped unless explicit enable flag is set.

## 6. 全链路重新运行方案

### 6.1 重新生成 D 初稿

推荐使用新的固定 projectId，避免污染已知 baseline：

```text
book-smoke-127-rerun-20260419
```

命令：

```powershell
mvn -q "-Dtest=BookWorkflowSmokeTest" `
  "-Dquillloom.test.book-workflow.enabled=true" `
  "-Dspring.profiles.active=dev" `
  "-Dquillloom.test.book-workflow.file=1.txt" `
  "-Dquillloom.test.book-workflow.project-id=book-smoke-127-rerun-20260419" `
  test
```

预期：

- 从 `book/1.txt` 重新跑 C0/D 或当前 smoke 所覆盖的完整 book workflow。
- 生成新的 `PostDraftReviewPackage`。
- package 的 `termState.effectiveConfirmedTerms` 来自 D 最终 `ProjectMemorySnapshot`，不是初始空 memory。
- 如果 D 期间发生术语冲突，应在对应 chunk 当场失败，错误包含 `confirmed_term_conflict`。

### 6.2 创建 Review Agent baseline

```powershell
.\scripts\review-create-baseline.ps1 -ProjectId book-smoke-127-rerun-20260419
```

预期：

```text
target/review-agent-baselines/book-smoke-127-rerun-20260419.json
```

### 6.3 启动 127 chunk Review Agent 冒烟

```powershell
.\scripts\review-start.ps1 -ProjectId book-smoke-127-rerun-20260419
```

如进入人工复核：

```powershell
.\scripts\review-resume.ps1 -ProjectId book-smoke-127-rerun-20260419 -HumanReviewNote "这里写人工回答"
```

如跑挂并修完 bug：

```powershell
.\scripts\review-reset-from-baseline.ps1 -ProjectId book-smoke-127-rerun-20260419
.\scripts\review-start.ps1 -ProjectId book-smoke-127-rerun-20260419
```

## 7. 验证矩阵

### 7.1 Unit / slice tests

```powershell
mvn -q "-Dtest=TranslationApplicationServiceTest" test
mvn -q "-Dtest=NovelTranslationWorkflowServiceTest" test
mvn -q "-Dtest=PostDraftContinuationAssemblyTest,PostDraftReviewPackageContractTest" test
mvn -q "-Dtest=PromptBackedNextStepDecisionProviderTest,PostDraftReviewAgentEndToEndSmokeTest,ReviewToolExecutorGuardrailTest" test
```

### 7.2 Compile safety

```powershell
mvn -q test -DskipITs
```

### 7.3 Full smoke

```powershell
mvn -q "-Dtest=BookWorkflowSmokeTest" `
  "-Dquillloom.test.book-workflow.enabled=true" `
  "-Dspring.profiles.active=dev" `
  "-Dquillloom.test.book-workflow.file=1.txt" `
  "-Dquillloom.test.book-workflow.project-id=book-smoke-127-rerun-20260419" `
  test
```

Then:

```powershell
.\scripts\review-create-baseline.ps1 -ProjectId book-smoke-127-rerun-20260419
.\scripts\review-start.ps1 -ProjectId book-smoke-127-rerun-20260419
```

## 8. 红线自检

- R-06：`finalProjectMemory` 是 D 阶段稳定产物，不是 Review Agent loop 临时状态；不写回 `TranslationTaskInput`。
- R-09：HITL 仍是求助式；本计划不改变人工输入模型。
- R-10：NO_PROGRESS 仍是 FAILED；不转 HITL。
- R-11：不新增工具，不往 `ReviewToolExecutor` 加 switch。
- R-12：不做结构化压缩摘要。
- R-13：不联网。
- R-14：人工回答只进 transcript；不进入 `TranslationTaskInput`。

## 9. 后置项

本轮不做：

- 全局译名替换工具。
- 自动从正文抽取所有未登记术语。
- 将 `finalChapterMemory` 作为 workflow state 稳定产物。
- D-12 checkpoint 恢复。
- D-13 流式输出接口。
- D-14 受控联网搜索。
