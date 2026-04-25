# Project-Level Unified Review Agent Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 `review agent` 基础上，实现“仅以 `projectId` 启动、单一统一 loop 连续处理全项目 chunk、每 chunk 完成后强制记忆压缩、遇到 `WAITING_HUMAN` 按项目级语义暂停”的第一阶段最小闭环。

**Architecture:** 以现有 `PostDraftReviewAgentService + PostDraftReviewLoopRunner + PostDraftReviewAgentReader` 为骨架，升级为项目级运行期会话驱动。统一 loop 在一个状态机内完成焦点选择、取证、评估、修订/重译、记忆压缩、项目收口，不新增第二套控制层，不新增第二个 agent。运行期状态只存在 application 层 session，不回写稳定领域对象。

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, PostgreSQL test fixture, existing postdraft/knowledge repositories

---

## Scope Guardrails

1. 不新增新 agent；只演进现有 review agent。
2. 不引入两层运行结构；只保留一个统一 loop。
3. 不回退大 orchestrator，不重做 A/B/C0。
4. 不把运行期临时状态塞回 `PostDraftReviewPackage`、`ProjectKnowledgeBase`、`TranslationTaskInput`。
5. 不破坏 `confirmed / candidate / alias / knowledge card` 边界。
6. D 仍不联网；本计划不接外部搜索能力。

## File Map

### Create

- `src/main/java/io/quillloom/application/postdraft/review/command/StartProjectPostDraftReviewAgentCommand.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ProjectChunkReviewOutcome.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ProjectRuntimeStopReason.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/CompletedChunkMemorySummary.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ProjectRollingMemory.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/FocusWorkingMemory.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ProjectFocusSelector.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/SequenceProjectFocusSelector.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ProjectMemoryCompressor.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ProjectReviewOutputAssembler.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftProjectRuntimeSessionModelTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ProjectFocusSelectorTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ProjectMemoryCompressorTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftProjectUnifiedLoopRunnerTest.java`
- `src/test/java/io/quillloom/PostDraftProjectReviewAgentSmokeTest.java`

### Modify

- `src/main/java/io/quillloom/application/postdraft/review/command/StartPostDraftReviewAgentCommand.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewSession.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentState.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentActionType.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewAgentResult.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/HumanReviewRequest.java`
- `src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentReader.java`
- `src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewLoopRunner.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewProcessSummaryAssembler.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAllowedActionPlanner.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewActionExecutor.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewLoopRunnerTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAllowedActionPlannerTest.java`
- `src/test/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReaderTest.java`
- `src/test/java/io/quillloom/PostDraftReviewAgentSmokeTest.java`
- `src/test/java/io/quillloom/support/PostDraftReviewSmokeSupport.java`
- `docs/handoff.md`
- `docs/current-status.md`

---

### Task 1: 建立项目级启动命令与运行期会话模型

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/command/StartProjectPostDraftReviewAgentCommand.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectChunkReviewOutcome.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectRuntimeStopReason.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftProjectRuntimeSessionModelTest.java`

- [ ] **Step 1: 写失败测试，约束正式入口只需要 `projectId`，并初始化待处理 chunk 队列**

```java
@Test
void shouldCreateProjectRuntimeSessionFromProjectIdOnly() {
    StartProjectPostDraftReviewAgentCommand command =
            new StartProjectPostDraftReviewAgentCommand("project-1", "operator note");
    ProjectReviewRuntimeSession session = ProjectReviewRuntimeSession.initialize(
            command.projectId(),
            List.of("chunk-1", "chunk-2", "chunk-3")
    );

    assertEquals("project-1", session.projectId());
    assertEquals(List.of("chunk-1", "chunk-2", "chunk-3"), session.pendingChunkIds());
    assertTrue(session.currentFocusChunkId().isEmpty());
    assertEquals(ProjectRuntimeStopReason.NONE, session.stopReason());
}
```

- [ ] **Step 2: 运行测试，确认新命令与会话模型缺失导致失败**

Run: `mvn -q "-Dtest=PostDraftProjectRuntimeSessionModelTest" test`  
Expected: FAIL，提示 `StartProjectPostDraftReviewAgentCommand` / `ProjectReviewRuntimeSession` 不存在

- [ ] **Step 3: 实现最小命令与会话模型（不含业务执行逻辑）**

```java
public record StartProjectPostDraftReviewAgentCommand(
        String projectId,
        String operatorNote
) {
    public StartProjectPostDraftReviewAgentCommand {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        operatorNote = operatorNote == null ? "" : operatorNote.trim();
    }
}
```

```java
public record ProjectReviewRuntimeSession(
        String projectId,
        List<String> pendingChunkIds,
        List<ProjectChunkReviewOutcome> completedChunkOutcomes,
        Optional<String> currentFocusChunkId,
        ProjectRuntimeStopReason stopReason
) {
    public static ProjectReviewRuntimeSession initialize(String projectId, List<String> orderedChunkIds) {
        return new ProjectReviewRuntimeSession(
                projectId,
                List.copyOf(orderedChunkIds),
                List.of(),
                Optional.empty(),
                ProjectRuntimeStopReason.NONE
        );
    }
}
```

- [ ] **Step 4: 回归模型测试**

Run: `mvn -q "-Dtest=PostDraftProjectRuntimeSessionModelTest" test`  
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/command/StartProjectPostDraftReviewAgentCommand.java src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java src/main/java/io/quillloom/application/postdraft/review/model/ProjectChunkReviewOutcome.java src/main/java/io/quillloom/application/postdraft/review/model/ProjectRuntimeStopReason.java src/test/java/io/quillloom/application/postdraft/review/PostDraftProjectRuntimeSessionModelTest.java
git commit -m "feat: add project-level review runtime session model"
```

