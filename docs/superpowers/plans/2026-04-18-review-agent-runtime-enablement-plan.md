# Review Agent Runtime Enablement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Review Agent 从“Java 内核链路可跑”推进到“应用可启动、可同步验证、可异步调用、可在 130+ chunk 项目上稳定长跑”。

**Architecture:** 先修正当前 `ProjectReviewRuntimePersistenceHook -> infrastructure writer` 的依赖方向，再补齐 Spring Bean 装配，让 Review Agent 以正式 Bean 链启动。运行入口按“两阶段”推进：先提供同步 `CommandLineRunner` 打通 start/resume smoke path，再提供异步 REST API + 状态查询；LLM 限流/超时重试保持在客户端层，不进入 agent loop。

**Tech Stack:** Java, Spring Boot, LangChain4j, Jackson, Maven, JUnit 5

---

## 文件结构

### 当前必须修改的文件

- `src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentWriter.java`
  - Review Agent 稳定产物写出 port。需要扩展为“写 completed chunks / 写 merged draft”的正式边界，并明确旧单 chunk 路径与新项目级路径的职责区别。
- `src/main/java/io/quillloom/application/postdraft/review/service/DefaultProjectReviewRuntimePersistenceHook.java`
  - 运行时副作用 hook。需要改为只依赖 application port，而不是直接依赖 infrastructure 实现。
- `src/main/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriter.java`
  - repository-backed writer 实现。保留 read-modify-write，但要明确当前仅支持单 agent / 单 projectId 串行执行。
- `src/main/java/io/quillloom/infrastructure/postdraft/review/PassThroughPostDraftReviewAgentWriter.java`
  - 兼容旧接口实现。需要随 port 扩展同步补齐新方法，避免测试或旧路径断裂。
- `src/main/java/io/quillloom/infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfiguration.java`
  - Review Agent 正式 Spring 装配入口。当前只有 LLM port Bean，后续需要补齐完整链，并核实上游依赖 Bean 是否已存在。

### 当前需要接入的既有实现

- `src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java`
  - 4 参数构造器：`PostDraftReviewPackageRepository + ProjectKnowledgeBaseRepository + PostDraftContinuationContextAssembler + KnowledgeRetrievalService`
- `src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentTermWriter.java`
  - 3 参数构造器：`PostDraftReviewPackageRepository + PostDraftReviewPackageAssembler + RepositoryBackedPostDraftReviewAgentReader`
- `src/main/java/io/quillloom/infrastructure/postdraft/review/FileReviewSessionStore.java`
  - 本地 session JSON 落盘/恢复实现
- `src/main/java/io/quillloom/infrastructure/postdraft/review/InMemoryHumanInTheLoopGateway.java`
  - 现阶段可作为最小“求助请求发布口”实现
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`
  - 已有 `reviewProject(...)` 和 `resumeProject(...)`，后续入口应直接复用
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewRuntimeVisualizer.java`
  - 运行观测边界，CLI / API 都要复用它提供的状态事件

### 计划中新建的文件

- `src/main/java/io/quillloom/infrastructure/postdraft/review/ReviewAgentRuntimeProperties.java`
  - Review Agent 运行入口配置，至少包含 session 目录、runner 模式、CLI 启动参数
- `src/main/java/io/quillloom/interfaces/runner/PostDraftReviewAgentCommandLineRunner.java`
  - 第一阶段同步 smoke 入口
- `src/main/java/io/quillloom/interfaces/api/PostDraftReviewAgentController.java`
  - 第二阶段异步启动/恢复/状态查询入口
- `src/main/java/io/quillloom/interfaces/api/dto/PostDraftReviewProjectStartRequest.java`
- `src/main/java/io/quillloom/interfaces/api/dto/PostDraftReviewProjectResumeRequest.java`
- `src/main/java/io/quillloom/interfaces/api/dto/PostDraftReviewProjectStatusResponse.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentStatusService.java`
  - 面向 CLI / REST 的统一状态投影查询服务
- `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewProjectStatusView.java`
  - 应用层统一状态投影模型，CLI 与 REST 共用，避免把 HTTP DTO 渗入 application 层
