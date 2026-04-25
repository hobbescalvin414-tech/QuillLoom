# Review Agent 完整跑通差距分析

**日期**：2026-04-18
**目标**：让 Review Agent 能在真实数据上跑通完整流程——启动 → 自主审校 → HITL 问答/恢复 → 最终产出持久化

---

## 当前能做什么

- ✅ 通过 smoke test 启动项目级审校（已验证 `book-smoke-1776178359703`）
- ✅ Agent 自主循环（while true + 工具调用 + LLM 决策）
- ✅ 13 个工具全部可用（含向量检索）
- ✅ Transcript + Evidence 自动压缩
- ✅ System Prompt 分离 + Per-tool Schema
- ✅ 结构化输出 + repair retry
- ✅ Console 可视化
- ✅ LLM 格式容错（字符串/数组/对象自动解析）

## 已知运行问题（smoke test 实测发现）

1. **LLM 输出 queryTerms 为字符串而非数组** → 已修：`toStringListFromArgument` 容错 + JSON Schema 补 `queryTerms`
2. **agent 跑完后修订译文不落库** → 未修：`PassThroughPostDraftReviewAgentWriter` 只透传
3. **HITL 暂停后无法恢复** → 已修到 Java 调用链：`WAITING_HUMAN` 可完整落盘并通过 `resumeProject(...)` 恢复；Spring 装配未做
4. **压缩摘要质量差** → 未修：硬拼 4 个字段，丢失大量信息
5. **NO_PROGRESS 仍是 bug 暴露路径** → 按新约束保留 FAILED，不转 HITL

## 完整跑通还差什么

分三类：**必须做**（不跑不通）、**应该做**（跑了但不完整）、**可以做**（体验优化）。

---

## 一、必须做（不跑不通）

### 1.1 修订译文写回数据库

**现状**：`PassThroughPostDraftReviewAgentWriter` 只透传，不写库。agent 跑完修订后的译文只存在于方法返回值里，进程结束就没了。

**需要**：`PostgresPostDraftReviewAgentWriter` 实现 `PostDraftReviewAgentWriter`，将修订后的 chunk 译文写回 `ql_post_draft_review_package`。

**改动范围**：
- 新增 `PostgresPostDraftReviewAgentWriter`
- 写入时机：`complete_working_set` 完成时，每个 chunk 的 `finalTranslation` 更新到 `chunks_json` 对应条目
- 项目完成时，`mergedDraftText` 写入 `merged_draft_text` 字段
- `PostDraftReviewAgentService` 注入真正的 writer 替换 `PassThrough`

**涉及文件**：
- 新增：`infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriter.java`
- 修改：`application/postdraft/review/service/PostDraftReviewAgentService.java`（注入新 writer）
- 修改：`infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfiguration.java`（注册 bean）
- 参考：`infrastructure/postdraft/PostgresPostDraftReviewPackageRepository.java`（已有的 save 逻辑）

**验证**：agent 跑完后，从数据库读 `ql_post_draft_review_package`，确认 chunks_json 中的译文已更新。

### 1.2 求助式 HITL（Codex 风格）

**现状**：本轮先打通 Java 调用链。agent 主动调用 `request_human_review` 后，runtime 进入 `WAITING_HUMAN`，完整 session 落到本地 JSON；后续通过 `PostDraftReviewAgentService.resumeProject(...)` 喂入人工自由文本后继续运行。`HumanInTheLoopGateway` 只负责提交/发布人工求助请求，不承担恢复入口角色。

**目标**：把 HITL 收敛为“正常暂停点 + 可恢复 session”语义，而不是“阻塞等待人回答”或“人工排障”。

**关键区分**：

| 场景 | 当前/本轮行为 | 说明 |
|------|---------|---------|
| agent 不确定（如术语无法确定） | 调用 `request_human_review`，进入 `WAITING_HUMAN`，完整落盘，后续 `resumeProject(...)` 恢复 | 正常工作流 |
| agent 卡死（NO_PROGRESS） | 整个项目标记 FAILED，不落盘，不恢复 | bug 暴露路径，不属于 HITL |

**需要改动**：

1. **`HumanInTheLoopGateway.submit()` 保持“请求发布口”角色**
   - 当前与本轮：`submit(request)` 只负责把求助请求交给外部系统
   - 不改为阻塞等待人工回答