### Task 2: 扩展 reader，支持按项目枚举 chunk 与顺序导航

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentReader.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java`
- Modify: `src/test/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReaderTest.java`

- [ ] **Step 1: 写失败测试，约束 reader 能返回按 sequence 排序的全项目 chunk 列表**

```java
@Test
void shouldListProjectChunksInSequenceOrder() {
    RepositoryBackedPostDraftReviewAgentReader reader = fixtures.readerWithChunks(
            "project-1",
            List.of(chunk("chunk-3", 3), chunk("chunk-1", 1), chunk("chunk-2", 2))
    );

    List<String> ordered = reader.listChunkIdsByProject("project-1");

    assertEquals(List.of("chunk-1", "chunk-2", "chunk-3"), ordered);
}
```

- [ ] **Step 2: 运行测试，确认接口尚未暴露项目枚举能力**

Run: `mvn -q "-Dtest=RepositoryBackedPostDraftReviewAgentReaderTest" test`  
Expected: FAIL，提示 `listChunkIdsByProject` 不存在

- [ ] **Step 3: 扩展 reader 接口与实现**

```java
public interface PostDraftReviewAgentReader {
    List<String> listChunkIdsByProject(String projectId);
    Optional<PostDraftChunkRecord> loadChunkById(String projectId, String chunkId);
    // existing methods...
}
```

```java
@Override
public List<String> listChunkIdsByProject(String projectId) {
    PostDraftReviewPackage pkg = loadReviewPackage(requireText(projectId, "projectId"));
    return pkg.chunks().stream()
            .sorted(Comparator.comparingInt(PostDraftChunkRecord::sequence))
            .map(PostDraftChunkRecord::chunkId)
            .toList();
}
```

- [ ] **Step 4: 回归 reader 测试**

Run: `mvn -q "-Dtest=RepositoryBackedPostDraftReviewAgentReaderTest" test`  
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentReader.java src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java src/test/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReaderTest.java
git commit -m "feat: add project-level chunk listing to review reader"
```