- `src/main/java/io/quillloom/application/postdraft/review/model/ExternalReviewProjectStatus.java`
  - 对外状态分类枚举，用于区分 `WAITING_HUMAN`、`FAILED_BUG`、`FAILED_INFRA_RETRYABLE`
- `src/test/java/io/quillloom/infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfigurationTest.java`
  - Bean 链装配测试
- `src/test/java/io/quillloom/interfaces/runner/PostDraftReviewAgentCommandLineRunnerTest.java`
  - CLI 入口测试
- `src/test/java/io/quillloom/interfaces/api/PostDraftReviewAgentControllerTest.java`
  - API 入口测试
- `src/test/java/io/quillloom/application/postdraft/review/support/ScriptedReviewAgentGenerationPort.java`
  - 若现有 `SequenceGenerationPort` 无法精确控制 review agent 的多类结构化生成调用，则新增该测试桩

### 预计需要同步更新的文档

- `docs/superpowers/plans/2026-04-18-review-agent-direction-anchor.md`
- `docs/superpowers/plans/2026-04-18-review-agent-e2e-run-gap-analysis.md`
- `docs/handoff.md`

---

### Task 1: 修正当前写出边界，避免 Bean 装配固化错误依赖

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentWriter.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/DefaultProjectReviewRuntimePersistenceHook.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriter.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/PassThroughPostDraftReviewAgentWriter.java`
- Modify: `src/test/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriterTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`

- [ ] **Step 1: 扩展 `PostDraftReviewAgentWriter` 为正式稳定产物写出 port**

```java
public interface PostDraftReviewAgentWriter {

    PostDraftReviewAgentResult writeCompleted(String finalTranslatedText, ReviewProcessSummary processSummary);

    PostDraftReviewAgentResult writeHumanRequired(HumanReviewRequest request);

    default void writeCompletedChunks(String projectId, List<ProjectChunkReviewOutcome> outcomes) {
        throw new UnsupportedOperationException("This writer does not support project-level completed chunk writeback");
    }

    default void writeMergedDraftText(String projectId, String mergedDraftText) {
        throw new UnsupportedOperationException("This writer does not support project-level merged draft writeback");
    }
}
```

说明：
- `writeCompleted(...)` / `writeHumanRequired(...)` 保留为旧单 chunk / 兼容路径写出接口
- `writeCompletedChunks(...)` / `writeMergedDraftText(...)` 作为项目级 autonomous review 路径的正式写出接口
- default 方法不能是空实现，避免误接错 writer 时静默吞掉写库动作

- [ ] **Step 2: 把 `DefaultProjectReviewRuntimePersistenceHook` 改为只依赖 application port**

```java
public final class DefaultProjectReviewRuntimePersistenceHook implements ProjectReviewRuntimePersistenceHook {

    private final PostDraftReviewAgentWriter writer;
    private final ReviewSessionStore reviewSessionStore;

    public DefaultProjectReviewRuntimePersistenceHook(PostDraftReviewAgentWriter writer,
                                                      ReviewSessionStore reviewSessionStore) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.reviewSessionStore = Objects.requireNonNull(reviewSessionStore, "reviewSessionStore");
    }
}
```

- [ ] **Step 3: 让 `PostgresPostDraftReviewAgentWriter` 实现扩展后的 port，并保留串行前提注释**

```java
@Override
public void writeCompletedChunks(String projectId, List<ProjectChunkReviewOutcome> outcomes) {
    // 当前依赖“单 agent / 单 projectId 串行执行”前提，因此这里采用 read-modify-write。
}
```

- [ ] **Step 4: 让 `PassThroughPostDraftReviewAgentWriter` 实现新方法的 no-op / 透传兼容**

```java
@Override
public void writeCompletedChunks(String projectId, List<ProjectChunkReviewOutcome> outcomes) {
    // 仅用于旧单 chunk / 兼容路径，项目级 autonomous review 不应注入该实现。
}
```

- [ ] **Step 5: 运行定向测试，确认依赖边界修正后行为不回退**

Run: `mvn -q "-Dtest=PostgresPostDraftReviewAgentWriterTest,PostDraftReviewAgentServiceTest" test`
Expected: PASS，且 `DefaultProjectReviewRuntimePersistenceHook` 不再直接依赖 infrastructure 实现

---

### Task 2: 补齐 Review Agent 的 Spring Bean 链

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfiguration.java`
- Create: `src/main/java/io/quillloom/infrastructure/postdraft/review/ReviewAgentRuntimeProperties.java`
- Create: `src/test/java/io/quillloom/infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfigurationTest.java`