2. **新增 service 级恢复入口**
   - `PostDraftReviewAgentService.resumeProject(projectId, humanReviewNote)`
   - 从本地 session JSON 读取完整 `ProjectReviewRuntimeSession`
   - 调用 `autonomousAgent.resume(...)`

3. **`WAITING_HUMAN` 是唯一允许完整落盘的正常暂停点**
   - 通过 runtime persistence hook 统一处理
   - 人工回答写入 transcript/history，作为证据，不是命令

4. **NO_PROGRESS 不进入 HITL**
   - 当前：`failNoProgress(rejectionKey)` → FAILED
   - 本轮保持：FAILED，不生成 `WAITING_HUMAN` session，不落盘

**涉及文件**：
- 修改：`application/postdraft/review/service/AutonomousProjectReviewAgent.java`（接入 persistence hook，保留 `resume(...)`）
- 修改：`application/postdraft/review/service/PostDraftReviewAgentService.java`（新增 `resumeProject(...)`）
- 修改：`application/postdraft/review/port/out/HumanInTheLoopGateway.java`（角色重定义，不改成交互阻塞器）
- 修改：`application/postdraft/review/model/StoredReviewSession.java`
- 修改：`infrastructure/postdraft/review/FileReviewSessionStore.java`

### 1.3 Session 持久化可恢复

**现状**：`StoredReviewSession` 是精简快照，丢失了 `currentFocusSession`、`completedChunkOutcomes` 完整内容、`humanReviewRequest` 等关键信息。从 `StoredReviewSession` 无法恢复 agent 运行状态。

**丢失的关键信息**：
1. `currentFocusSession`（PostDraftReviewSession）— 整个 focus session 未保存
2. `completedChunkOutcomes` 的完整内容 — 只存了 chunkId，丢失了 finalTranslation、strategy、processSummary
3. `issueBacklog` 的完整内容 — 只存了 issueId
4. `humanReviewRequest` — 完全未保存
5. `currentFocusRound` — 未保存

**需要**：两种方案选一：

**方案 A：完整序列化**（推荐）
- `StoredReviewSession` 保存完整的 `ProjectReviewRuntimeSession` JSON
- `ReviewSessionStore.load()` 反序列化后能完整恢复
- 优点：resume 时状态完整，不丢信息
- 缺点：JSON 较大（但一个项目也就几百 KB）

**方案 B：从数据库重建**
- resume 时从 `ql_post_draft_review_package` 重新加载 chunks、terms 等
- 只存 session 的增量状态（transcript、history、processTrail）
- 优点：存储小
- 缺点：重建逻辑复杂，需要保证和原始状态一致

**建议选方案 A**，简单可靠。

**涉及文件**：
- 修改：`application/postdraft/review/model/StoredReviewSession.java`
- 修改：`infrastructure/postdraft/review/FileReviewSessionStore.java`
- 可能需要：`ProjectReviewRuntimeSession` 的 Jackson 序列化注解

**本轮新增边界**：

- `ProjectReviewRuntimePersistenceHook` 作为运行时副作用边界，统一处理：
  1. 新完成 chunk 的译文写库
  2. `WAITING_HUMAN` 时完整 session 落盘
  3. `COMPLETED` 时写回 `merged_draft_text` 并清理 session 文件

---

## 二、应该做（跑了但不完整）

### 2.1 工具系统解耦

**现状**：`ReviewToolExecutor` 是 switch-case 大类，13 个工具的执行逻辑全部写在一个 550+ 行的类里。每加一个工具需要改 5 个文件。

**当前加一个新工具的改动**：
1. `ReviewToolRegistry.defaultRegistry()` — 注册工具定义
2. `ReviewToolExecutor.execute()` — 加 switch case
3. `ReviewToolExecutor` — 加 private executeXxx() 方法
4. `ReviewToolDecisionContractValidator` — 加参数校验
5. `OpenAiCompatibleReviewAgentStructuredGenerationClient.investigationArgumentsSchema()` — 加参数声明

**目标**：加一个工具只需要 1 个文件——一个实现了 `ReviewTool` 接口的类，自动注册、自动校验、自动执行。