### Task 3: 定义统一 loop 状态与动作集合（项目级）

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentState.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentActionType.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewLoopRunnerTest.java`

- [ ] **Step 1: 写失败测试，约束存在项目推进与记忆压缩状态**

```java
@Test
void shouldContainProjectLevelStates() {
    assertTrue(EnumSet.allOf(ReviewAgentState.class).contains(ReviewAgentState.SELECTING_FOCUS));
    assertTrue(EnumSet.allOf(ReviewAgentState.class).contains(ReviewAgentState.COMPRESSING_MEMORY));
    assertTrue(EnumSet.allOf(ReviewAgentState.class).contains(ReviewAgentState.FINALIZING));
}
```

- [ ] **Step 2: 运行测试，确认状态枚举尚未覆盖**

Run: `mvn -q "-Dtest=PostDraftReviewLoopRunnerTest" test`  
Expected: FAIL，提示新增状态不存在

- [ ] **Step 3: 扩展状态与动作枚举（不改执行逻辑）**

```java
public enum ReviewAgentState {
    INITIALIZING,
    SELECTING_FOCUS,
    INVESTIGATING,
    EVALUATING,
    REVISING,
    COMPRESSING_MEMORY,
    FINALIZING,
    WAITING_HUMAN,
    COMPLETED,
    FAILED
}
```

```java
public enum ReviewAgentActionType {
    SELECT_NEXT_CHUNK,
    READ_PREVIOUS_CHUNKS,
    READ_NEXT_CHUNKS,
    EXPAND_BLOCK,
    READ_DECISION_NOTES,
    READ_TRANSITION_NOTE,
    LOOKUP_KNOWLEDGE_CARDS,
    ENTER_EVALUATION,
    ENTER_REVISION,
    MARK_CHUNK_COMPLETED,
    COMPRESS_MEMORY,
    FINALIZE_PROJECT,
    REQUEST_HUMAN_REVIEW
}
```

- [ ] **Step 4: 执行回归**

Run: `mvn -q "-Dtest=PostDraftReviewLoopRunnerTest,PostDraftReviewAllowedActionPlannerTest" test`  
Expected: PASS（若失败，按新枚举同步修测试）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentState.java src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentActionType.java src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewLoopRunnerTest.java
git commit -m "refactor: extend review state and action enums for unified project loop"
```

### Task 4: 把 `PostDraftReviewLoopRunner` 升级为项目级统一 loop

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewLoopRunner.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAllowedActionPlanner.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewActionExecutor.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/ProjectFocusSelector.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/SequenceProjectFocusSelector.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftProjectUnifiedLoopRunnerTest.java`

- [ ] **Step 1: 写失败测试，约束同一次运行可连续处理多个 chunk**

```java
@Test
void shouldProcessMultipleChunksInSingleUnifiedLoop() {
    ProjectReviewRuntimeSession session = fixtures.session("project-1", List.of("chunk-1", "chunk-2"));
    PostDraftReviewLoopRunner runner = fixtures.runner();

    ProjectReviewRuntimeSession result = runner.runProject(session);

    assertEquals(2, result.completedChunkOutcomes().size());
    assertTrue(result.pendingChunkIds().isEmpty());
    assertEquals(ProjectRuntimeStopReason.COMPLETED, result.stopReason());
}
```

- [ ] **Step 2: 运行测试，确认当前仍是单焦点语义导致失败**

Run: `mvn -q "-Dtest=PostDraftProjectUnifiedLoopRunnerTest" test`  
Expected: FAIL，提示 `runProject` 或项目级结果不存在

- [ ] **Step 3: 在同一个 loop 中合并“焦点选择+取证评估修订+完成标记”**

```java
while (runtime.state() != ReviewAgentState.COMPLETED && runtime.state() != ReviewAgentState.FAILED) {
    switch (runtime.state()) {
        case INITIALIZING -> runtime = runtime.enterSelectingFocus();
        case SELECTING_FOCUS -> runtime = focusSelector.selectNext(runtime);
        case INVESTIGATING -> runtime = runInvestigatingStep(runtime);
        case EVALUATING -> runtime = runEvaluatingStep(runtime);
        case REVISING -> runtime = runRevisingStep(runtime);
        case COMPRESSING_MEMORY -> runtime = compressMemory(runtime);
        case FINALIZING -> runtime = finalizeProject(runtime);
        case WAITING_HUMAN -> { return runtime; }
        default -> throw new IllegalStateException("Unexpected state: " + runtime.state());
    }
}
```

- [ ] **Step 4: 调整 action planner allowlist，确保动作与状态严格对应**

```java
case SELECTING_FOCUS -> Set.of(ReviewAgentActionType.SELECT_NEXT_CHUNK, ReviewAgentActionType.FINALIZE_PROJECT);
case INVESTIGATING -> Set.of(
        ReviewAgentActionType.READ_PREVIOUS_CHUNKS,
        ReviewAgentActionType.READ_NEXT_CHUNKS,
        ReviewAgentActionType.EXPAND_BLOCK,
        ReviewAgentActionType.READ_DECISION_NOTES,
        ReviewAgentActionType.READ_TRANSITION_NOTE,
        ReviewAgentActionType.LOOKUP_KNOWLEDGE_CARDS,
        ReviewAgentActionType.ENTER_EVALUATION,
        ReviewAgentActionType.REQUEST_HUMAN_REVIEW
);
case COMPRESSING_MEMORY -> Set.of(ReviewAgentActionType.COMPRESS_MEMORY);
```

- [ ] **Step 5: 回归 runner 与 planner 测试**

Run: `mvn -q "-Dtest=PostDraftProjectUnifiedLoopRunnerTest,PostDraftReviewLoopRunnerTest,PostDraftReviewAllowedActionPlannerTest,PostDraftReviewActionExecutorTest" test`  
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewLoopRunner.java src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAllowedActionPlanner.java src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewActionExecutor.java src/main/java/io/quillloom/application/postdraft/review/service/ProjectFocusSelector.java src/main/java/io/quillloom/application/postdraft/review/service/SequenceProjectFocusSelector.java src/test/java/io/quillloom/application/postdraft/review/PostDraftProjectUnifiedLoopRunnerTest.java
git commit -m "feat: upgrade review loop runner to unified project-level loop"
```