- [ ] **Step 1: 先核实上游依赖 Bean 是否已在主上下文存在，缺失时一并注册**

```java
@Bean
public PostDraftReviewAgentReader postDraftReviewAgentReader(
        PostDraftReviewPackageRepository reviewPackageRepository,
        ProjectKnowledgeBaseRepository knowledgeBaseRepository,
        PostDraftContinuationContextAssembler continuationContextAssembler,
        KnowledgeRetrievalService knowledgeRetrievalService) {
    return new RepositoryBackedPostDraftReviewAgentReader(
            reviewPackageRepository,
            knowledgeBaseRepository,
            continuationContextAssembler,
            knowledgeRetrievalService
    );
}
```

核实范围：
- `PostDraftContinuationContextAssembler`
- `KnowledgeRetrievalService`
- 若主上下文已通过组件扫描或其他配置提供，则直接注入
- 若缺失，则在本 Task 中补注册；不能假设它们一定存在

- [ ] **Step 2: 显式注册 `PostDraftReviewSessionFactory` / `PostDraftReviewProblemClassifier` / `ReviewRuntimeVisualizer` / `ProjectReviewRuntimePersistenceHook`**

```java
@Bean
public PostDraftReviewSessionFactory postDraftReviewSessionFactory() {
    return new PostDraftReviewSessionFactory();
}

@Bean
public PostDraftReviewProblemClassifier postDraftReviewProblemClassifier() {
    return new PostDraftReviewProblemClassifier();
}

@Bean
public ReviewRuntimeVisualizer reviewRuntimeVisualizer() {
    return ReviewRuntimeVisualizer.noop();
}

@Bean
public ProjectReviewRuntimePersistenceHook projectReviewRuntimePersistenceHook(
        PostDraftReviewAgentWriter writer,
        ReviewSessionStore reviewSessionStore) {
    return new DefaultProjectReviewRuntimePersistenceHook(writer, reviewSessionStore);
}
```

- [ ] **Step 3: 按正确顺序装配 term writer / writer / session store / gateway / service**

```java
@Bean
public PostDraftReviewAgentService postDraftReviewAgentService(
        PostDraftReviewAgentReader reader,
        PostDraftReviewSessionFactory sessionFactory,
        PostDraftReviewProblemClassifier problemClassifier,
        PostDraftReviewProcessSummaryAssembler summaryAssembler,
        HumanInTheLoopGateway humanGateway,
        PostDraftReviewAgentWriter writer,
        PostDraftReviewAgentTermWriter termWriter,
        ReviewAgentStructuredGenerationPort generationPort,
        ReviewSessionStore reviewSessionStore,
        ReviewRuntimeVisualizer runtimeVisualizer,
        ProjectReviewRuntimePersistenceHook persistenceHook) {
    return new PostDraftReviewAgentService(
            reader,
            sessionFactory,
            problemClassifier,
            summaryAssembler,
            humanGateway,
            writer,
            termWriter,
            generationPort,
            reviewSessionStore,
            runtimeVisualizer,
            persistenceHook
    );
}
```

- [ ] **Step 4: 用属性类承接 session 目录、CLI 模式和 LLM retry 参数**
- [ ] **Step 4: 用属性类承接 session 目录和 CLI 模式；LLM retry 参数放回 `ReviewAgentLlmProperties`**

```java
@ConfigurationProperties(prefix = "quillloom.postdraft.review.runtime")
public class ReviewAgentRuntimeProperties {

    private Path sessionDirectory;
    private boolean cliEnabled;
}
```

```java
@ConfigurationProperties(prefix = "quillloom.postdraft.review.llm")
public class ReviewAgentLlmProperties {

    private int maxRetries = 3;
    private Duration retryBackoff = Duration.ofSeconds(1);
}
```

