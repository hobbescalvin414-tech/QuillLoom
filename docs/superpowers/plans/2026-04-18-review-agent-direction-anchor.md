# Review Agent 方向锚定文档

> 本文档是 Review Agent 的总领性方向文件。所有后续设计、实现、重构都必须在本文档约束下展开。
>
> 本文档取代 `2026-04-16-direction-c-true-autonomous-agent-anchor.md` 作为方向锚定。04-16 文档中已落地的红线归档至本文档 §5.1，仍有效的红线归档至本文档 §5.2。

---

## 1. 当前定位

### Review Agent 是什么

1. 一个**自主 agent**——LLM 自主决定下一步动作（读什么、做什么），执行层只做边界校验。覆盖审校、精修、重译、未译补全、衔接修整、逻辑检查全流程。
2. 受控流水线中的**自治节点**——不是 agent 社会，不引入多 agent 协调层。
3. **本地优先 agent**——优先在本地 chunk、上下文、知识库范围内行动；联网搜索作为受控扩展工具，按需启用。
4. **项目级 agent**——以 `projectId` 为锚点，在统一 loop 中顺序处理多个 chunk。
5. **求助式 HITL agent**——遇到不确定的情况主动问人；人工回答作为证据写回后，可从 `WAITING_HUMAN` 正常暂停点恢复继续。

### Review Agent 不是什么

1. 不是受控状态机——不存在外部 `INITIALIZING → SELECTING_FOCUS → INVESTIGATING → ...` 的 switch-case 推进。
2. 不是枚举选择器——不存在 `allowedActions` 预收窄 LLM 决策空间。
3. 不是排障式 HITL——不会"卡死等人来排障"，而是"主动问人等回答"。
4. 不是自由联网器——联网搜索必须通过受控工具接口接入，不允许 LLM 自由访问网络。
5. 不是大 orchestrator——不回退到统一调度层，不接管 A/B/C0/D 的职责。
6. 不是知识库写手——不回写 `PostDraftReviewPackage`、`ProjectKnowledgeBase`、`confirmedTerms` 的稳定契约（`record_confirmed_terms` 是受控写回，不是自由写库）。

---

## 2. 已完成的方向性决策

以下决策已在代码中落地，后续不得回退。

### D-01：单 agent 内核 + guardrail（工具注册表部分落地，执行层待解耦）

**决策**：agent 由 LLM 自主决定下一步工具调用，执行层只做注册表匹配、边界校验、执行、回写 session。

**已落地部分**：
1. **单 agent 内核**：`AutonomousProjectReviewAgent` 的 `while(true)` 循环，LLM 自主决定下一步动作
2. **ReviewToolRegistry**：注册了 13 个 `ReviewToolDefinition`（含描述、必填参数、参数 Schema）
3. **ReviewToolGuardrail**：校验工具名是否在注册表中、必填参数是否存在
4. **消除旧架构**：`allowedActions`、`legacyFallback`、`ReviewAgentActionType`、`maxLoopRounds` 已全部消除

**未落地部分**：
1. **执行层仍是 switch-case**：`ReviewToolExecutor`（653 行）用 switch 表达式分发 13 个工具调用，所有工具逻辑内联在私有方法中
2. **ReviewTool 接口不存在**：无法通过实现接口自动接入执行流
3. **新增工具需改 2-3 个文件**：ReviewToolExecutor（加 case + 加方法）+ ReviewToolRegistry（加定义）+ ReviewToolDecisionContractValidator（加校验，如需）
4. **investigationArgumentsSchema() 仍是硬编码**：未从 Registry 动态生成

**为什么**：04-16 锚定文档论证了"弱状态机 + allowlist 动作选择"的四类核心问题（allowedAction 锁死决策空间、legacyFallback 双轨、假 self-check、硬编码 enum）。单 agent 内核已根治这四类问题，但工具执行层的解耦尚未完成（见 D-08）。

