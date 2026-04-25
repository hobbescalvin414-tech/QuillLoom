# 2026-04-18 Review Agent 端到端跑通改进计划

## 目标

让 post-draft review agent 能完整跑完一本 130+ chunk 的小说初稿审校，不中途崩溃、不无限循环、不撑爆 prompt。

## 当前状态（2026-04-18 更新）

agent 核心链路已通，8 个问题中 5 个已完成、1 个半做、3 个未做。已能在真实数据上启动项目级审校并跑过若干 chunk，但存在 LLM 格式容错、产物持久化、HITL 上层接通等缺口。

### 进度总览

| 问题 | 状态 | 完成内容 | 遗留 |
|------|------|---------|------|
| 1a. TranscriptStore 压缩 | ✅ 完成 | compact + prepend + 每轮检查 | - |
| 1b. EvidenceBundle 压缩 | ✅ 完成 | compact + totalEntries + 与 transcript 同步 | - |
| 1c. Token 预算追踪 | ❌ 未做 | - | `UsageSummary` 空壳，`usageBudget` 未使用 |
| 1d. 项目级记忆压缩 | ❌ 未做 | - | 跨焦点摘要 |
| 2. HITL 恢复 | ⚠️ 半做 | 底层 `resume()` + `resumeFromHumanReview()` 已实现 | 上层未接通，设计方向已变更为求助式 HITL，详见差距分析 1.2 |
| 3. Reader 缓存 | ✅ 完成 | ConcurrentHashMap + 装饰器 + 写回失效 | - |
| 4. System Prompt 分离 | ✅ 完成 | ReviewAgentSystemPromptBuilder + 端口签名变更 | - |
| 5. Per-tool JSON Schema | ✅ 完成 | ToolArgumentSchema + 动态渲染 + repair prompt + JSON Schema 补 queryTerms | 未做 per-tool 独立 schema（两阶段调用），选了单次调用 + 后置校验 + repair 方案 |
| 6. LLM 重试/退避 | ❌ 未做 | - | 429/503 直接崩 |
| 7. Legacy 构造器清理 | ❌ 未做 | - | 技术债 |
| 8. 向量检索 | ✅ 完成 | KnowledgeRetrievalService 集成 + queryTerms + fallback | `REVIEW_AGENT_LOOKUP` 复用 assembly 策略，无独立策略 |

### 计划外修复

- `ReviewToolExecutor.toStringListFromArgument` / `toStringMapFromArgument` 容错：LLM 传字符串不崩，自动解析为单元素列表
- `investigationArgumentsSchema` 补 `queryTerms` 声明：之前 JSON Schema 里没有这个字段，LLM 不知道类型
- `resumeFromHumanReview()` 构造器参数顺序 bug + 字段名 bug（`currentFocusChunkId` → `selectedFocusChunkId`）
- 18 个测试文件 UTF-8 BOM 损坏修复
- 冒烟测试 `RepositoryBackedPostDraftReviewAgentTermWriter` 构造器缺参数
- `RepositoryBackedPostDraftReviewAgentTermWriterTest` 缺 `Optional` import

### 实施报告

详见 `docs/superpowers/plans/2026-04-18-review-agent-e2e-hardening-impl-report.md`

### 跑通完整流程的差距分析

详见 `docs/superpowers/plans/2026-04-18-review-agent-e2e-run-gap-analysis.md`

---

## 原始问题描述（保留供参考）

## 问题 1：TranscriptStore 无限增长 + 缺少 Token 预算追踪

### 严重程度：P0（不修则 130+ chunk 根本跑不完）

### 现象

- `TranscriptStore` 只有 `append()`，没有自动 `compact()`
- `ReviewEvidenceBundle` 也只有 `merge()`，没有去重或压缩
- agent 每轮都往 transcript 和 evidence 里追加内容
- 130 chunk × 平均 5 轮 = 650 轮，transcript 和 evidence 会膨胀到远超 LLM 上下文窗口
- `UsageBudget` 和 `UsageSummary` 是空壳，loop 从未检查预算
- `ReviewAgentConfig.usageBudget=12000` 定义了但从未使用