**需要改动**：

1. **定义 `ReviewTool` 接口**
   ```java
   public interface ReviewTool {
       ReviewToolDefinition definition();           // 工具定义（名称、描述、参数）
       ReviewToolExecutionResult execute(ReviewToolExecutionContext context, ReviewToolCall call);
       default Optional<String> validateArguments(ReviewToolCall call) { return Optional.empty(); }
   }
   ```

2. **定义 `ReviewToolExecutionContext`**
   ```java
   public record ReviewToolExecutionContext(
       ProjectReviewRuntimeSession runtime,
       PostDraftReviewAgentReader reader,
       PostDraftReviewAgentTermWriter termWriter,
       // ... 其他共享依赖
   ) {}
   ```

3. **每个工具一个实现类**
   ```
   review/tool/
   ├── ReadPreviousChunksTool.java
   ├── ReadNextChunksTool.java
   ├── ExpandBlockContextTool.java
   ├── LookupKnowledgeCardsTool.java
   ├── ReadConfirmedTermsTool.java
   ├── RecordConfirmedTermsTool.java
   ├── EvaluateFocusTool.java
   ├── DraftRevisionTool.java
   ├── RequestHumanReviewTool.java
   ├── CompleteWorkingSetTool.java
   └── CompleteProjectTool.java
   ```

4. **`ReviewToolExecutor` 改为分发器**
   - 不再包含任何工具执行逻辑
   - 从 `ReviewToolRegistry` 查找 `ReviewTool` 实现
   - 调用 `tool.execute(context, call)`
   - guardrail 校验委托给 `tool.validateArguments()`

5. **`ReviewToolRegistry` 改为自动发现**
   - Spring 注入所有 `ReviewTool` 实现
   - 自动构建工具定义列表
   - `defaultRegistry()` 降级为测试用工厂方法

6. **`investigationArgumentsSchema()` 改为动态生成**
   - 从 `ReviewToolRegistry` 的所有 `ReviewTool` 中提取参数定义
   - 不再硬编码每个工具的参数

**涉及文件**：
- 新增：`application/postdraft/review/tool/ReviewTool.java`
- 新增：`application/postdraft/review/tool/ReviewToolExecutionContext.java`
- 新增：11 个工具实现类（`application/postdraft/review/tool/`）
- 修改：`application/postdraft/review/service/ReviewToolExecutor.java`（改为分发器）
- 修改：`application/postdraft/review/service/ReviewToolRegistry.java`（自动发现）
- 修改：`infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`（动态 schema）
- 可删除：`application/postdraft/review/service/ReviewToolDecisionContractValidator.java`（校验逻辑移入各工具）

**参考**：claw-code 的 `ToolProvider` trait 设计

**风险**：改动量大，需要确保 13 个工具的行为完全不变。建议逐个迁移，每迁移一个跑一次测试。

### 2.2 结构化压缩摘要

**现状**：`AutonomousProjectReviewAgent.buildCompactSummary()` 硬拼 4 个字段：
```java
"[compact] 已完成 %d 轮取证，当前策略=%s，已读chunk=%s，关键发现=%s"
```

丢失了大量信息：矛盾证据、证据缺口、guardrail 拒绝历史、工具调用统计、策略变化历史等。

**需要**：从 session 的各个记忆项中提取关键信息，生成结构化摘要：

```
[compact] 
轮次=12 | 策略历史=KEEP→LIGHT_EDIT→HEAVY_EDIT→KEEP
已用工具: read_previous(3), evaluate(3), complete_working_set(2)
已确认术语: Louki→露姬, Vianne→薇安
矛盾证据: chunk-3 与 chunk-5 对 festival 译法不一致
guardrail 拒绝: missing_argument:chunkIds(1次)
```

**信息来源**：

| 摘要字段 | 来源 |
|---------|------|
| 轮次 | `transcriptStore.replay().size()` |
| 策略历史 | `session.strategy()` + transcript 中的策略变化记录 |
| 已用工具 | `session.toolTraces()` 统计 |
| 已确认术语 | `session.evidenceBundle().evidenceSummaries()` 中 `recordedConfirmedTerm=` 开头的条目 |
| 矛盾证据 | `session.evidenceBundle().conflictingEvidenceSummaries()` |
| guardrail 拒绝 | `session.diagnostics().localRejectionReasons()` |