**违反后果**：回退到 allowlist/状态机会导致 LLM 决策空间被代码预排，agent 无法自主判断"我需要读决策笔记"这类非预设动作。

**验证**：`grep -n "allowedAction\|legacyFallback\|enum ReviewAgentActionType\|maxLoopRounds" src/` 应返回 0 结果。

### D-02：HistoryLog 只做审计，不进 prompt

**决策**：`HistoryLog` 记录结构化事件（focus 切换、工具选择、guardrail 拒绝等），只追加不压缩，不参与 prompt 构建。prompt 可见历史只来自 `TranscriptStore`。

**为什么**：审计轨迹和 LLM 上下文是两个不同职责。审计需要完整不丢，LLM 上下文需要压缩可控。混在一起会导致审计信息被压缩丢失，或 LLM 上下文因审计信息膨胀。

**违反后果**：把 HistoryLog 塞进 prompt 会导致上下文无限膨胀（HistoryLog 不压缩），或审计信息因压缩而丢失。

### D-03：Focus anchor + working set 模型

**决策**：agent 从单个 anchor 出发，可扩展到多个 chunk（working set）。`complete_working_set` 允许一次提交多个 chunk 的完成确认。正式结果仍逐 chunk 产出 `ProjectChunkReviewOutcome`。

**为什么**：小说翻译的衔接问题经常跨 chunk 边界。单 chunk 工作单元无法处理"chunk-3 和 chunk-5 对 festival 译法不一致"这类跨 chunk 问题。

**违反后果**：回退到单 chunk 工作单元会导致跨 chunk 衔接问题无法被自主发现和修复。

### D-04：结构化输出三层防御

**决策**：JSON Schema 约束 + repair retry + 运行时容错。LLM 输出不符合预期时，先 repair 再容错，不直接崩。

**为什么**：LLM 输出格式不稳定是常态（如 `queryTerms` 返回字符串而非数组）。直接崩会导致 agent 在 130+ chunk 长跑中几乎必然失败。

**违反后果**：去掉任何一层防御都会增加 agent 崩溃概率。只靠 JSON Schema 不够（模型不总是遵守），只靠容错不够（掩盖问题），只靠 repair 不够（repair 本身也可能失败）。

### D-05：System Prompt 分离

**决策**：角色定义、核心约束、工具使用规则放在 system prompt（每轮不变），动态事实放在 user prompt（每轮变化）。

**为什么**：每轮重复发送不变的规则浪费 token，且 LLM 没有稳定的"身份感"容易在长对话中漂移。

**违反后果**：回退到全塞 user message 会导致 token 浪费和 LLM 行为漂移。

### D-06：Per-tool 参数 Schema

**决策**：每个工具注册 `ToolArgumentSchema`，在 system prompt 和 repair prompt 中动态渲染参数要求。

**为什么**：`complete_working_set` 缺 `chunkIds` 的死循环根因是所有工具共用宽松 schema，LLM 可以合法输出无参调用。

**违反后果**：回退到宽松 schema 会导致参数缺失死循环重现。

---

## 3. 待实现的方向性决策

以下决策已在对话中确认，但尚未在代码中完整落地。实现时必须按此方向，不得走偏。

### D-07：求助式 HITL（Codex 风格）

**目标**：agent 遇到不确定的情况主动问人；当运行态进入 `WAITING_HUMAN` 时，将完整 `ProjectReviewRuntimeSession` 落盘，后续喂入人工自由文本后从该暂停点恢复继续。

**当前**：`request_human_review` 虽能把 runtime 置为 `WAITING_HUMAN`，但 `HumanInTheLoopGateway` 仍只是 request 发布口；完整 session 持久化和 `resumeProject(...)` 恢复入口尚未正式接通。`resume()` 方法只存在于 `AutonomousProjectReviewAgent` 内部，上层 service 没有正式恢复链路。