### 改进方案

#### 1a. TranscriptStore 自动压缩

**文件**：
- 修改：`src/main/java/io/quillloom/application/postdraft/review/model/TranscriptStore.java`
- 修改：`src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- 修改：`src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentConfig.java`

**做法**：

1. `ReviewAgentConfig` 新增 `compactAfterEntries`（默认 20）和 `compactKeepLast`（默认 8）
2. 在 `AutonomousProjectReviewAgent.run()` 的每轮结束后，检查 `focusSession.transcriptStore().replay().size()`
3. 超过 `compactAfterEntries` 时，调用 `transcriptStore.compact(compactKeepLast)`
4. compact 时不只是截断，还要在头部插入一条摘要条目，格式如：`[compact] 已完成 N 轮取证，当前策略=LIGHT_EDIT，已读 chunk=[chunk-1,chunk-2]，关键发现=...`
5. 摘要内容从当前 `evidenceBundle.keyEvidenceSummaries()` 和 `session.strategy()` 提取

#### 1b. EvidenceBundle 去重与压缩

**文件**：
- 修改：`src/main/java/io/quillloom/application/postdraft/review/model/ReviewEvidenceBundle.java`

**做法**：

1. `merge()` 已做 `LinkedHashSet` 去重，但只去完全相同的字符串
2. 新增 `compact(maxEntries)` 方法：当任一列表超过 `maxEntries` 时，保留最近的条目，并在头部插入一条聚合摘要
3. 与 TranscriptStore 压缩同步调用

#### 1c. Token 预算追踪

**文件**：
- 修改：`src/main/java/io/quillloom/application/postdraft/review/model/UsageSummary.java`（当前是空壳，需补实）
- 修改：`src/main/java/io/quillloom/application/postdraft/review/port/out/ReviewAgentStructuredGenerationPort.java`
- 修改：`src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- 修改：`src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- 修改：`src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java`

**做法**：

1. `ReviewAgentStructuredGenerationPort` 的四个方法返回值改为携带 token 用量（或新增一个 wrapper 类型 `StructuredGenerationResult<T>` 包含 `result + inputTokens + outputTokens`）
2. `OpenAiCompatibleReviewAgentStructuredGenerationClient.invoke()` 从 `ChatResponse` 中提取 `tokenUsage()`
3. `UsageSummary` 补充 `addTurn(inputTokens, outputTokens)` 和 `totalTokens()` 方法
4. `ProjectReviewRuntimeSession` 新增 `usageSummary` 字段
5. `AutonomousProjectReviewAgent.run()` 每轮后累计 token 用量，超过 `config.usageBudget` 时以 `MAX_BUDGET_REACHED` 停机

#### 1d. 每个焦点完成后的项目级记忆压缩

**文件**：
- 修改：`src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- 修改：`src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java`

**做法**：

1. 当一个 working set 完成后（`complete_working_set` 执行成功），在进入下一个焦点前，对项目级 transcript 做压缩
2. 压缩产出一条项目级摘要：`[project-compact] 已完成 N/total chunk，已确认术语=[...]，待处理问题=[...]`
3. 这条摘要写入 `ProjectReviewRuntimeSession.transcriptStore` 的头部
4. 新焦点会话的初始 transcript 应包含这条项目级摘要

### 验证方式

- 单元测试：TranscriptStore.compact() 保留最近 N 条并插入摘要
- 单元测试：UsageSummary.addTurn() 累计正确，超预算返回 true
- 集成测试：模拟 20 轮循环后 transcript 不超过 compactKeepLast + 1 条
- Smoke：跑 10+ chunk 项目，观察 transcript 长度是否被控制

---

## 问题 2：HITL 恢复路径不完整

### 严重程度：P0（不修则遇到 WAITING_HUMAN 后无法继续）

### ⚠️ 设计方向已变更

原方案为"排障式 HITL"（`pollResolution()` + 外部 `resumeProject()`），已变更为"求助式 HITL"（Codex 风格）。