### Task 5: 实现记忆压缩三层模型与压缩时机

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/FocusWorkingMemory.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectRollingMemory.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/CompletedChunkMemorySummary.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/ProjectMemoryCompressor.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ProjectMemoryCompressorTest.java`

- [ ] **Step 1: 写失败测试，约束“每个 chunk 完成后必须有一条压缩摘要”**

```java
@Test
void shouldAppendCompressedSummaryAfterChunkCompleted() {
    ProjectReviewRuntimeSession session = fixtures.sessionWithCompletedChunk("project-1", "chunk-1");
    ProjectMemoryCompressor compressor = new ProjectMemoryCompressor();

    ProjectReviewRuntimeSession compressed = compressor.compressAfterChunk(session);

    assertEquals(1, compressed.completedChunkMemorySummaries().size());
    assertTrue(compressed.focusWorkingMemory().evidenceDetails().isEmpty());
}
```

- [ ] **Step 2: 运行测试，确认压缩组件尚未实现**

Run: `mvn -q "-Dtest=ProjectMemoryCompressorTest" test`  
Expected: FAIL，提示 `ProjectMemoryCompressor` / memory model 不存在

- [ ] **Step 3: 实现三层记忆模型与压缩服务**

```java
public final class ProjectMemoryCompressor {
    public ProjectReviewRuntimeSession compressAfterChunk(ProjectReviewRuntimeSession session) {
        CompletedChunkMemorySummary summary = CompletedChunkMemorySummary.from(
                session.currentFocusChunkId().orElseThrow(),
                session.focusWorkingMemory(),
                session.currentStrategy()
        );
        ProjectRollingMemory rolling = ProjectRollingMemory.merge(
                session.projectRollingMemory(),
                summary.highValueSignals()
        );
        return session.afterCompression(summary, rolling, FocusWorkingMemory.empty());
    }
}
```

- [ ] **Step 4: 回归压缩与 loop 测试**

Run: `mvn -q "-Dtest=ProjectMemoryCompressorTest,PostDraftProjectUnifiedLoopRunnerTest" test`  
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/model/FocusWorkingMemory.java src/main/java/io/quillloom/application/postdraft/review/model/ProjectRollingMemory.java src/main/java/io/quillloom/application/postdraft/review/model/CompletedChunkMemorySummary.java src/main/java/io/quillloom/application/postdraft/review/service/ProjectMemoryCompressor.java src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java src/test/java/io/quillloom/application/postdraft/review/ProjectMemoryCompressorTest.java
git commit -m "feat: add three-layer runtime memory compression for project loop"
```

