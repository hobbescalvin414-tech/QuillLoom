# [OUTDATED - 已被 2026-04-18-review-agent-direction-anchor.md 取代] 方向 C：真正自主 Post-Draft Review Agent 详细设计稿

> ⚠️ 本文档已被 [04-18 方向锚定文档](./2026-04-18-review-agent-direction-anchor.md) 取代。本文档中描述的部分技术方案（FocusWorkingMemory / ProjectRollingMemory / CompletedChunkMemorySummary / legacyFallback / allowedActions）已全部消除，仅作历史参考。

## 1. 设计目标

本稿只解决一件事：把 QuillLoom 的 `post-draft review agent` 从“受控状态机 + allowlist 枚举动作选择”升级为“LLM 自主决定下一步动作、执行层只做边界校验”的真正自主 agent。

本稿严格遵守以下锚定：

1. 不回退大 orchestrator，不引入多 agent 社会。
2. D 不联网，agent 仍只在本地 chunk、上下文、知识库范围内行动。
3. 不把运行期状态写回 `PostDraftReviewPackage` 或 `ProjectKnowledgeBase`。
4. 只保留 LLM 决策路径，没有真实 LLM 端口时显式报错。
5. `HistoryLog` 只做审计，不进入 prompt；prompt 可见历史只来自 `TranscriptStore`。

## 2. 方案对比

### 方案 A：保留现有状态机，只把动作枚举改成字符串

做法：

1. 保留 `PostDraftReviewLoopRunner` 的 `switch-case` 主循环。
2. 把 `ReviewAgentActionType enum` 改成字符串注册表。
3. 继续让代码先计算 allowed actions，再让 LLM 从中选。

优点：

1. 改动最小。
2. 迁移成本低。

缺点：

1. 本质上仍是“枚举选择器”，不是真自主。
2. 仍触碰 R-01、R-07 的核心问题。
3. 只是把 enum 换皮，不解决决策空间被代码预排的问题。

结论：不采用。

### 方案 B：单 agent 内核 + 动态工具注册表 + guardrail 校验

做法：

1. 由 LLM 直接输出下一步意图：要调用哪个工具、参数是什么、为什么。
2. 执行层只做注册表匹配、本地边界校验、执行、回写 session。
3. project loop 的 stop reason 由 agent 内部根据预算、无进展、人工介入条件自行决定。

优点：

1. 符合方向 C 的目标形态。
2. 能直接消除 `allowedActions`、`legacyFallback`、硬编码 enum、外部状态机四类核心问题。
3. 与 `claw-code` 的 `TranscriptStore + session store + stop reason` 结构最接近。

缺点：

1. 改动范围大。
2. 需要一次性重构 decision provider、memory、loop。

结论：采用。

### 方案 C：规划器和执行器拆成两个 LLM 子循环

做法：

1. 一个 LLM 负责规划下一步。
2. 另一个 LLM 负责校验/裁决是否执行。

优点：

1. 形式上更强控制。

缺点：

1. 超出当前单 agent 边界。
2. 复杂度高，且没有锚定要求。
3. 容易重新演化成上层 orchestrator。

结论：不采用。

## 3. 选定方案总览

采用方案 B，核心结构如下：

1. 外部入口仍保持 `PostDraftReviewAgentService.reviewProject(...)`。
2. 外部不再驱动 `INITIALIZING -> SELECTING_FOCUS -> INVESTIGATING -> ...` 状态机。
3. 新的 agent 内核 `AutonomousProjectReviewAgent` 持有统一运行态 session，自主循环：
   - 选定当前 focus anchor
   - 围绕 anchor 扩展当前 working set chunks
   - 读取 prompt 上下文
   - 让 LLM 输出下一步工具调用意图
   - guardrail 校验
   - 执行工具
   - 更新 session、budget、transcript、history
   - 判断 stop reason
4. 当 agent 决定“进入评估”“生成修订”“人工介入”“完成 working set”“完成 project”时，走对应的专用服务。

## 4. 组件拆解

### 4.1 新增组件