新方向详见 `docs/superpowers/plans/2026-04-18-review-agent-e2e-run-gap-analysis.md` 的 1.2 节。

核心变更：
- agent 遇到不确定的情况主动问人，人回答后 agent 自动继续
- `HumanInTheLoopGateway.submit()` 返回人的回答（阻塞等待），而非原样返回 request
- NO_PROGRESS 走 request_human_review 路径而非直接 FAILED
- 不需要外部 resume()，agent 自己完成"问→等→答→继续"

### 现象

- `HumanInTheLoopGateway` 只有 `submit()`，没有 `resolve()` / `resume()`
- `HumanReviewResolution` 存在但未接入 `AutonomousProjectReviewAgent`
- 当 agent 遇到 `WAITING_HUMAN` 后，`run()` 方法返回，但没有代码路径能把人工审阅结果传回 agent 继续运行
- 130+ chunk 场景下，几乎必然会遇到需要人工介入的 chunk，如果无法恢复，项目就卡住了
- 当前 NO_PROGRESS 直接标记 FAILED，应该改为请求人工帮助

### 已发现的脆弱点

`ProjectReviewRuntimeSession.withCurrentFocusSession()` 硬编码 `ProjectReviewStatus.ACTIVE` + 清空 `humanReviewRequest`。当前 `executeHumanReview` 的调用顺序是先 `withInvestigatingFocusSession`（设 ACTIVE）再 `withHumanReviewRequest`（设 WAITING_HUMAN），顺序对了所以最终结果正确。但如果后续有人调换顺序或在其他地方先调 `withCurrentFocusSession` 再忘调 `withHumanReviewRequest`，WAITING_HUMAN 状态会被静默吞掉，loop 不会停。

**修复要求**：resume 实现时，`withCurrentFocusSession` 不应无条件清空 `humanReviewRequest` 和硬编码 `ACTIVE`。应改为保留当前 status 和 humanReviewRequest，除非显式指定新值。或者更简单的方案：resume 路径不经过 `withCurrentFocusSession`，而是新增专门的 `resumeFromHumanReview()` 方法。

### 当前实施状态

底层已实现：
- `resumeFromHumanReview(String humanReviewNote)` 在 `ProjectReviewRuntimeSession`
- `resume(ProjectReviewRuntimeSession, String humanReviewNote)` 在 `AutonomousProjectReviewAgent`

上层未接通：
- `PostDraftReviewAgentService` 无 resume 方法
- `HumanInTheLoopGateway` 无 resolve 接口
- HITL 交互模式仍为排障式

待按求助式方向重新设计上层，详见差距分析文档 1.2 节。

---

## 问题 3：Reader 每次调用全量加载 ReviewPackage

### 严重程度：P0（性能垃圾，130+ chunk 下每次工具调用都全量加载）

### 现象

- `RepositoryBackedPostDraftReviewAgentReader` 的每个方法都调用 `loadReviewPackage()`
- `loadReviewPackage()` 每次从 repository 加载整个 `PostDraftReviewPackage`
- 130 chunk × 5 轮 × 2-3 次 reader 调用 = 1300~1950 次全量加载
- `PostDraftReviewPackage` 包含所有 chunk 的源文+译文+注释，单次可能几十 KB 到几百 KB

### 改进方案

**文件**：
- 修改：`src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java`
- 新增：`src/main/java/io/quillloom/infrastructure/postdraft/review/CachingPostDraftReviewAgentReader.java`（装饰器）

**做法**：

方案 A（推荐）：装饰器模式

1. 新增 `CachingPostDraftReviewAgentReader` 实现 `PostDraftReviewAgentReader`
2. 内部持有 `Map<String, PostDraftReviewPackage>` 缓存和 `Map<String, ProjectKnowledgeBase>` 缓存
3. `loadContinuationContext()` / `readContinuousChunks()` 等方法先查缓存
4. 缓存 miss 时委托给被装饰的 reader，并写入缓存
5. `record_confirmed_terms` 写回后需要使缓存失效（通过新增 `invalidateCache(projectId)` 方法）
6. 在 `PostDraftReviewAgentRuntimeConfiguration` 中装配：用 CachingReader 包裹 RepositoryReader