**禁止的中间态**：
- 不允许把 HITL 做成"排障式"——agent 停机等人排障，人诊断后手动恢复
- 不允许把 `HumanInTheLoopGateway` 做成阻塞等待人工回答的恢复入口
- 不允许把 `NO_PROGRESS` 伪装成正常 HITL 暂停点

**实现要点**：
1. `HumanInTheLoopGateway.submit()` 只负责提交/发布人工求助请求，不负责等待人工回答
2. agent 主动选择 `request_human_review` 后，runtime 进入 `WAITING_HUMAN`
3. `WAITING_HUMAN` 是唯一允许完整 session 落盘和后续恢复的正常暂停点
4. 恢复入口收敛到 `PostDraftReviewAgentService.resumeProject(projectId, humanReviewNote)`
5. 人工回答只作为证据写入 transcript/history，不是命令；agent 自主决定下一步

**为什么**：用户明确要求"我要的不是停机卡死，我想要的人工介入是像 Codex 一样的询问人建议而不是一个自主 agent 要人来排障"。同时用户进一步确认：人工回答是证据，不是命令；`NO_PROGRESS` 是 bug 暴露，不是正常求助。

### D-08：工具系统解耦

**目标**：加一个工具只需 1 个文件——一个实现了 `ReviewTool` 接口的类，自动注册、自动校验、自动执行。

**当前**：`ReviewToolExecutor` 是 653 行 switch 表达式，新增工具需改 2-3 个文件：ReviewToolExecutor（加 case + 加方法）+ ReviewToolRegistry（加定义）+ ReviewToolDecisionContractValidator（加校验，如需）。ReviewToolGuardrail 和 PromptBuilder 是通用的，不需要改。

**禁止的中间态**：
- 不允许继续往 `ReviewToolExecutor` 加 switch case
- 不允许新工具的参数校验散落在 `ReviewToolDecisionContractValidator` 里
- 不允许 `investigationArgumentsSchema()` 继续硬编码每个工具的参数

**实现要点**：
1. 定义 `ReviewTool` 接口：`definition()` + `execute(context, call)` + `validateArguments(call)`
2. 每个工具一个实现类（`ReadPreviousChunksTool`、`CompleteWorkingSetTool` 等）
3. `ReviewToolExecutor` 改为分发器，从 Registry 查找 ReviewTool 实现并委托
4. `investigationArgumentsSchema()` 改为从 Registry 动态生成
5. Spring 自动发现所有 ReviewTool 实现

**为什么**：用户明确要求"我要一个比较容易扩展的工具系统，以实现后续对 agent 功能的灵活扩展"。当前 5 文件改动的耦合度使得加工具成本极高，且容易遗漏。

### D-09：结构化压缩摘要

**目标**：从 session 各记忆项提取关键信息，生成结构化摘要。

**当前**：`buildCompactSummary()` 硬拼 4 个字段（轮次、策略、已读 chunk、关键发现），丢失矛盾证据、guardrail 拒绝历史、工具调用统计、策略变化历史等。

**禁止的中间态**：
- 不允许继续硬拼 4 个字段
- 不允许用 LLM 调用生成摘要（压缩是每轮都可能触发的，不能每轮多一次 LLM 调用）

**实现要点**：
1. 从 `transcriptStore.replay().size()` 提取轮次
2. 从 `session.toolTraces()` 统计已用工具
3. 从 `session.evidenceBundle()` 提取矛盾证据和已确认术语
4. 从 `session.diagnostics().localRejectionReasons()` 提取 guardrail 拒绝
5. 拼成多行结构化文本，不需要 LLM 调用

**为什么**：用户明确指出"代码硬拼摘要怎么压缩？"。硬拼 4 字段在压缩后丢失大量上下文，agent 恢复后无法知道之前被 guardrail 拒绝过什么、策略变化过几次、哪些术语已确认。

### D-10：修订译文写回数据库

