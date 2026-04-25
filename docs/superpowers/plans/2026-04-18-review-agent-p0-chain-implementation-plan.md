# Review Agent P0 Chain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通 Review Agent 的 Java 调用链，使其在 `complete_working_set` 后写回修订译文，在 `WAITING_HUMAN` 时完整落盘 session，并支持后续从本地 JSON 恢复继续运行。

**Architecture:** 在 `AutonomousProjectReviewAgent` 的 loop 边界新增 `ProjectReviewRuntimePersistenceHook`，把 chunk 写库、`WAITING_HUMAN` 落盘、项目完成清理 session 三类副作用从 `ReviewToolExecutor` 中隔离出去。`HumanInTheLoopGateway` 保持“求助请求发布口”角色，恢复入口收敛到 `PostDraftReviewAgentService.resumeProject(...)`，人工回答只作为证据写回 transcript/history，由 agent 自主决定下一步。

**Tech Stack:** Java, Jackson, Maven, JUnit 5

---

### Task 1: 先锁定失败测试

**Files:**
- Modify: `src/test/java/io/quillloom/application/postdraft/review/FileReviewSessionStoreTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`
- Create: `src/test/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriterTest.java`

- [ ] **Step 1: 为完整 session 持久化写失败测试**

```java
@Test
void shouldPersistAndLoadFullRuntimeSession() {
    ProjectReviewRuntimeSession runtime = ReviewAgentFixtures.waitingHumanRuntime("project-1");

    store.save(runtime);

    StoredReviewSession stored = store.load("project-1").orElseThrow();
    assertEquals("project-1", stored.projectId());
    assertEquals(ProjectReviewStatus.WAITING_HUMAN, stored.runtime().status());
    assertTrue(stored.runtime().currentFocusSession().isPresent());
    assertTrue(stored.runtime().humanReviewRequest().isPresent());
    assertEquals(1, stored.runtime().completedChunkOutcomes().size());
}
```

- [ ] **Step 2: 运行 session store 测试，确认当前失败**

Run: `mvn -q "-Dtest=FileReviewSessionStoreTest" test`
Expected: FAIL，原因是 `StoredReviewSession` 仍是精简快照，无法恢复完整 runtime

- [ ] **Step 3: 为 writer 写失败测试**

```java
@Test
void shouldWriteCompletedChunkTranslationsBackToReviewPackage() {
    PostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
    repository.save(samplePackage("project-1", "old-1", "old-2"));
    PostgresPostDraftReviewAgentWriter writer = new PostgresPostDraftReviewAgentWriter(repository);

    writer.writeCompletedChunks("project-1", List.of(
            completedOutcome("chunk-1", "new-1"),
            completedOutcome("chunk-2", "new-2")
    ));

    PostDraftReviewPackage updated = repository.load("project-1").orElseThrow();
    assertEquals("new-1", chunkById(updated, "chunk-1").translatedText());
    assertEquals("new-2", chunkById(updated, "chunk-2").translatedText());
}
```

- [ ] **Step 4: 运行 writer 测试，确认当前失败**

Run: `mvn -q "-Dtest=PostgresPostDraftReviewAgentWriterTest" test`
Expected: FAIL，原因是 writer 类尚不存在

- [ ] **Step 5: 为 resume 入口写失败测试**

```java
@Test
void shouldResumeProjectFromStoredWaitingHumanSession() {
    ReviewSessionStore store = new FileReviewSessionStore(tempDir);
    store.save(waitingHumanRuntime("project-1"));

    PostDraftReviewAgentResult result = service.resumeProject("project-1", "Louki 统一译为露姬");

    assertTrue(result.completedChunkResults().size() > 0 || result.humanReviewRequest().isPresent());
}
```

- [ ] **Step 6: 运行 service 测试，确认当前失败**

Run: `mvn -q "-Dtest=PostDraftReviewAgentServiceTest" test`
Expected: FAIL，原因是 `resumeProject(...)` 尚不存在