方案 B（简单）：在 RepositoryReader 内部加缓存

1. 在 `RepositoryBackedPostDraftReviewAgentReader` 内部加 `Map<String, PostDraftReviewPackage>` 字段
2. `loadReviewPackage()` 先查缓存
3. 新增 `invalidateCache(projectId)` 方法
4. `PostDraftReviewAgentTermWriter` 写回后调用 `reader.invalidateCache()`

### 验证方式

- 单元测试：连续调用两次 `readContinuousChunks()`，repository 只被访问一次
- 单元测试：`record_confirmed_terms` 后缓存被失效，下次读取拿到新数据

---

## 问题 4：缺少 System Prompt 层

### 严重程度：P0（不修则 LLM 没有稳定角色锚定，每轮重复消耗大量 token）

### 现象

- 每次 LLM 调用只传一个 `UserMessage`（由 prompt builder 生成的长文本）
- 角色定义（"你是译后审校 Agent"）、红线约束、工具使用规则全部塞在 user message 里
- 每轮都要重复发送这些不变的规则，浪费 token
- LLM 没有稳定的"身份感"，容易在长对话中漂移

### 改进方案

**文件**：
- 新增：`src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- 修改：`src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- 修改：`src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- 修改：`src/main/java/io/quillloom/application/postdraft/review/prompt/EvaluationPromptBuilder.java`
- 修改：`src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionPromptBuilder.java`
- 修改：`src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java`

**做法**：

1. 新增 `ReviewAgentSystemPromptBuilder`，构建稳定的 system prompt，包含：
   - 角色定义："你是译后审校 Agent，负责对小说翻译初稿做自主多步审校"
   - 核心约束：
     - 只能在本地 chunk、上下文、知识库范围内行动
     - 不联网
     - KEEP 不等于跳过衔接、逻辑、自相矛盾和专名一致性检查
     - tool rejection 是本地纠错信号，不是转人工信号
   - 工具使用规则（从 InvestigationPromptBuilder 的"关键语义"和"决策规则"部分提取）
   - 输出格式要求："只输出 JSON 对象，不要附加解释文本"

2. `OpenAiCompatibleReviewAgentStructuredGenerationClient.invoke()` 改为传入 system prompt + user prompt
   - `ChatRequest` 的 messages 从 `UserMessage.from(prompt)` 改为 `SystemMessage.from(systemPrompt) + UserMessage.from(userPrompt)`

3. `ReviewAgentStructuredGenerationPort` 的四个方法签名改为接收 `systemPrompt + userPrompt`

4. 各 PromptBuilder 的 `build()` 方法只输出动态事实部分（当前证据、transcript、working set 等），不再包含角色定义和规则

### 验证方式

- 单元测试：system prompt 包含角色定义和核心约束
- 单元测试：user prompt 不包含角色定义
- 单元测试：LLM 调用时 messages 包含 system + user 两条
- Smoke：观察 LLM 是否更稳定地遵循规则

---

## 问题 5：Structured Output 契约过松——Per-tool JSON Schema

### 严重程度：P1（已改善但仍需加强，根治 complete_working_set 参数缺失问题）

### 现象

- 当前所有工具共用一个 `INVESTIGATION_SCHEMA`，arguments 是宽松的 `JsonObjectSchema`
- LLM 可以合法输出 `{"toolName":"complete_working_set","arguments":{},"reason":"..."}` 而不违反 schema
- prompt 文本约束（"必须提供 chunkIds"）不够可靠
- 这是 smoke blocker 文档中记录的 `missing_argument:chunkIds` 死循环的根因

### 改进方案

**文件**：
- 修改：`src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- 修改：`src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- 修改：`src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDefinition.java`
- 新增测试

**做法**：

1. `ReviewToolDefinition` 新增 `JsonSchema argumentSchema` 字段
2. 为每个工具定义独立的 argument schema：

| 工具 | required arguments schema |
|------|--------------------------|
| `read_previous_chunks` | `{ "count": integer, required }` |
| `read_next_chunks` | `{ "count": integer, required }` |
| `expand_block_context` | `{}` (无必填) |
| `read_decision_notes` | `{}` |
| `read_transition_note` | `{}` |
| `lookup_knowledge_cards` | `{}` |
| `read_confirmed_terms` | `{ "sourceTerms": string[], required }` |
| `record_confirmed_terms` | `{ "entries": object, required }` |
| `evaluate_focus` | `{}` |
| `draft_revision` | `{}` |
| `request_human_review` | `{ "reason": string, required }` |
| `complete_working_set` | `{ "chunkIds": string[], required }` |
| `complete_project` | `{}` |

3. `ReviewToolRegistry.defaultRegistry()` 注册时附带 argument schema

4. `OpenAiCompatibleReviewAgentStructuredGenerationClient.generateNextToolDecision()` 改为两阶段：
   - 第一阶段：让 LLM 先输出 `toolName`（可以用宽松 schema 或直接从注册表枚举）
   - 第二阶段：根据 toolName 查注册表获取对应的 argument schema，再让 LLM 输出完整 decision
   - 或者更简单的方案：在 `invoke()` 返回后，用 per-tool schema 做二次校验，不满足则触发 repair

5. 推荐采用"单次调用 + 后置校验 + repair"方案（改动最小）：
   - 保持单次 LLM 调用
   - 返回后用 `ReviewToolDefinition.argumentSchema` 校验 arguments
   - 校验失败时触发 repair prompt（已有机制），repair prompt 中附带该工具的 required arguments
   - 这比两阶段调用简单，且与现有 repair 机制兼容

### 验证方式

- 单元测试：`complete_working_set` 缺 chunkIds 时校验失败
- 单元测试：`read_previous_chunks` 缺 count 时校验失败
- 单元测试：repair prompt 包含正确的 required arguments 信息
- Smoke：观察 `complete_working_set` 是否稳定带上 chunkIds

---

## 问题 6：LLM 调用无重试/退避

### 严重程度：P1（开发环境网络稳定，但生产环境必须修）

### 现象

- `OpenAiCompatibleReviewAgentStructuredGenerationClient.invoke()` 对 LLM 调用无任何重试
- 429 限流、503 服务不可用、网络超时等瞬态错误直接抛 `IllegalStateException` 终止整个 agent
- 130+ chunk 场景下，即使 1% 的调用失败，也几乎必然遇到

### 改进方案

**文件**：
- 修改：`src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- 修改：`src/main/java/io/quillloom/infrastructure/postdraft/review/ReviewAgentLlmProperties.java`