**目标**：agent 跑完后修订的译文写入 `ql_post_draft_review_package`。

**当前**：`PassThroughPostDraftReviewAgentWriter` 只透传，不写库。

**禁止的中间态**：
- 不允许 agent 跑完但修订译文只存在于内存/方法返回值中

**实现要点**：
1. 新增 `PostgresPostDraftReviewAgentWriter`
2. `complete_working_set` 完成时，每个 chunk 的 `finalTranslation` 更新到 `chunks_json`
3. 项目完成时，`mergedDraftText` 写入 `merged_draft_text` 字段

**为什么**：没有这个，agent 跑完也白跑——修订后的译文进程结束就没了。

### D-11：Session 持久化可恢复

**目标**：`StoredReviewSession` 保存完整的 `ProjectReviewRuntimeSession`，从磁盘恢复后能完整继续。

**当前**：`StoredReviewSession` 是精简快照，丢失 `currentFocusSession`、`completedChunkOutcomes` 完整内容、`humanReviewRequest` 等关键信息。

**禁止的中间态**：
- 不允许只存 chunkId 不存 finalTranslation/strategy/processSummary
- 不允许丢掉 humanReviewRequest

**实现要点**：
1. `StoredReviewSession` 保存完整的 `ProjectReviewRuntimeSession` JSON
2. `ReviewSessionStore.load()` 反序列化后能完整恢复
3. 建议选方案 A（完整序列化），不选方案 B（从数据库重建）

**为什么**：HITL 暂停必须能从断点恢复（这是正常工作流，不是 bug）。崩溃恢复在稳定期也需要。

### D-12：崩溃恢复分阶段

**目标**：开发期异常崩溃从头来，稳定期崩溃从上一完成的焦点恢复。

**当前**：崩溃后所有状态丢失。

**实现要点**：
1. 开发期：不恢复可能脏掉的状态，修 bug 后重新跑
2. 稳定期：靠 D-10（逐 focus 写库）+ D-11（session 持久化），崩了只丢当前 focus，从上一个完成的 focus 恢复

**为什么**：用户明确要求"开发阶段只有 HITL 能中断恢复，但是异常卡爆了链路就修 bug 之后重新来过"和"后面稳定之后还是要做到崩溃了也能恢复"。

### D-13：流式输出暂不做但架构预留

**目标**：暂不实现 SSE/数据库可视化，但 `ReviewRuntimeVisualizer` 接口已预留流式注入点。

**当前**：`ConsoleReviewRuntimeVisualizer` 输出到 stdout，接口已抽象 5 个事件方法。

**禁止的中间态**：
- 不允许把可视化逻辑硬编码到 agent loop 里
- 不允许 ReviewRuntimeVisualizer 的事件方法签名阻碍后续改为异步

**为什么**：用户确认"先暂时不用做，但是要提前有意识地给流式输出留位置"。

### D-14：受控联网搜索扩展

**目标**：agent 可通过受控工具接口进行联网搜索，用于查证术语、文化背景、历史事实等本地知识库无法覆盖的信息。

**当前**：无联网能力。`lookup_knowledge_cards` 只搜索本地知识库（含向量检索）。

**禁止的中间态**：
- 不允许 LLM 自由访问网络——必须通过注册的工具接口，输入输出受 guardrail 校验
- 不允许引入 browser / shell / code execution 能力——只做搜索，不做通用网络操作
- 不允许联网搜索绕过 guardrail——搜索结果必须经过与本地工具相同的边界校验

**实现要点**：
1. 新增 `external_search` 工具（实现 ReviewTool 接口，D-08 解耦后自动注册）
2. 工具输入：`query`（搜索词）、`searchType`（web / reference / fact-check）
3. 工具输出：结构化搜索结果（来源、摘要、置信度），不是原始网页
4. 通过独立 port 接入（`ExternalSearchPort`），底层可对接 SearXNG / Tavily / 自定义 API
5. guardrail 校验：搜索频率限制、结果数量限制、来源可信度过滤
6. capability policy 控制：可按项目/配置启用或禁用联网搜索