**不需要 LLM 调用**，只是多读几个字段拼字符串。

**涉及文件**：
- 修改：`application/postdraft/review/service/AutonomousProjectReviewAgent.java`（`buildCompactSummary()`）

### 2.3 REST API 入口

**现状**：没有 review 相关的 REST 端点，只能通过 Java 代码调用。

**需要**：
- `POST /api/review/project/start` — 启动项目审校
- `POST /api/review/project/{projectId}/resume` — 恢复 HITL
- `GET /api/review/project/{projectId}/status` — 查询状态

**改动范围**：
- 新增 `ReviewAgentController`
- 异步执行（agent 跑 130 chunk 可能要几小时，不能阻塞 HTTP 请求）
- 需要任务队列或 `@Async`

**涉及文件**：
- 新增：`interfaces/api/ReviewAgentController.java`
- 修改：`infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfiguration.java`

### 2.4 运行进度可观测 + 流式输出

**现状**：只有 `ConsoleReviewRuntimeVisualizer`，输出到 stdout。

**暂不做，但架构上预留流式输出位置**：
- `ReviewRuntimeVisualizer` 接口已经抽象了 5 个事件方法，后续加 `DatabaseReviewRuntimeVisualizer`（写事件表）或 `SseReviewRuntimeVisualizer`（推流）只需新增实现类
- `AutonomousProjectReviewAgent.run()` 中的 `runtimeVisualizer` 调用点就是流式输出的注入点，不需要改循环逻辑
- 后续做流式时，`ReviewRuntimeVisualizer` 的事件方法签名可能需要改为异步（返回 `CompletableFuture` 或使用 `Flux`），当前先保持同步

### 2.5 LLM 重试/退避

**现状**：LLM 调用失败（429/503/网络超时）直接崩整个 agent。

**需要**：
- `OpenAiCompatibleReviewAgentStructuredGenerationClient.invoke()` 加重试
- 指数退避：1s → 2s → 4s，最多 3 次
- 429 和 503 重试，400 和 500 不重试

**涉及文件**：
- 修改：`infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- 修改：`infrastructure/postdraft/review/ReviewAgentLlmProperties.java`

### 2.6 Spring Bean 装配

**现状**：`PostDraftReviewAgentRuntimeConfiguration` 只注册了 `ReviewAgentStructuredGenerationPort` 一个 Bean。`PostDraftReviewAgentService` 的所有依赖（reader、writer、sessionStore 等）都在 smoke test 里手动构建。

**需要**：
- `PostDraftReviewAgentRuntimeConfiguration` 注册完整的 Bean 链
- 或者新增一个 `PostDraftReviewAgentAutoConfiguration`
- 包括：reader、writer、termWriter、sessionStore、humanGateway、visualizer、service

**涉及文件**：
- 修改：`infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfiguration.java`

---

## 三、可以做（体验优化）

### 3.1 Token 预算追踪

`UsageBudget` 和 `UsageSummary` 是空壳。130+ chunk 长跑时没有安全阀，可能烧很多钱。

**涉及文件**：
- 修改：`application/postdraft/review/model/UsageSummary.java`
- 修改：`application/postdraft/review/port/out/ReviewAgentStructuredGenerationPort.java`
- 修改：`infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- 修改：`application/postdraft/review/service/AutonomousProjectReviewAgent.java`

### 3.2 项目级记忆压缩

working set 完成后，跨焦点的摘要。当前每个 focus 独立，后续 focus 看不到前面 focus 的关键发现。

### 3.3 Legacy 构造器清理

`PostDraftReviewAgentService` 有 6 个构造函数，大部分是历史遗留。

### 3.4 withCurrentFocusSession 脆弱点

硬编码 `ACTIVE` + 清空 `humanReviewRequest`，存在静默吞状态的风险。

### 3.5 ProjectFocusSelectorTest 预存失败

2 个测试断言与代码行为不一致。

---

## 建议实施顺序