**做法**：

1. `ReviewAgentLlmProperties` 新增：
   - `maxRetries`（默认 2）
   - `retryBaseDelayMs`（默认 500）
   - `retryMaxDelayMs`（默认 5000）

2. `invoke()` 方法外包重试逻辑：
   ```
   for attempt in 0..maxRetries:
     try:
       response = chatModel.chat(request)
       return parse(response)
     catch (可重试异常):
       if attempt >= maxRetries: throw
       delay = min(retryBaseDelayMs * 2^attempt, retryMaxDelayMs)
       Thread.sleep(delay)
   ```

3. 可重试异常的判断：
   - HTTP 429 / 503 / 502 / 500 / 408
   - 网络超时（SocketTimeoutException）
   - 不重试：400（请求格式错误）、401（认证失败）、JSON 解析失败

4. 每次重试记录到 HistoryLog

### 验证方式

- 单元测试：模拟 429 后重试成功
- 单元测试：模拟连续 3 次 503 后抛出异常
- 单元测试：400 错误不重试

---

## 问题 7：PostDraftReviewSession legacy 构造器静默丢弃字段

### 严重程度：P2（不掩饰问题就先不动，但需确认）

### 现象

- `PostDraftReviewSession` 有 6 个构造器，其中 4 个是 legacy 兼容
- legacy 构造器中 `actionTrail` 总是返回 `List.of()`
- `autonomyState()` 和 `stopReason()` 是从 `diagnostics` 反推的，不是独立字段
- 新增字段时需要修改所有构造器和所有 `with*()` 方法