**为什么**：用户明确要求"要联网！后续要扩展这个工具的"。审校场景中经常需要查证本地知识库没有的术语、文化背景、历史事实。但联网必须是受控的——搜索是工具，不是自由能力。

---

## 4. 架构边界约束

### B-01：不回退大 orchestrator

仍是单一 agent，不引入多 agent 协调层、agent 间通信、agent 生命周期管理。

### B-02：不回退 A/B/C0

主翻译工作流不受 Review Agent 影响。Review Agent 只读 `PostDraftReviewPackage` 和 `ProjectKnowledgeBase`。

### B-03：联网搜索必须受控

Review Agent 优先在本地 chunk + 上下文 + knowledge base 范围内行动。联网搜索作为受控扩展工具，必须满足：
1. 通过注册的工具接口（`external_search`）接入，不允许 LLM 自由访问网络
2. 通过独立 port 接入（`ExternalSearchPort`），底层实现可替换
3. 搜索结果经过 guardrail 校验（频率限制、数量限制、来源过滤）
4. capability policy 控制：可按项目/配置启用或禁用
5. 只做搜索，不引入 browser / shell / code execution 能力

### B-04：不把运行期状态塞回稳定领域对象

Memory 仍是运行期对象，不回写 `PostDraftReviewPackage` / `ProjectKnowledgeBase` / `TranslationTaskInput`。

例外：`record_confirmed_terms` 是受控写回，只允许追加或同值确认，冲突时显式拒绝。这不是"自由写库"，而是"受控术语登记"。

### B-05：不破坏 confirmed/candidate/alias/knowledge card 边界

Agent 只读不写这些稳定契约（B-04 例外除外）。不允许新发明一套与现有体系冲突的新体系。

### B-06：受控流水线中的自治节点

当前系统仍是受控流水线，Review Agent 是其中的自治节点。不是自治 agent 社会。

---

## 5. 红线规则

### 5.1 已落地的红线（来自 04-16 锚定文档，代码中已消除）

以下红线在代码中已不存在违反风险，归档备查：

| 编号 | 禁止行为 | 当前状态 |
|------|---------|---------|
| R-01 | 在 InvestigationDecisionProvider / EvaluationDecisionProvider 里用 allowedActions 过滤 LLM 决策空间 | ✅ 已消除 |
| R-02 | 出现任何 legacyFallback 相关逻辑 | ✅ 已消除 |
| R-03 | Self-check 返回硬编码 true 或永远 passed | ✅ 已消除 |
| R-04 | 用 enum ReviewAgentActionType 作为动作生成的唯一入口 | ✅ 已消除 |
| R-05 | 用 maxLoopRounds 或轮次硬编码作为 loop 中断机制 | ✅ 已消除 |
| R-07 | 在 Loop 控制层使用外部状态机驱动 agent 决策 | ✅ 已消除 |
| R-08 | 绕过 LLM 调用走手写启发式决策 | ✅ 已消除 |

### 5.2 仍有效的红线

| 编号 | 禁止行为 | 验证方法 |
|------|---------|---------|
| R-06 | 把运行期状态写回 PostDraftReviewPackage 或 ProjectKnowledgeBase | 代码审查检查写操作路径 |
| R-09 | 把 HITL 做成排障式（agent 卡死等人排障，人诊断后手动恢复） | 代码审查：只有 agent 主动选择 `request_human_review` 才能进入 `WAITING_HUMAN`；恢复入口必须在 service 层而不是人工排障 |
| R-10 | 把 `NO_PROGRESS` 伪装成正常 HITL 暂停或可恢复人工求助路径 | 代码审查：`NO_PROGRESS` 必须保持 FAILED；不得生成 `WAITING_HUMAN` session；不得落可恢复 session 文件 |
| R-11 | 继续往 ReviewToolExecutor 加 switch case 而不先做工具解耦 | 代码审查：新工具必须实现 ReviewTool 接口 |
| R-12 | 压缩摘要硬拼 4 个字段 | 代码审查：buildCompactSummary 从 session 各记忆项提取信息 |
| R-13 | 让 LLM 自由访问网络（绕过受控工具接口的联网搜索） | 代码审查：联网搜索必须通过 external_search 工具 + ExternalSearchPort + guardrail |
| R-14 | 把 loop 临时状态塞回 TranslationTaskInput 或其他稳定执行输入契约 | 代码审查：TranslationTaskInput 不承载巨型运行态 |

