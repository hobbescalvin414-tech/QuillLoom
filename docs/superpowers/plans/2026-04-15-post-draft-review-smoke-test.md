# Post-Draft Review Smoke Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 post-draft review agent 增加一个独立的真实 smoke test 入口，能够基于现有 `projectId + chunkId` 读取正式数据、执行一次审校 agent，并把结果以可读文本写到 `run-output/postdraft-review-smoke/`。

**Architecture:** 方案只新增测试侧入口，不把 review agent 正式接入现有 API 或原初稿流水线。测试通过 Spring 上下文读取真实仓储与 reader，在测试侧补最小 bean 装配与结果渲染器，确保真实数据可跑、结果可看、失败可诊断。

**Tech Stack:** Java, Spring Boot Test, JUnit 5, 现有 PostgreSQL 仓储, postdraft.review 应用服务

---

### Task 1: 明确 smoke test 装配边界

**Files:**
- Modify: `docs/handoff.md`
- Create: `src/test/java/io/quillloom/support/PostDraftReviewSmokeSupport.java`
- Test: `src/test/java/io/quillloom/PostDraftReviewAgentSmokeTest.java`

- [ ] **Step 1: 记录本次实现边界**

在 `docs/handoff.md` 追加一段，明确本次只新增：
- `src/test` 下的 smoke test 入口
- 测试侧最小装配
- `run-output/postdraft-review-smoke/` 可视化输出

并明确不新增：
- 正式 REST API
- 正式 CLI
- 真实 retranslate 生成后端

- [ ] **Step 2: 定义 smoke support 责任**

在 `src/test/java/io/quillloom/support/PostDraftReviewSmokeSupport.java` 定义一个测试辅助类，负责：
- 解析 `projectId`、`chunkId`、`operatorNote`
- 生成输出目录
- 渲染结果摘要文本
- 提供 `preview(String)`、`sanitizeFileName(String)`、`writeReport(...)` 等纯测试辅助能力

- [ ] **Step 3: 预留 smoke test 固定属性**

在测试中约定以下 JVM 属性：
- `quillloom.test.post-draft-review-smoke.enabled=true`
- `quillloom.test.post-draft-review-smoke.project-id=<projectId>`
- `quillloom.test.post-draft-review-smoke.chunk-id=<chunkId>`
- `quillloom.test.post-draft-review-smoke.note=<operatorNote，可选>`


### Task 2: 先写 failing smoke test

**Files:**
- Create: `src/test/java/io/quillloom/PostDraftReviewAgentSmokeTest.java`
- Test: `src/test/java/io/quillloom/PostDraftReviewAgentSmokeTest.java`

- [ ] **Step 1: 写 smoke test 骨架**

```java
@SpringBootTest
@ActiveProfiles("dev")
class PostDraftReviewAgentSmokeTest {

    @Test
    void shouldRunPostDraftReviewAgentAgainstExistingProjectData() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("quillloom.test.post-draft-review-smoke.enabled"),
                "Skip post-draft review smoke test unless explicitly enabled.");
    }
}
```

- [ ] **Step 2: 在测试中声明真实依赖**

测试里通过 `@Autowired` 注入：
- `RepositoryBackedPostDraftReviewAgentReader`
- `PostDraftContinuationContextAssembler`
- `PostDraftReviewPackageRepository`
- `ProjectKnowledgeBaseRepository`

先不要写生产代码 bean，保持在测试侧装配。

- [ ] **Step 3: 让测试先失败，暴露缺少 service 装配**

在测试里直接构造：

```java
PostDraftReviewAgentService service = new PostDraftReviewAgentService(
        reader,
        new PostDraftReviewSessionFactory(),
        new PostDraftReviewProblemClassifier(),
        new PostDraftReviewStrategyResolver(),
        new PostDraftReviewProcessSummaryAssembler(),
        new InMemoryHumanInTheLoopGateway(),
        new PassThroughPostDraftReviewAgentWriter()
);
```

然后执行：

```java
PostDraftReviewAgentResult result = service.review(
        new StartPostDraftReviewAgentCommand(projectId, ReviewFocus.forChunk(chunkId), operatorNote)
);
```