### Task 6: 项目级停机与人工恢复语义

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/HumanReviewRequest.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewLoopRunner.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewLoopRunnerTest.java`

- [ ] **Step 1: 写失败测试，约束进入 `WAITING_HUMAN` 时暂停整个项目而非单 chunk**

```java
@Test
void shouldPauseProjectWhenAnyFocusEntersWaitingHuman() {
    PostDraftReviewAgentResult result = fixtures.runProjectWithHumanEscalation("project-1");

    assertTrue(result.humanReviewRequest().isPresent());
    assertEquals("project_waiting_human", result.humanReviewRequest().orElseThrow().requestReason());
    assertTrue(result.processSummary().processNote().contains("stopReason=waiting_human"));
}
```

- [ ] **Step 2: 运行测试，确认当前请求语义仍偏单焦点**

Run: `mvn -q "-Dtest=PostDraftReviewAgentServiceTest,PostDraftReviewLoopRunnerTest" test`  
Expected: FAIL，字段或状态断言不匹配

- [ ] **Step 3: 扩展人工请求字段并挂接项目恢复点**

```java
public record HumanReviewRequest(
        String projectId,
        ReviewFocus focus,
        ReviewProcessSummary processSummary,
        String requestNote,
        String requestReason,
        ReviewAgentState waitingState,
        String resumeHint,
        int completedChunkCount,
        int pendingChunkCount
) { ... }
```

- [ ] **Step 4: 回归人审暂停/恢复测试**

Run: `mvn -q "-Dtest=PostDraftReviewAgentServiceTest,PostDraftReviewLoopRunnerTest" test`  
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/model/HumanReviewRequest.java src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewLoopRunner.java src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewLoopRunnerTest.java
git commit -m "feat: support project-level waiting-human stop and resume metadata"
```

### Task 7: 项目级输出装配（正式译文 + 过程摘要 + 人工信息）

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/ProjectReviewOutputAssembler.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewAgentResult.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewProcessSummaryAssembler.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`

- [ ] **Step 1: 写失败测试，约束结果包含项目级聚合输出**

```java
@Test
void shouldAssembleProjectLevelFinalOutput() {
    PostDraftReviewAgentResult result = fixtures.runCompletedProject("project-1");

    assertEquals(3, result.completedChunkResults().size());
    assertFalse(result.finalMergedTranslatedText().isBlank());
    assertTrue(result.processSummary().processNote().contains("completedChunkCount=3"));
}
```

- [ ] **Step 2: 运行测试，确认结果模型尚无项目聚合字段**

Run: `mvn -q "-Dtest=PostDraftReviewAgentServiceTest" test`  
Expected: FAIL，`completedChunkResults` / `finalMergedTranslatedText` 不存在

- [ ] **Step 3: 实现项目级输出组装器并接入 service**

```java
public final class ProjectReviewOutputAssembler {
    public PostDraftReviewAgentResult assemble(ProjectReviewRuntimeSession runtime) {
        String mergedTranslation = runtime.completedChunkOutcomes().stream()
                .map(ProjectChunkReviewOutcome::finalTranslation)
                .collect(Collectors.joining("\n\n"));
        return PostDraftReviewAgentResult.forProject(
                runtime.projectId(),
                mergedTranslation,
                runtime.completedChunkOutcomes(),
                runtime.projectProcessSummary(),
                runtime.humanReviewRequest()
        );
    }
}
```

- [ ] **Step 4: 回归结果装配测试**

Run: `mvn -q "-Dtest=PostDraftReviewAgentServiceTest,PostDraftReviewProcessSummaryAssemblerTest" test`  
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/service/ProjectReviewOutputAssembler.java src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewAgentResult.java src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewProcessSummaryAssembler.java src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java
git commit -m "feat: assemble project-level review outputs and summary"
```

### Task 8: 项目级 smoke test（入口仅 `projectId`）

**Files:**
- Create: `src/test/java/io/quillloom/PostDraftProjectReviewAgentSmokeTest.java`
- Modify: `src/test/java/io/quillloom/PostDraftReviewAgentSmokeTest.java`
- Modify: `src/test/java/io/quillloom/support/PostDraftReviewSmokeSupport.java`

- [ ] **Step 1: 写失败测试，约束 smoke 入口只必需 `projectId`**

```java
@Test
void shouldRunProjectSmokeWithProjectIdOnly() throws Exception {
    Assumptions.assumeTrue(Boolean.getBoolean("quillloom.test.post-draft-project-review-smoke.enabled"));
    PostDraftReviewAgentResult result = runSmoke("book-smoke-1");
    assertFalse(result.finalMergedTranslatedText().isBlank());
}
```

- [ ] **Step 2: 运行测试，确认缺少项目级 smoke 入口**

Run: `mvn -q "-Dtest=PostDraftProjectReviewAgentSmokeTest" test`  
Expected: FAIL，测试类或方法缺失

- [ ] **Step 3: 新增项目级 smoke 与输出报告字段**