```
1.1 修订译文写回数据库        ← 最关键，没有这个跑完也白跑
1.3 Session 持久化可恢复      ← HITL 的前提
1.2 求助式 HITL              ← 依赖 1.3，当前已按“WAITING_HUMAN 落盘 + service 恢复入口”打通 Java 调用链
2.1 工具系统解耦              ← 后续扩展的基础，建议在加新工具前先做
2.2 结构化压缩摘要            ← 提升压缩质量，不影响主流程
2.5 Spring Bean 装配          ← 2.3 的前提
2.6 LLM 重试/退避             ← 130 chunk 长跑不被限流搞崩
--- 以下暂不做，但架构上预留 ---
2.3 REST API 入口             ← 给非 Java 用户用，当前 smoke test 够用
2.4 运行进度可观测 + 流式输出  ← 给编辑/前端用，当前 console 够用；Visualizer 接口已预留流式注入点
2.7 断点恢复                  ← 开发期：HITL 暂停必须恢复，异常崩溃从头开始；稳定后：崩溃也要能恢复（靠 1.1 逐 focus 写库 + 1.3 session 持久化，崩了只丢当前 focus，从上一个完成的 focus 恢复）
```

**最小可跑通路径**：只做 1.1 + 1.3 + 1.2 + 2.5，就能通过 REST API 启动 agent、跑完所有 chunk、遇到不确定时主动问人、修订译文落库。

**扩展性路径**：做完 2.1 工具系统解耦后，加新工具只需 1 个文件。

---

## 与已有计划的关系

- `2026-04-18-review-agent-e2e-hardening-plan.md`：agent 内部健壮性，8 个问题中 5 个已完成（1a、1b、3、4、8），1 个半做（2，底层已实现但上层未接通，设计方向已变更为求助式），3 个未做（1c、1d、6、7）。Problem 2 的原排障式方案已废弃，新方向见本文档 1.2 节。
- `2026-04-18-review-agent-e2e-hardening-impl-report.md`：实施报告，记录已完成和未完成的工作。Problem 2 已从"✅ 已完成"更正为"⚠️ 半做"。
- 本文档：agent 外部运行环境——持久化、API、HITL 交互、压缩质量、工具解耦。两者互补，不重叠。

---

## Codex 接手注意事项

1. **编译验证**：改完跑 `mvn clean compile -q`，测试跑 `mvn clean test-compile -q` 后再跑具体测试
2. **BOM 问题**：如果用 PowerShell 脚本写 Java 文件，必须用无 BOM 的 UTF-8。用 `[System.IO.File]::WriteAllText(path, content, (New-Object System.Text.UTF8Encoding $false))` 而不是 `Out-File`
3. **测试风格**：项目测试不用 Mockito，全部用内部类手写 test double（InMemoryReader、SequenceGenerationPort 等）
4. **构造器变更**：`RepositoryBackedPostDraftReviewAgentReader` 现在是 4 参数构造器（加了 `KnowledgeRetrievalService`），`RepositoryBackedPostDraftReviewAgentTermWriter` 是 3 参数构造器（加了 `reader`）
5. **ReviewAgentConfig**：现在是 5 参数 record（`maxTurns`, `usageBudget`, `compactAfterTurns`, `compactKeepLast`, `compactKeepLastEvidence`）
6. **ReviewToolDefinition**：现在有 4 参数构造器（加了 `argumentSchemas`），3 参数构造器仍可用（兼容）
7. **ReviewAgentStructuredGenerationPort**：所有方法签名已改为 `(String systemPrompt, String userPrompt)`
8. **PostDraftReviewAgentReader.lookupKnowledgeCards**：签名已改为 `(String projectId, String chunkId, List<String> queryTerms)`
9. **HITL 设计方向**：必须是"求助式"而非"排障式"——agent 主动问人；`WAITING_HUMAN` 是唯一允许完整落盘和后续恢复的正常暂停点。恢复入口在 `PostDraftReviewAgentService.resumeProject(...)`，人工输入是证据，不是命令。
10. **压缩摘要**：当前 `buildCompactSummary()` 硬拼 4 个字段质量太差，需要改为从 session 各记忆项提取信息的结构化摘要
11. **工具系统解耦**：当前 `ReviewToolExecutor` 是 switch-case 大类，加一个工具要改 5 个文件。需要改为 `ReviewTool` 接口 + 独立实现类 + 自动注册，加一个工具只需 1 个文件