### 改进方案

**前提**：先确认 legacy 构造器是否还有调用方。如果没有，直接删除。

**文件**：
- 修改：`src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewSession.java`

**做法**：

1. 搜索所有对 legacy 构造器的调用，确认是否还有使用
2. 如果没有调用方，直接删除 4 个 legacy 构造器
3. 如果有调用方，先迁移调用方，再删除
4. `autonomyState` 和 `stopReason` 应该成为一等字段，不从 `diagnostics` 反推
5. 长期：考虑用 Builder 模式替代大量 `with*()` 方法

### 验证方式

- 编译通过
- 所有现有测试通过

---

## 问题 8：lookup_knowledge_cards 未接入向量检索，只做内存过滤

### 严重程度：P0（不修则 agent 的知识卡检索能力远弱于 D 阶段）

### 现象

- 当前 `lookup_knowledge_cards` 的实现是：全量加载 `ProjectKnowledgeBase` → `cards().stream().filter(card -> card.applicableChunkIds().contains(chunkId))`
- 这只是按 C0 阶段预标注的 `applicableChunkIds` 做精确匹配，不是语义检索
- D 阶段已有完整的混合检索基础设施：关键词召回 + 向量召回(pgvector) + 混合重排 + 截断选卡
- Review Agent 的知识卡检索能力远弱于 D，agent 可能查不到真正需要的知识卡

### D 阶段已有的检索链路

```
KnowledgeRetrievalService.retrieve()
  → KeywordKnowledgeRecallService.recall()      // 关键词召回
  → VectorKnowledgeRecallService.merge()         // 向量召回（pgvector 余弦距离）
  → HybridKnowledgeRanker.applyScores()          // 混合重排
  → KnowledgeSelectionService.select()           // 截断选卡
```

关键端口：
- `KnowledgeRetrievalService`：统一检索端口（application/translation/port/out）
- `KnowledgeEmbeddingService`：向量生成端口（application/preprocess/port/out）
- `KnowledgeIndexRepository`：向量索引存储端口，已有 pgvector 实现（application/preprocess/port/out）
- `KnowledgeRetrievalPolicyResolver`：检索策略解析端口

### 改进方案