- [ ] **Step 5: 写最小上下文装配测试，避免 `@SpringBootTest` 拉起完整数据库 / API key 依赖**

```java
class PostDraftReviewAgentRuntimeConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PostDraftReviewAgentRuntimeConfiguration.class);

    @Test
    void shouldAssembleReviewAgentRuntimeBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PostDraftReviewAgentService.class);
            assertThat(context.getBean(PostDraftReviewAgentWriter.class))
                    .isInstanceOf(PostgresPostDraftReviewAgentWriter.class);
        });
    }
}
```

- [ ] **Step 6: 运行装配测试**

Run: `mvn -q "-Dtest=PostDraftReviewAgentRuntimeConfigurationTest" test`
Expected: PASS，`PostDraftReviewAgentService` 与其依赖 Bean 均可成功注入

---

### Task 3: 提供同步 `CommandLineRunner`，先打通最小 start/resume 运行器

**Files:**
- Create: `src/main/java/io/quillloom/interfaces/runner/PostDraftReviewAgentCommandLineRunner.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/ReviewAgentRuntimeProperties.java`
- Create: `src/test/java/io/quillloom/interfaces/runner/PostDraftReviewAgentCommandLineRunnerTest.java`

- [ ] **Step 1: 定义 CLI 运行模式属性**

```java
public class ReviewAgentRuntimeProperties {

    private boolean cliEnabled;
    private String cliAction;
    private String cliProjectId;
}
```

- [ ] **Step 2: 让 runner 优先从命令行参数读取 `humanReviewNote`，避免要求用户改配置文件后重启**

```java
@Component
@ConditionalOnProperty(prefix = "quillloom.postdraft.review.runtime", name = "cli-enabled", havingValue = "true")
public class PostDraftReviewAgentCommandLineRunner implements CommandLineRunner {

    @Override
    public void run(String... args) {
        if ("start".equalsIgnoreCase(properties.getCliAction())) {
            service.reviewProject(new StartProjectPostDraftReviewAgentCommand(properties.getCliProjectId(), ""));
            return;
        }
        if ("resume".equalsIgnoreCase(properties.getCliAction())) {
            service.resumeProject(properties.getCliProjectId(), requireArg(args, "--humanReviewNote="));
            return;
        }
        throw new IllegalArgumentException("unsupported cli action: " + properties.getCliAction());
    }
}
```

说明：
- 第一阶段先支持命令行参数传入 `humanReviewNote`
- 暂不要求交互式 stdin
- 目标是便于脚本化 smoke，而不是做完整交互式 CLI

- [ ] **Step 3: 为 runner 写测试，确认 start / resume 路径都能进入 service**

```java
@Test
void shouldResumeProjectWhenCliActionIsResume() throws Exception {
    ReviewAgentRuntimeProperties properties = new ReviewAgentRuntimeProperties();
    properties.setCliEnabled(true);
    properties.setCliAction("resume");
    properties.setCliProjectId("project-1");
    runner.run("--humanReviewNote=Louki 统一译为露姬");
    verify(service).resumeProject("project-1", "Louki 统一译为露姬");
}
```

- [ ] **Step 4: 运行 runner 测试**

Run: `mvn -q "-Dtest=PostDraftReviewAgentCommandLineRunnerTest" test`
Expected: PASS

---

### Task 4: 建立统一状态投影，先解决“外部怎么知道 agent 正在等人”

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentStatusService.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewProjectStatusView.java`
- Create: `src/main/java/io/quillloom/interfaces/api/dto/PostDraftReviewProjectStatusResponse.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`

- [ ] **Step 1: 在 application 层定义统一状态投影，CLI / REST 共用**

```java
public record PostDraftReviewProjectStatusView(
        String projectId,
        String status,
        String stopReason,
        String currentChunkId,
        int completedChunkCount,
        boolean waitingHuman,
        String latestHumanQuestion
) {
}
```

- [ ] **Step 2: 在 `PostDraftReviewAgentService` 中维护线程安全的运行中 session 视图，供状态服务读取**

```java
private final ConcurrentHashMap<String, ProjectReviewRuntimeSession> activeRuntimes = new ConcurrentHashMap<>();