先运行测试，确认失败点来自：
- 缺少输入属性
- 指定 `projectId/chunkId` 不存在
- 或输出目录尚未生成

- [ ] **Step 4: 运行单测验证 RED**

Run: `mvn -q "-Dtest=PostDraftReviewAgentSmokeTest" test`

Expected:
- 默认 skip，或
- 在显式开启但属性不完整时 fail，错误信息明确指出缺少 `project-id/chunk-id`


### Task 3: 实现最小结果可视化

**Files:**
- Create: `src/test/java/io/quillloom/support/PostDraftReviewSmokeSupport.java`
- Modify: `src/test/java/io/quillloom/PostDraftReviewAgentSmokeTest.java`
- Test: `src/test/java/io/quillloom/PostDraftReviewAgentSmokeTest.java`

- [ ] **Step 1: 在 support 中实现输出目录与报告写入**

核心代码形态：

```java
public Path prepareOutputDir(String projectId, String chunkId) throws IOException {
    Path root = Path.of("run-output", "postdraft-review-smoke");
    Files.createDirectories(root);
    Path dir = root.resolve(System.currentTimeMillis() + "-" + sanitizeFileName(projectId) + "-" + sanitizeFileName(chunkId));
    return Files.createDirectories(dir);
}
```

- [ ] **Step 2: 在 support 中实现可读报告渲染**

报告至少包含：
- `projectId`
- `chunkId`
- `finalTranslatedTextPreview`
- `strategy`
- `problemTypes`
- `processNote`
- `evidenceSummaries`
- `humanReviewRequest`

渲染形态示例：

```text
projectId=...
chunkId=...
strategy=RETRANSLATE
finalTranslatedTextPreview=...
processNote=...

[Evidence]
1. ...
2. ...

[HumanReview]
requestReason=...
resumeHint=...
```

- [ ] **Step 3: 让 smoke test 写出结果文件**

测试运行后写出至少两个文件：
- `result-summary.txt`
- `result.json` 或 `result-debug.txt`

其中 `result-summary.txt` 供人读，`result-debug.txt` 供排障。

- [ ] **Step 4: 补齐断言**

测试断言至少覆盖：
- `processSummary` 非空
- 输出目录存在
- `result-summary.txt` 已写出
- 如果 `humanReviewRequest` 存在，则 `requestReason` 非空
- 如果 `finalTranslatedText` 非空，则其预览非空

- [ ] **Step 5: 运行单测验证 GREEN**

Run: `mvn -q "-Dtest=PostDraftReviewAgentSmokeTest" test`

Expected:
- 默认 skip
- 显式开启且属性完整时通过
- `run-output/postdraft-review-smoke/<timestamp-project-chunk>/` 下生成结果文件


### Task 4: 补充可诊断性与文档同步

**Files:**
- Modify: `src/test/java/io/quillloom/PostDraftReviewAgentSmokeTest.java`
- Modify: `docs/handoff.md`
- Test: `src/test/java/io/quillloom/PostDraftReviewAgentSmokeTest.java`

- [ ] **Step 1: 增加标准控制台输出**

测试结束时输出：

```text
[PostDraftReviewAgentSmokeTest] projectId=...
[PostDraftReviewAgentSmokeTest] chunkId=...
[PostDraftReviewAgentSmokeTest] strategy=...
[PostDraftReviewAgentSmokeTest] outputDir=...
```

- [ ] **Step 2: 明确 retranslate 当前语义**

如果命中 `RETRANSLATE` 且默认 provider 未接真实后端，则允许结果进入：
- `humanReviewRequest`

并在报告中明确标记：
- `retranslationBackendConfigured=false`

不允许伪造译文。

- [ ] **Step 3: 在 handoff 中补一条当前可试跑入口**

追加说明：
- 已有 `PostDraftReviewAgentSmokeTest`
- 启用属性
- 输出目录位置
- 当前仍不等于正式产品入口

- [ ] **Step 4: 跑最终定向验证**

Run: `mvn -q "-Dtest=PostDraftReviewAgentSmokeTest,PostDraftReviewAgentServiceTest" test`

Expected:
- 两组测试通过
- smoke test 在未开启开关时 skip
- agent service 回归继续通过