**文件**：
- 修改：`src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentReader.java`
- 修改：`src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java`
- 修改：`src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- 修改：`src/main/java/io/quillloom/infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfiguration.java`

**做法**：

1. `PostDraftReviewAgentReader.lookupKnowledgeCards()` 签名改为支持查询词：
   ```java
   List<KnowledgeCard> lookupKnowledgeCards(String projectId, String chunkId, List<String> queryTerms);
   ```
   - `queryTerms` 来自 LLM 的工具调用参数（agent 自主决定搜什么）
   - 如果 `queryTerms` 为空，fallback 到当前的 `applicableChunkIds` 精确匹配

2. `RepositoryBackedPostDraftReviewAgentReader` 注入 `KnowledgeRetrievalService`

3. `lookupKnowledgeCards()` 实现改为：
   - 有 queryTerms 时：构建 `KnowledgeRetrievalQuery(useCase=REVIEW_AGENT_LOOKUP, chunkId, queryTerms)`，委托给 `KnowledgeRetrievalService.retrieve()`
   - 无 queryTerms 时：保持当前的 `applicableChunkIds` 精确匹配（作为 fallback）

4. `KnowledgeRetrievalUseCase` 枚举新增 `REVIEW_AGENT_LOOKUP`

5. `KnowledgeRetrievalPolicyResolver` 为 `REVIEW_AGENT_LOOKUP` 配置检索策略（可复用 `SUPPLEMENTAL_LOOKUP` 的策略参数作为起点）

6. `ReviewToolRegistry` 中 `lookup_knowledge_cards` 的参数定义新增可选参数 `queryTerms`（string array）

7. `ReviewToolExecutor.executeKnowledgeLookup()` 从 `call.arguments()` 提取 `queryTerms` 传给 reader

8. `PostDraftReviewAgentRuntimeConfiguration` 中装配 `KnowledgeRetrievalService` 到 Reader

### 验证方式

- 单元测试：有 queryTerms 时走 KnowledgeRetrievalService
- 单元测试：无 queryTerms 时 fallback 到 applicableChunkIds 过滤
- 单元测试：REVIEW_AGENT_LOOKUP 策略可被解析
- Smoke：观察 agent 是否能通过语义查询找到更相关的知识卡

---

## 实施顺序建议

按"让 130+ chunk 跑通"的目标排序：

| 顺序 | 问题 | 预计工作量 | 理由 |
|------|------|-----------|------|
| 1 | 问题 3：Reader 缓存 | 2h | 改动小、收益大，直接消除性能垃圾 |
| 2 | 问题 4：System Prompt 分离 | 3h | 减少每轮 token 消耗，稳定 LLM 行为 |
| 3 | 问题 1：TranscriptStore 压缩 + Token 预算 | 4h | 不修则长 session 必然撑爆 |
| 4 | 问题 2：HITL 恢复路径 | 3h | 不修则遇到人工介入就卡死 |
| 5 | 问题 8：lookup_knowledge_cards 接入向量检索 | 3h | 复用 D 阶段已有基础设施，改动量可控 |
| 6 | 问题 5：Per-tool JSON Schema | 4h | 根治参数缺失死循环 |
| 7 | 问题 6：LLM 重试/退避 | 2h | 生产环境必要，开发环境可后补 |
| 8 | 问题 7：Legacy 构造器清理 | 2h | 技术债，不阻塞功能 |

**建议**：先做 1-5，然后跑一次真实 130+ chunk smoke。如果跑通，再做 6-7 加固。8 可以放到后面。

---

## 不在本计划范围内的事项

1. **Agent Loop 迭代上限**：当前 `while(true)` 是有意设计，130+ chunk 需要持续运行。Token 预算追踪（问题 1c）已提供等价的安全阀。
2. **ReviewToolExecutor 拆分**：已移至差距分析 2.1，作为后续优先项。详见 `2026-04-18-review-agent-e2e-run-gap-analysis.md`。
3. **Prompt 注入防护**：当前不考虑。
4. **ReviewToolRegistry 动态调整**：与 Executor 拆分一起重构。
5. **流式输出/进度回调**：`ReviewRuntimeVisualizer` 接口已预留流式注入点，暂不做 SSE/数据库实现。详见差距分析 2.4。
6. **求助式 HITL**：已从本计划 Problem 2 拆出，作为差距分析 1.2 独立推进。
7. **修订译文写回数据库**：差距分析 1.1，不在本计划范围内。
8. **Session 持久化可恢复**：差距分析 1.3，不在本计划范围内。
9. **结构化压缩摘要**：差距分析 2.2，不在本计划范围内。

---

## 验证总入口

修完问题 1-4 后，用以下命令验证：

```powershell
mvn -q "-Dtest=PostDraftProjectReviewAgentSmokeTest" `
  "-Dquillloom.test.post-draft-project-review-smoke.enabled=true" `
  "-Dquillloom.test.post-draft-project-review-smoke.project-id=book-smoke-1776178359703" `
  "-Dquillloom.postdraft.review.llm.enabled=true" `
  "-Dquillloom.postdraft.review.llm.base-url=<baseUrl>" `
  "-Dquillloom.postdraft.review.llm.api-key=<apiKey>" `
  "-Dquillloom.postdraft.review.llm.model-name=<modelName>" `
  test
```

关键观察点：
1. agent 是否能越过 chunk-1 继续处理后续 chunk
2. transcript 长度是否被控制在合理范围
3. 遇到 WAITING_HUMAN 后是否能 resume
4. reader 是否不再每轮全量加载
5. LLM 调用是否带 system prompt