public Optional<ProjectReviewRuntimeSession> findActiveRuntime(String projectId) {
    return Optional.ofNullable(activeRuntimes.get(projectId));
}
```

更新规则：
- `reviewProject(...)` 开始时 `put(projectId, initialRuntime)`
- `resumeProject(...)` 开始时 `put(projectId, resumedRuntime)`
- agent 每轮状态跃迁后，用最新不可变 runtime 覆盖 map 中的值
- `COMPLETED` / `FAILED` 时 `remove(projectId)`
- `WAITING_HUMAN` 时，先完成 session 落盘，再从内存 map 中移除，避免状态查询时出现“内存态 + 文件态”双源冲突
- `PostDraftReviewAgentStatusService` 只读依赖 `PostDraftReviewAgentService`，`service` 不反向依赖 `statusService`

- [ ] **Step 3: 状态服务同时查询“运行中内存态”和“WAITING_HUMAN session 文件”，不能只查 session**

```java
public Optional<PostDraftReviewProjectStatusView> loadStatus(String projectId) {
    Optional<ProjectReviewRuntimeSession> inMemoryRuntime = service.findActiveRuntime(projectId);
    if (inMemoryRuntime.isPresent()) {
        return inMemoryRuntime.map(this::toView);
    }
    return reviewSessionStore.load(projectId)
            .map(StoredReviewSession::runtime)
            .map(this::toView);
}
```

约束：
- `ACTIVE` 运行中时通常没有 session 文件，因此不能用“查文件是否存在”代表项目状态
- `Optional.empty()` 只能表示“既不在运行中，也没有可恢复 session”

- [ ] **Step 4: 在 interface 层再包一层 REST DTO，不把 HTTP 结构渗入 application**

```java
public record PostDraftReviewProjectStatusResponse(
        String projectId,
        String status,
        String stopReason,
        String currentChunkId,
        int completedChunkCount,
        boolean waitingHuman,
        String latestHumanQuestion
) {
    public static PostDraftReviewProjectStatusResponse from(PostDraftReviewProjectStatusView view) {
        return new PostDraftReviewProjectStatusResponse(
                view.projectId(),
                view.status(),
                view.stopReason(),
                view.currentChunkId(),
                view.completedChunkCount(),
                view.waitingHuman(),
                view.latestHumanQuestion()
        );
    }
}
```

- [ ] **Step 5: 运行状态服务相关测试**

Run: `mvn -q "-Dtest=PostDraftReviewAgentServiceTest,FileReviewSessionStoreTest" test`
Expected: PASS，且状态投影能区分“ACTIVE 运行中”“WAITING_HUMAN 已落盘”“不存在 session”

---

### Task 5: 在 LLM 客户端层补齐 429/503/超时重试与诊断日志

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfiguration.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/ReviewAgentLlmProperties.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- Modify: `src/test/java/io/quillloom/infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfigurationTest.java`

- [ ] **Step 1: 先确认 LangChain4j 当前 builder 是否满足 retry 需求，只放开可重试错误**

```java
ChatModel chatModel = OpenAiChatModel.builder()
        .baseUrl(properties.getBaseUrl())
        .apiKey(properties.getApiKey())
        .modelName(properties.getModelName())
        .maxRetries(properties.getMaxRetries())
        .timeout(WorkflowFixedLlmTimeouts.standardTimeout())
        .build();
```

- [ ] **Step 2: 如果内置能力不足，再把重试包装限制在 client 层，不进入 agent loop**

```java
try {
    return delegate.generate(request);
} catch (RuntimeException ex) {
    if (!retryPolicy.isRetryable(ex)) {
        throw ex;
    }
    // log retry_attempt / retry_reason / backoff_ms
}
```

- [ ] **Step 3: 明确可重试 / 不可重试边界，并补齐基础设施失败 stop reason**

```java
private boolean isRetryable(Throwable error) {
    return isRateLimit(error) || isServiceUnavailable(error) || isTimeout(error);
}
```

补充约束：
- 不可重试：
  - 400
  - 401
  - 结构化输出解析失败 / repair 失败
  - `NO_PROGRESS`
  - guardrail 拒绝
- 若最终因可重试类基础设施错误失败，需要能映射到新的 stop reason，例如 `LLM_CALL_FAILED`

- [ ] **Step 4: 写测试确认 400/401/结构化输出失败/NO_PROGRESS 不被误重试**

Run: `mvn -q "-Dtest=PostDraftReviewAgentRuntimeConfigurationTest" test`
Expected: PASS，且日志中可区分 retry attempt 与 agent 自主多轮决策

---

### Task 6: 做一条真实项目 smoke / e2e，验证 start -> WAITING_HUMAN -> resume -> 完成

**Files:**
- Create: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentEndToEndSmokeTest.java`
- Create: `src/test/java/io/quillloom/application/postdraft/review/support/ScriptedReviewAgentGenerationPort.java`
- Modify: `docs/superpowers/plans/2026-04-18-review-agent-e2e-run-gap-analysis.md`
- Modify: `docs/handoff.md`