```java
private static final String PROJECT_ID_PROPERTY = "quillloom.test.post-draft-project-review-smoke.project-id";
// no chunk-id required in project-mode smoke
```

```java
builder.append("completedChunkCount=").append(result.completedChunkResults().size()).append('\n');
builder.append("stopReason=").append(result.processSummary().processNote()).append('\n');
```

- [ ] **Step 4: 执行 smoke（受控开关）**

Run: `mvn -q "-Dtest=PostDraftProjectReviewAgentSmokeTest" "-Dquillloom.test.post-draft-project-review-smoke.enabled=true" "-Dquillloom.test.post-draft-project-review-smoke.project-id=<projectId>" test`  
Expected: PASS，`run-output/postdraft-review-smoke/` 下生成项目级报告

- [ ] **Step 5: 提交**

```bash
git add src/test/java/io/quillloom/PostDraftProjectReviewAgentSmokeTest.java src/test/java/io/quillloom/PostDraftReviewAgentSmokeTest.java src/test/java/io/quillloom/support/PostDraftReviewSmokeSupport.java
git commit -m "test: add project-level post-draft review smoke entry"
```

### Task 9: 文档同步与总回归验证

**Files:**
- Modify: `docs/handoff.md`
- Modify: `docs/current-status.md`
- Modify: `docs/current-architecture.md`（如字段或语义已变化）

- [ ] **Step 1: 更新交接文档中的正式入口与统一 loop 口径**

```md
## 2026-04-15 项目级统一 loop 已落地（第一阶段）
1. 正式入口仅收 `projectId`（`chunkId` 仅调试）
2. 单一统一 loop：焦点选择、取证、评估、修订/重译、记忆压缩、项目收口
3. 每完成一个 chunk 后执行记忆压缩
4. `WAITING_HUMAN` 为项目级暂停点
```

- [ ] **Step 2: 运行聚合回归**

Run: `mvn -q "-Dtest=PostDraftProjectRuntimeSessionModelTest,ProjectFocusSelectorTest,ProjectMemoryCompressorTest,RepositoryBackedPostDraftReviewAgentReaderTest,PostDraftReviewAllowedActionPlannerTest,PostDraftReviewActionExecutorTest,PostDraftReviewLoopRunnerTest,PostDraftProjectUnifiedLoopRunnerTest,PostDraftReviewAgentServiceTest,PostDraftRetranslationServiceTest" test`  
Expected: PASS

- [ ] **Step 3: 可选执行项目级 smoke（手动开关）**

Run: `mvn -q "-Dtest=PostDraftProjectReviewAgentSmokeTest" "-Dquillloom.test.post-draft-project-review-smoke.enabled=true" "-Dquillloom.test.post-draft-project-review-smoke.project-id=<projectId>" test`  
Expected: PASS，并生成可读输出

- [ ] **Step 4: 提交**

```bash
git add docs/handoff.md docs/current-status.md docs/current-architecture.md
git commit -m "docs: sync project-level unified review loop design and status"
```

---

## Spec Coverage Check

1. 单一项目级自治 agent：通过项目级命令、项目级 session、项目级输出落实。
2. 单一统一 loop：通过 `PostDraftReviewLoopRunner` 一套状态机统一推进。
3. 正式入口 `projectId`：通过 `StartProjectPostDraftReviewAgentCommand` 与项目级 smoke 落实。
4. 记忆压缩一等能力：通过 `ProjectMemoryCompressor` 与 `COMPRESSING_MEMORY` 状态落实。
5. 人工停机策略：`WAITING_HUMAN` 项目级暂停与恢复语义落实。
6. 输出物：正式译文、过程摘要、人工信息都由项目级 assembler 统一产出。

## Placeholder Scan

1. 无 `TODO/TBD` 占位项。
2. 每个任务都给出了目标文件、验证命令与预期结果。
3. 每个代码任务均有最小代码片段，避免“只描述不落地”。

## Type/Contract Consistency Check

1. 保持 `PostDraftReviewPackage` / `ProjectKnowledgeBase` 只读恢复源语义，不新增运行态字段。
2. `HumanReviewRequest` 的增强字段仅属于 application 层，不回写 domain 稳定对象。
3. `ReviewAgentState` 与 `ReviewAgentActionType` 扩展后，planner/runner/executor 测试需同步更新。