---

## 6. 记忆体系锚定

Review Agent 的记忆由 6 个独立组件构成，各自职责清晰：

| 组件 | 进 prompt | 可压缩 | 用途 |
|------|----------|-------|------|
| TranscriptStore | ✅ | ✅ | LLM 可见的对话上下文，超阈值时保留最近 N 条 + prepend 摘要 |
| HistoryLog | ❌ | ❌ | 结构化事件日志，只追加不淘汰，审计用 |
| ReviewEvidenceBundle | ✅ | ✅ | 结构化证据包（5 个列表），超阈值时保留最近 N 条 + prepend 摘要 |
| ReviewVisitedObjects | ✅ | ❌ | 已访问对象记录 |
| ReviewToolTrace | ✅ | ❌ | 工具调用轨迹 |
| FocusReviewDiagnostics | ✅ | ❌ | 循环/错误诊断（localRejectionReasons 等） |

**压缩触发时机**：每轮 submit 结束后，检查 transcript 和 evidence 是否超阈值，独立触发。

**压缩策略**：保留最近 N 条 + 在头部插入结构化摘要（D-09）。不用 LLM 调用生成摘要。

**HistoryLog 不压缩的原因**：审计需要完整不丢。如果压缩了，就无法追溯"agent 为什么做了这个决策"。

---

## 7. 与 04-16 锚定文档的关系

`2026-04-16-direction-c-true-autonomous-agent-anchor.md` 是方向 C 的原始锚定文档。本文档取代其作为方向锚定的角色：

| 04-16 内容 | 在本文档中的位置 |
|-----------|----------------|
| §2 当前真实问题（4 个） | 已全部落地，归档至 §5.1 |
| §3 目标形态 | 已落地，归档至 §2（D-01~D-06） |
| §4 关键设计锚点 | 已落地，归档至 §2（D-01~D-06） |
| §5 与现有实现的关系 | 仍有效，归档至 §4（B-01~B-06） |
| §6 架构边界约束 | 仍有效，归档至 §4（B-01~B-06） |
| §8 持久化方案 | 仍有效，保留在 04-16 文档中作为数据基座参考 |
| R-01~R-08 红线 | 已消除的归档至 §5.1，仍有效的归档至 §5.2 |

04-16 文档不删除，作为历史参考。但新会话应以本文档为准。

---

## 8. 相关文档

- 产品定义：[2026-04-18-review-agent-product-definition.md](./2026-04-18-review-agent-product-definition.md)
- 项目现状：[docs/current-architecture.md](../../current-architecture.md)
- 当前状态：[docs/current-status.md](../../current-status.md)
- 差距分析：[2026-04-18-review-agent-e2e-run-gap-analysis.md](./2026-04-18-review-agent-e2e-run-gap-analysis.md)
- 加固计划：[2026-04-18-review-agent-e2e-hardening-plan.md](./2026-04-18-review-agent-e2e-hardening-plan.md)
- 方向 C 原始锚定（已被本文档取代）：[2026-04-16-direction-c-true-autonomous-agent-anchor.md](./2026-04-16-direction-c-true-autonomous-agent-anchor.md)
- Workspace 规则：[AGENTS.md](../../../AGENTS.md)