- [ ] **Step 1: 用 scripted / mock generation port 控制 agent 路径，避免把 WAITING_HUMAN 建立在不稳定的真实 LLM 行为上**

```java
@Test
void shouldResumeFromWaitingHumanSessionAndFinishProject() {
    PostDraftReviewAgentResult firstPass = service.reviewProject(startCommand("project-1"));
    assertThat(firstPass.humanReviewRequest()).isPresent();
    assertThat(sessionStore.load("project-1")).isPresent();

    PostDraftReviewAgentResult resumed = service.resumeProject("project-1", "Louki 统一译为露姬");
    assertThat(resumed.finalMergedTranslatedText()).isNotBlank();
}
```

约束：
- 优先核实现有 `SequenceGenerationPort` 是否足以精确覆盖 review agent 的结构化生成调用序列
- 若不足，则新增 `ScriptedReviewAgentGenerationPort`，按调用顺序返回 investigation decision / evaluation / revision / self-check 所需结果
- e2e 测试要显式控制 LLM 决策序列：
  - investigate
  - evaluate
  - request_human_review
  - resume 后继续
  - complete
- 不能假设真实 LLM 一定进入 `WAITING_HUMAN`

- [ ] **Step 2: 运行 e2e smoke**

Run: `mvn -q "-Dtest=PostDraftReviewAgentEndToEndSmokeTest" test`
Expected: PASS，覆盖完整 start / pause / persist / resume / writeback

- [ ] **Step 3: 同步 gap-analysis 与 handoff，记录真实可运行边界**

```markdown
- Spring Bean 链已具备
- CLI smoke 入口已具备
- WAITING_HUMAN 可持久化并恢复
- 真实项目 smoke 已验证
```

---

### Task 7: 提供异步 REST API 和状态查询，形成可对外调用的最小产品入口

**Files:**
- Create: `src/main/java/io/quillloom/interfaces/api/PostDraftReviewAgentController.java`
- Create: `src/main/java/io/quillloom/interfaces/api/dto/PostDraftReviewProjectStartRequest.java`
- Create: `src/main/java/io/quillloom/interfaces/api/dto/PostDraftReviewProjectResumeRequest.java`
- Create: `src/test/java/io/quillloom/interfaces/api/PostDraftReviewAgentControllerTest.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfiguration.java`

- [ ] **Step 1: 定义异步 start / resume / status API**

```java
@RestController
@RequestMapping("/api/review/project")
public class PostDraftReviewAgentController {

    @PostMapping("/start")
    public ResponseEntity<Void> start(@RequestBody PostDraftReviewProjectStartRequest request) { ... }

    @PostMapping("/resume")
    public ResponseEntity<Void> resume(@RequestBody PostDraftReviewProjectResumeRequest request) { ... }

    @GetMapping("/{projectId}/status")
    public PostDraftReviewProjectStatusResponse status(@PathVariable String projectId) { ... }
}
```

- [ ] **Step 2: 用 `@Async` 或明确任务执行器包装 service 调用，不把异步逻辑塞进 agent loop**

```java
@Async("postDraftReviewAgentExecutor")
public void startAsync(String projectId, String operatorNote) {
    service.reviewProject(new StartProjectPostDraftReviewAgentCommand(projectId, operatorNote));
}
```