### Task 2: 完整 runtime session 持久化

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/StoredReviewSession.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/port/out/ReviewSessionStore.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/FileReviewSessionStore.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/FileReviewSessionStoreTest.java`

- [ ] **Step 1: 将 `StoredReviewSession` 改为完整 runtime 包装**

```java
public record StoredReviewSession(
        String projectId,
        ProjectReviewRuntimeSession runtime
) {

    public StoredReviewSession {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public static StoredReviewSession from(ProjectReviewRuntimeSession runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return new StoredReviewSession(runtime.projectId(), runtime);
    }
}
```

- [ ] **Step 2: 给 `ReviewSessionStore` 增加 `delete(projectId)`**

```java
void delete(String projectId);
```

- [ ] **Step 3: 修改 `FileReviewSessionStore` 读写完整 runtime，并支持删除**

```java
@Override
public void save(ProjectReviewRuntimeSession runtime) {
    Path target = rootDirectory.resolve(runtime.projectId() + ".json");
    Files.writeString(target, objectMapper.writeValueAsString(StoredReviewSession.from(runtime)));
}

@Override
public Optional<StoredReviewSession> load(String projectId) {
    Path target = rootDirectory.resolve(projectId + ".json");
    if (!Files.exists(target)) {
        return Optional.empty();
    }
    return Optional.of(objectMapper.readValue(Files.readString(target), StoredReviewSession.class));
}

@Override
public void delete(String projectId) {
    Path target = rootDirectory.resolve(projectId + ".json");
    Files.deleteIfExists(target);
}
```

- [ ] **Step 4: 运行 session store 测试，确认转绿**

Run: `mvn -q "-Dtest=FileReviewSessionStoreTest" test`
Expected: PASS

### Task 3: 实现 writer 与 runtime persistence hook

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/ProjectReviewRuntimePersistenceHook.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/DefaultProjectReviewRuntimePersistenceHook.java`
- Create: `src/main/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriter.java`
- Modify: `src/test/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriterTest.java`

- [ ] **Step 1: 定义 persistence hook 接口**

```java
public interface ProjectReviewRuntimePersistenceHook {

    static ProjectReviewRuntimePersistenceHook noop() {
        return (previousRuntime, currentRuntime) -> {
        };
    }

    void afterTransition(ProjectReviewRuntimeSession previousRuntime,
                         ProjectReviewRuntimeSession currentRuntime);
}
```

- [ ] **Step 2: 实现 PostgreSQL writer**

```java
public final class PostgresPostDraftReviewAgentWriter {

    private final PostDraftReviewPackageRepository reviewPackageRepository;

    public void writeCompletedChunks(String projectId,
                                     List<ProjectChunkReviewOutcome> outcomes) {
        // 当前依赖“单 agent / 单 projectId 串行运行”前提，因此这里采用 read-modify-write。
    }

    public void writeMergedDraftText(String projectId,
                                     String mergedDraftText) {
        // 当前依赖“单 agent / 单 projectId 串行运行”前提，因此这里采用 read-modify-write。
    }
}
```

- [ ] **Step 3: 实现默认 hook**

```java
public final class DefaultProjectReviewRuntimePersistenceHook implements ProjectReviewRuntimePersistenceHook {

    @Override
    public void afterTransition(ProjectReviewRuntimeSession previousRuntime,
                                ProjectReviewRuntimeSession currentRuntime) {
        List<ProjectChunkReviewOutcome> newOutcomes = findNewOutcomes(previousRuntime, currentRuntime);
        if (!newOutcomes.isEmpty()) {
            writer.writeCompletedChunks(currentRuntime.projectId(), newOutcomes);
        }
        if (currentRuntime.status() == ProjectReviewStatus.WAITING_HUMAN) {
            reviewSessionStore.save(currentRuntime);
        }
        if (currentRuntime.status() == ProjectReviewStatus.COMPLETED) {
            writer.writeMergedDraftText(currentRuntime.projectId(), assembleMergedDraft(currentRuntime));
            reviewSessionStore.delete(currentRuntime.projectId());
        }
    }
}
```

- [ ] **Step 4: 运行 writer 测试，确认转绿**

Run: `mvn -q "-Dtest=PostgresPostDraftReviewAgentWriterTest" test`
Expected: PASS

### Task 4: 将 hook 接入 agent loop，并补恢复入口

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/port/out/HumanInTheLoopGateway.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/InMemoryHumanInTheLoopGateway.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`

- [ ] **Step 1: 给 `AutonomousProjectReviewAgent` 注入 hook，并在每轮状态跃迁后调用**

```java
ProjectReviewRuntimeSession previous = current;
current = execution.nextRuntime();
persistenceHook.afterTransition(previous, current);
current = compactFocusTranscriptIfNeeded(current);
```

- [ ] **Step 2: 在 service 中新增 `resumeProject(...)`**

```java
public PostDraftReviewAgentResult resumeProject(String projectId, String humanReviewNote) {
    StoredReviewSession stored = reviewSessionStore.load(projectId)
            .orElseThrow(() -> new IllegalStateException("Stored review session not found for projectId=" + projectId));
    ProjectReviewRuntimeSession resumedRuntime = autonomousAgent.resume(stored.runtime(), humanReviewNote);
    if (resumedRuntime.status() != ProjectReviewStatus.WAITING_HUMAN) {
        reviewSessionStore.delete(projectId);
    }
    return projectOutputAssembler.assemble(resumedRuntime);
}
```

- [ ] **Step 3: 保持 `HumanInTheLoopGateway` 只承担“求助请求发布口”角色**

```java
public interface HumanInTheLoopGateway {
    HumanReviewRequest submit(HumanReviewRequest request);
}
```

- [ ] **Step 4: 运行 service 测试，确认恢复路径转绿**

Run: `mvn -q "-Dtest=PostDraftReviewAgentServiceTest" test`
Expected: PASS

### Task 5: 同步方向锚定 / 差距分析 / handoff 文档

**Files:**
- Modify: `docs/superpowers/plans/2026-04-18-review-agent-direction-anchor.md`
- Modify: `docs/superpowers/plans/2026-04-18-review-agent-e2e-run-gap-analysis.md`
- Modify: `docs/handoff.md`

- [ ] **Step 1: 修正方向锚定文档中的 D-07 与 R-10**

```md
- `HumanInTheLoopGateway` 只负责提交人工求助请求，不负责等待人工回答或恢复运行。
- 恢复入口改为 `PostDraftReviewAgentService.resumeProject(...)`。
- R-10：不得把 `NO_PROGRESS` 伪装成正常 HITL 暂停或可恢复人工求助路径。
```

- [ ] **Step 2: 在差距分析文档中写清 persistence hook 和 `WAITING_HUMAN` 唯一落盘点**

```md
- 新增 `ProjectReviewRuntimePersistenceHook` 作为运行时副作用边界
- 只有 `WAITING_HUMAN` 是允许完整落盘的正常暂停点
- `NO_PROGRESS` / 网络错误 / LLM 输出异常不落盘
```

- [ ] **Step 3: 在 handoff 中记录新边界**

```md
1. `HumanInTheLoopGateway` 现在是“求助请求发布口”，恢复入口在 service。
2. `ProjectReviewRuntimePersistenceHook` 负责 chunk 写库、WAITING_HUMAN 落盘、完成后清理 session。
3. `NO_PROGRESS` 视为 bug 暴露，不进入 HITL，不恢复。
```

### Task 6: 回归验证

**Files:**
- Test: `src/test/java/io/quillloom/application/postdraft/review/FileReviewSessionStoreTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriterTest.java`

- [ ] **Step 1: 跑本轮定向测试**

Run: `mvn -q "-Dtest=FileReviewSessionStoreTest,PostDraftReviewAgentServiceTest,PostgresPostDraftReviewAgentWriterTest" test`
Expected: PASS

- [ ] **Step 2: 跑 review agent 核心回归测试**

Run: `mvn -q "-Dtest=AutonomousProjectReviewAgentTest,ReviewToolExecutorGuardrailTest,PostDraftReviewAgentServiceTest,FileReviewSessionStoreTest" test`
Expected: PASS

- [ ] **Step 3: 记录实际验证结果，不做未验证成功声明**

```text
记录实际执行的命令、退出码、通过/失败测试数，以及是否存在未覆盖风险。
```