#### 运行态与记忆

1. `src/main/java/io/quillloom/application/postdraft/review/model/HistoryEvent.java`
2. `src/main/java/io/quillloom/application/postdraft/review/model/HistoryLog.java`
3. `src/main/java/io/quillloom/application/postdraft/review/model/TranscriptStore.java`
4. `src/main/java/io/quillloom/application/postdraft/review/model/UsageSummary.java`
5. `src/main/java/io/quillloom/application/postdraft/review/model/UsageBudget.java`
6. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentConfig.java`
7. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentStopReason.java`
8. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewEvidenceBundle.java`
9. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewVisitedObjects.java`
10. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolTrace.java`
11. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewWorkingSet.java`
12. `src/main/java/io/quillloom/application/postdraft/review/model/DeferredReviewIssue.java`
13. `src/main/java/io/quillloom/application/postdraft/review/model/ProjectIssueBacklog.java`

#### 动态动作体系

1. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDefinition.java`
2. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolCall.java`
3. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDecision.java`
4. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolExecutionResult.java`
5. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewGuardrailRejection.java`
6. `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
7. `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolGuardrail.java`
8. `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`

#### 自主 loop 与决策

1. `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
2. `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`
3. `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedStrategyEvaluationService.java`
4. `src/main/java/io/quillloom/application/postdraft/review/service/ReviewFocusSelector.java`
5. `src/main/java/io/quillloom/application/postdraft/review/service/ReviewProgressTracker.java`
6. `src/main/java/io/quillloom/application/postdraft/review/service/ReviewSessionCompactor.java`
7. `src/main/java/io/quillloom/application/postdraft/review/service/LlmBackedRevisionSelfCheckService.java`

#### session 持久化

1. `src/main/java/io/quillloom/application/postdraft/review/port/out/ReviewSessionStore.java`
2. `src/main/java/io/quillloom/application/postdraft/review/model/StoredReviewSession.java`
3. `src/main/java/io/quillloom/infrastructure/postdraft/review/FileReviewSessionStore.java`

### 4.2 重点修改组件

1. `PostDraftReviewSession`
   - 保留 immutable session 角色。
   - 删除旧的 `actionTrail / evidenceGaps / autonomyState` 主导地位。
   - 演进为统一运行态容器，新增：
     - `HistoryLog`
     - `TranscriptStore`
     - `UsageSummary`
     - `ReviewAgentConfig`
     - `ReviewEvidenceBundle`
     - `List<ReviewToolTrace>`
     - 当前 focus anchor
     - 当前 `ReviewWorkingSet`
     - project 级完成摘要
     - `ProjectIssueBacklog`

2. `ProjectReviewRuntimeSession`
   - 保留 project 级边界。
   - 移除：
     - `currentFocusRound`
     - `FocusWorkingMemory`
     - `ProjectRollingMemory`
     - `CompletedChunkMemorySummary`
   - 保留：
     - `pendingChunkIds`
     - `completedChunkOutcomes`
     - `currentFocusChunkId`
     - `currentFocusSession`
     - `openIssueBacklog`
     - `humanReviewRequest`
     - `processTrail`
     - `stopReason`

3. `PostDraftReviewAgentService`
   - 保留外部入口和 writer/human gateway 协作。
   - 内部改为组装 `AutonomousProjectReviewAgent`，不再自行 new 旧 loop runner。

4. `Prompt builders`
   - 保留 builder 框架。
   - 内容重写为：
     - 下一步决策 prompt
     - 策略评估 prompt
     - revision prompt
     - self-check prompt

### 4.3 直接删除或替换的组件

1. 删除 `ReviewAgentActionType enum`
2. 删除 `PostDraftReviewAllowedActionPlanner`
3. 删除 `PromptBackedInvestigationDecisionProvider` 的 `legacyFallback`
4. 删除 `PromptBackedEvaluationDecisionProvider` 的 `legacyFallback`
5. 删除 `maxLoopRounds`
6. 删除 `FocusWorkingMemory`
7. 删除 `ProjectRollingMemory`
8. 删除 `CompletedChunkMemorySummary`
9. 删除 `PostDraftReviewLoopRunner` 中的外部 `switch-case` 状态推进职责

## 5. 工具注册表设计

### 5.1 注册表结构

每个工具注册项包含：

1. `toolName`
2. `description`
3. `argumentSchema`
4. `executor`
5. `availabilityRule`
6. `guardrailRule`

### 5.2 首批注册工具

首批只注册当前已被锚定允许的本地工具，不扩展联网能力：

1. `read_previous_chunks`
2. `read_next_chunks`
3. `expand_block_context`
4. `read_decision_notes`
5. `read_transition_note`
6. `lookup_knowledge_cards`
7. `evaluate_focus`
8. `draft_revision`
9. `request_human_review`
10. `complete_working_set`
11. `complete_project`

说明：

1. 这些是注册表中的字符串，不再由编译期 enum 锁死调用入口。
2. LLM 可以输出任意工具名，但只有注册表中存在且 guardrail 通过的工具才能执行。
3. `complete_working_set` 允许一次提交多个 chunk 的完成确认，但正式产物仍逐 chunk 生成 `ProjectChunkReviewOutcome`。

### 5.3 guardrail 规则

guardrail 只做边界检查，不做“下一步该做什么”的预规划。规则包括：

1. 工具名必须已注册。
2. 参数必须满足 schema。
3. 只允许读取当前 project 的 `PostDraftReviewPackage`、`ProjectKnowledgeBase` 及其派生只读上下文。
4. 不允许联网、不允许访问未注册资源。
5. 不允许把运行态 memory 回写正式数据源。

若 guardrail 拒绝：

1. 将 rejection 记入 `HistoryLog`
2. 将 rejection 摘要记入 `TranscriptStore`
3. 给 LLM 一次显式修正机会
4. 再次越界则 stop reason 置为 `HUMAN_REVIEW_REQUIRED`

### 5.4 工具扩展位设计

本设计在当前版本中不放开联网，但预留“未来工具扩展位”，用于后续增量接入新的 agent 动作。

扩展位由三层组成：

1. `ReviewToolDefinition`
   - 描述工具名、用途、参数 schema、executor、guardrail rule
2. `ReviewToolRegistry`
   - 负责集中注册所有可执行工具
3. `ToolCapabilityPolicy`
   - 负责声明当前运行环境允许哪些能力类型

建议把工具能力分成 3 类：

1. `LOCAL_READ`
   - 本地只读工具
2. `LOCAL_WRITE`
   - 本地受控写入工具
3. `EXTERNAL_FETCH`
   - 外部检索/联网获取工具

当前方向 C 默认策略：

1. 仅开启 `LOCAL_READ`
2. `LOCAL_WRITE` 只允许 session store 这类运行态落盘，不允许改正式数据源
3. `EXTERNAL_FETCH` 全部禁用

后续如果要扩展 agent 动作，不需要再改核心 loop，只需要：

1. 新增 `ReviewToolDefinition`
2. 注册到 `ReviewToolRegistry`
3. 为新工具声明能力类型
4. 在 `ToolCapabilityPolicy` 中显式放开该能力
5. 补 guardrail 和 prompt 暴露

这意味着：

1. 现在不加联网搜索，不会把未来扩展堵死
2. 未来即使新增联网工具，也不需要重新把动作体系改回 enum 或 allowlist
3. 是否允许联网，不由 LLM 自行决定，而由 capability policy 和 guardrail 显式控制

### 5.5 未来联网工具的受控接入方式

若未来用户修改锚定，允许 post-draft review agent 做受控联网搜索，建议按以下方式接入：

1. 新增单独 port：
   - `ReviewExternalSearchPort`

2. 新增单独工具定义：
   - `web_search_reference`
   - 或更窄的 `search_reference_whitelist`

3. 给该工具单独设置 guardrail：
   - 必须显式开启 `EXTERNAL_FETCH`
   - 必须记录 query、来源、摘要
   - 必须限制域名白名单或来源集合
   - 结果只能作为证据补充，不直接改正式知识库

4. prompt 层只暴露“受控联网检索”语义，不暴露任意浏览器/任意 shell 能力

5. 审计层必须追加：
   - 检索词
   - 命中来源
   - 引用摘要
   - 是否被 guardrail 拒绝

本节的作用只是预留扩展位，不改变当前边界。当前版本的结论仍然是：review agent 只允许本地工具，不允许联网。

## 6. Session 与记忆体系

### 6.1 多层结构

#### `HistoryLog`

用途：

1. 记录结构化事件
2. 审计和诊断

边界：

1. 不参与 prompt 构建
2. 不做数据源副本

记录内容包括：

1. focus 切换
2. 工具选择
3. guardrail 拒绝
4. revision/self-check 结果
5. stop reason

#### `TranscriptStore`

用途：

1. 保存 LLM 可见的对话上下文
2. 为下一轮 prompt 提供 replay

规则：

1. 每轮记录 prompt 摘要和模型结构化结果摘要
2. 超过 `compactAfterEntries` 后保留最近 N 条
3. prompt 注入只用 `transcriptStore.replay()`

#### `ReviewSessionStore`

用途：

1. 将 project 级 session 序列化到本地文件
2. 支持断点恢复和人工介入后恢复

路径：

1. `.quillloom_sessions/{projectId}.json`

#### `ReviewWorkingSet`

用途：

1. 表示当前围绕某个 focus anchor 正在被共同处理的一组 chunk。
2. 允许 agent 在一次工作周期里同时阅读、修订、确认多个 chunk。

边界：

1. working set 的起点来自 focus anchor，但不等于单 chunk。
2. working set 内 chunk 必须属于当前 project 的正式数据源。
3. working set 是运行态对象，不改变正式产出仍按 chunk 落地的边界。

#### `ProjectIssueBacklog`

用途：

1. 暂存“当前发现但暂时不急着转人工、也暂时无法解决”的全局问题。
2. 允许 agent 在后续进入其他上下文后再次领取、求解、关闭。

当前设计定位：

1. 本次重构先把 backlog 作为运行态结构和接口预留好。
2. backlog 的自动重开、自动再调度可以后续再实现。

### 6.2 预算与停机

agent 不再依赖 `maxLoopRounds`。

stop reason 仅由以下条件触发：

1. `PROJECT_COMPLETED`
2. `WORKING_SET_COMPLETED`
3. `HUMAN_REVIEW_REQUIRED`
4. `MAX_BUDGET_REACHED`
5. `NO_PROGRESS`
6. `LLM_PORT_MISSING`
7. `FAILED`

预算控制采用：

1. `UsageBudget.maxTokens`
2. `TranscriptStore.compactAfterEntries`
3. 结构化解析重试上限
4. self-check 本地重试 1 次

说明：

1. `NO_PROGRESS` 不是轮次上限，而是“连续两次同类动作未新增证据或持续被 guardrail 拒绝”的显式诊断停机。

## 7. LLM 决策与输出格式

### 7.1 下一步工具决策

`PromptBackedNextStepDecisionProvider` 输出结构化 JSON：

```json
{
  "toolName": "read_previous_chunks",
  "arguments": {
    "count": 2
  },
  "reason": "需要补齐前文衔接证据",
  "expectedEvidence": [
    "确认称谓是否沿用",
    "确认场景切换是否连续"
  ]
}
```

处理规则：

1. `toolName` 不存在于注册表时，guardrail 拒绝。
2. 参数不合法时，guardrail 拒绝。
3. 不存在“没有 LLM 时走手写 heuristics”的分支。

### 7.2 策略评估

`PromptBackedStrategyEvaluationService` 输出：

```json
{
  "strategy": "LIGHT_EDIT",
  "reason": "证据已足够，问题集中在用词和衔接微调",
  "evidenceSufficiency": "SUFFICIENT",
  "continueInvestigation": false
}
```

说明：

1. 评估只在 agent 决定调用 `evaluate_focus` 后发生。
2. `candidateStrategies` 可以保留为 guardrail 校验集合，但不能先收窄“读什么、做什么”的决策空间。

### 7.3 Self-check

`LlmBackedRevisionSelfCheckService` 输出：

```json
{
  "passed": false,
  "stopReason": "new_inconsistency_detected",
  "findings": [
    "修订版引入了人名称谓不一致",
    "decision note 的未决点仍未被解决"
  ]
}
```

处理规则：

1. 第一次失败：允许本地 revision 再试一次。
2. 第二次仍失败：交由 `FocusHumanStopPolicy` 判定人工介入。
3. 不允许任何 stub 或硬编码 `passed=true`。

## 8. 数据流

### 8.1 Project 启动

1. `PostDraftReviewAgentService.reviewProject(projectId, operatorNote)` 被调用。
2. `PostDraftReviewAgentReader` 读取 `PostDraftReviewPackage` 与知识库快照。
3. `PostDraftReviewSessionFactory` 初始化 `ProjectReviewRuntimeSession` 和 `PostDraftReviewSession`。
4. 若未注入真实 `ReviewAgentStructuredGenerationPort`，立即报错并结束，不进入 loop。

### 8.2 Agent 主循环

1. `ReviewFocusSelector` 从 `pendingChunkIds` 或 backlog 关联上下文中选出当前 focus anchor。
2. agent 基于当前 anchor 扩展 `ReviewWorkingSet`，working set 可包含多个 chunk。
3. `PromptBackedNextStepDecisionProvider` 读取以下 prompt 输入：
   - 当前 focus anchor
   - 当前 working set chunks
   - `TranscriptStore.replay()`
   - 当前证据包
   - 已访问对象
   - 已执行工具轨迹
   - 当前未关闭问题摘要
   - 当前 project 完成摘要
4. LLM 输出下一步工具调用。
5. `ReviewToolGuardrail` 校验。
6. `ReviewToolExecutor` 执行并返回：
   - 新证据
   - 新 visited objects
   - 工具结果摘要
   - 可能扩展或收缩 working set
   - 可能新增 backlog issue
7. 更新 session：
   - `HistoryLog.add(...)`
   - `TranscriptStore.append(...)`
   - `UsageSummary.addTurn(...)`
   - `ReviewEvidenceBundle.merge(...)`
   - `ProjectIssueBacklog.merge(...)`
8. `ReviewProgressTracker` 判断：
   - 是否进入评估
   - 是否无进展
   - 是否预算耗尽
   - 是否完成 working set / project

### 8.3 进入 revision

1. agent 调用 `evaluate_focus`
2. `PromptBackedStrategyEvaluationService` 产出策略
3. 若策略是 `KEEP`：
   - 当前 working set 中达到完成条件的 chunk 可直接收口
4. 若策略是 `LIGHT_EDIT / DEEP_EDIT / RETRANSLATE`：
   - `PostDraftRevisionService` 生成修订
   - `LlmBackedRevisionSelfCheckService` 校验
   - 失败重试一次
   - 仍失败则人工介入
5. 若策略是 `REQUIRE_HUMAN_REVIEW`：
   - 直接产出 `HumanReviewRequest`

### 8.4 Working set 完成与 project 持久化

1. agent 调用 `complete_working_set`，参数中显式列出本次确认完成的 chunk 列表。
2. completion handler 基于当前 session 中已确认的结果，为列表中的每个 chunk 分别生成 `ProjectChunkReviewOutcome`。
3. 已完成 chunk 从 `pendingChunkIds` 中移除。
4. 将 chunk outcomes 写入 `ProjectReviewRuntimeSession.completedChunkOutcomes`。
5. 将 project 级 session 保存到 `ReviewSessionStore`。
6. 若仍有待处理 chunk 或 backlog 中仍有开放问题，则继续下一轮选择。
7. 仅当 `pendingChunkIds` 为空，且 backlog 中没有未关闭问题时，才允许 `complete_project`，并置 `PROJECT_COMPLETED`。

## 9. 与现有保留资产的对接方式

### 9.1 继续保留

1. `PostDraftReviewPackage` + `ProjectKnowledgeBase`
   - 仍是唯一正式数据源

2. `PostDraftReviewAgentReader`
   - 继续承担只读导航与上下文提取

3. `PostDraftReviewPackageAssembler`
   - 不改动

4. `FocusHumanStopPolicy`
   - 继续作为人工介入的确定性裁决器

5. `PostDraftRevisionService`
   - 保留 revision draft 生成职责

6. `HumanReviewRequest` / `HumanReviewResolution`
   - 语义不变

7. Prompt builder 框架
   - 保留类边界，只重写内容和输入

### 9.2 对接原则

1. 所有读取仍经由 `reader`，不从 memory 直接读正文。
2. memory 只记录行动历史、证据摘要、transcript，不缓存为第二份正式语料副本。
3. `ProjectChunkReviewOutcome` 仍是当前 chunk 正式完成产物。
4. 运行期 session 落本地 session store，不回写 PostgreSQL 正式流水线表。
5. 即使 agent 在 working set 中一次处理多个 chunk，正式完成产物仍逐 chunk 落地，不引入 project 级“大批次 outcome”替代它。

## 10. 红线自检

### R-01

是否触碰：否

原因：

1. 不再存在 `allowedActions` 参与 `InvestigationDecisionProvider` 或 `EvaluationDecisionProvider` 的决策收窄。
2. 决策来自 LLM 自主工具选择，guardrail 只验边界。

### R-02

是否触碰：否

原因：

1. 明确删除所有 `legacyFallback` 分支。
2. 没有真实 LLM 端口时 fail fast。

### R-03

是否触碰：否

原因：

1. Self-check 改为独立 LLM 服务。
2. 只允许真实结构化结果，禁止 stub `true`。

### R-04

是否触碰：否

原因：

1. `ReviewAgentActionType enum` 被删除。
2. 动作入口改为 `ReviewToolRegistry` 的动态字符串注册。

### R-05

是否触碰：否

原因：

1. 删除 `maxLoopRounds`。
2. loop 中断依据 stop reason、budget、no-progress、human policy，而不是轮次。

### R-06

是否触碰：否

原因：

1. 所有运行态 memory 仅存在于 session 和本地 session store。
2. 不写回 `PostDraftReviewPackage` 或 `ProjectKnowledgeBase`。

### R-07

是否触碰：否

原因：

1. 外部 `switch-case` 状态推进被移除。
2. 外部仅负责启动 agent、接收 stop reason 和结果。

### R-08

是否触碰：否

原因：

1. 无 LLM 时不再手写 heuristic fallback。
2. 没有端口即显式报错并停止。

## 11. 本稿明确拍板的设计结论

1. 采用“单 agent 内核 + 动态工具注册表 + guardrail”的方案，不保留 allowlist 选动作模型。
2. `HistoryLog` 只做审计，不进 prompt。
3. `TranscriptStore` 是唯一 prompt 可见历史来源。
4. `ReviewAgentActionType`、`legacyFallback`、`maxLoopRounds`、旧 memory 三件套全部移除。
5. 外部入口仍保留 `reviewProject(...)`，但 loop 控制权收回 agent 内核。
6. agent 的调度起点是 focus anchor，不是被限定死的单 chunk 工作单元；agent 可以在一个 working set 中同时完成多个 chunk。
7. `ProjectIssueBacklog` 作为全局问题列表被纳入设计，先预留模型和调度接口，后续再实现自动回访。

## 12. 待实现阶段回答的问题

以下不是设计未定，而是实现时必须按本稿具体落地：

1. 各工具参数 schema 的 Java 表达形式
2. `UsageSummary` 的 token 估算实现
3. `FileReviewSessionStore` 的 JSON 序列化结构
4. `RevisionSelfCheckPromptBuilder` 的具体 prompt 文案
5. `ReviewProgressTracker` 的“无进展”判定细则
6. `ProjectIssueBacklog` 的 reopen / close / reprioritize 规则