约束：
- `@Async` 入口必须位于独立的 async service / facade 中，由外部 Bean 调用，避免同类内自调用导致代理失效
- 异步异常不能静默吞掉；需要使用 `CompletableFuture` 返回失败，或配置 `AsyncUncaughtExceptionHandler`

- [ ] **Step 3: 写 controller 测试，确认三条入口都能路由到 service / statusService**

Run: `mvn -q "-Dtest=PostDraftReviewAgentControllerTest" test`
Expected: PASS

---

### Task 8: 统一失败分类与对外返回模型，并同步方向文档

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewStatus.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewProjectStopReason.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ExternalReviewProjectStatus.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentStatusService.java`
- Modify: `docs/superpowers/plans/2026-04-18-review-agent-direction-anchor.md`
- Modify: `docs/handoff.md`

- [ ] **Step 1: 在不破坏现有 runtime status 的前提下，给对外状态投影补失败分类**

```java
public enum ExternalReviewProjectStatus {
    ACTIVE,
    WAITING_HUMAN,
    COMPLETED,
    FAILED_INFRA_RETRYABLE,
    FAILED_BUG
}
```

- [ ] **Step 2: 明确映射规则**

```java
private ExternalReviewProjectStatus toExternalStatus(ProjectReviewRuntimeSession runtime) {
    if (runtime.status() == ProjectReviewStatus.WAITING_HUMAN) {
        return ExternalReviewProjectStatus.WAITING_HUMAN;
    }
    return switch (runtime.stopReason()) {
        case LLM_CALL_FAILED -> ExternalReviewProjectStatus.FAILED_INFRA_RETRYABLE;
        case NO_PROGRESS, FAILED -> ExternalReviewProjectStatus.FAILED_BUG;
        default -> ExternalReviewProjectStatus.ACTIVE;
    };
}
```

前提：
- Task 5 需要先补齐新的基础设施失败 stop reason，否则这里无法区分 `FAILED_INFRA_RETRYABLE`

- [ ] **Step 3: 同步锚定文档和 handoff**

```markdown
- 对外调用方必须能区分 WAITING_HUMAN / FAILED_BUG / FAILED_INFRA_RETRYABLE
- 该分类属于对外状态投影，不改变 agent loop 的内部状态机
```

- [ ] **Step 4: 运行相关测试**

Run: `mvn -q "-Dtest=PostDraftReviewAgentControllerTest,PostDraftReviewAgentRuntimeConfigurationTest" test`
Expected: PASS

---

## 本计划之后的后置项

以下事项不纳入本轮“runtime enablement”实施：

- D-12：按 focus checkpoint 的崩溃恢复
- D-08：ReviewTool 注册式解耦
- D-09：结构化压缩摘要
- D-13：流式输出预留的进一步产品化
- D-14：受控联网搜索

这些项仍然有效，但前提是先完成本计划，把系统推进到“可启动、可调用、可长跑、可观测”。

---

## 自检

### 1. 覆盖检查

- 你指出的架构问题“hook 依赖 infrastructure writer”已被放到 Task 1 的首要修正项。
- 你指出的完整 Bean 链与构造顺序问题已放到 Task 2。
- 你指出的“入口逻辑已有，缺的是运行器”已拆成 Task 3（CLI）和 Task 7（异步 REST）。
- 你指出的“外部怎么知道需要人工介入”已放到 Task 4 的统一状态投影和 Task 7 的 `GET /status`。
- 你指出的 LLM 重试边界与位置问题已放到 Task 5，并明确限制在客户端层。
- 你指出的真实项目 smoke / e2e 应前置已放到 Task 6。
- 你指出的失败分类要与现有模型对齐已放到 Task 8。

### 2. 占位符检查

- 没有使用 `TODO` / `TBD` / “稍后实现” 之类占位词。
- 每个任务都给了明确文件路径、变更方向和验证命令。

### 3. 类型一致性检查

- `PostDraftReviewAgentWriter` 始终是 application port。
- `ProjectReviewRuntimePersistenceHook` 始终是 application service 边界，不直接依赖 infrastructure 实现。
- `PostDraftReviewAgentService.reviewProject(...)` / `resumeProject(...)` 始终被视为既有入口逻辑，新增的是运行器和 API 暴露层。
