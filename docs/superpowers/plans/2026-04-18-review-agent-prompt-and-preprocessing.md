# Review Agent Prompt 优化与数据预处理

> ⚠️ **本文档已废弃**
>
> 根因分析已迁移至 `2026-04-19-d-term-memory-and-review-agent-recovery-plan.md`。
>
> 本文档保留作为历史参考，但修复方案以新文档为准。

## 发现时间

2026-04-18（127 chunk 真实冒烟测试中暴露）

## 严重度

🟡 中 — 不阻塞核心链路，但影响 agent 运行效率和决策质量

---

## 一、从冒烟日志暴露的问题

### 问题 1：strategy=KEEP 时 LLM 仍想修订

**日志证据**：

```
chunk-4 → evaluate_focus(KEEP)
        → read_confirmed_terms(发现专名冲突：孔代咖啡馆 vs 勒孔代咖啡馆)
        → draft_revision → guardrail 拒绝：invalid_strategy_for_tool:draft_revision:KEEP
```

LLM 发现了 chunk 译文与项目术语表不一致，想要修订，但当前 strategy=KEEP 不允许 `draft_revision`。LLM 没有选择升级 strategy，而是反复查询同一术语。

**影响**：浪费 LLM 调用轮次，增加 NO_PROGRESS 风险。

### 问题 2：重复调用同一工具

**日志证据**：

```
chunk-4 → read_confirmed_terms(Le Condé) → 返回 勒孔代咖啡馆
chunk-4 → read_confirmed_terms(Le Condé) → 返回 勒孔代咖啡馆
chunk-4 → read_confirmed_terms(Le Condé) → 返回 勒孔代咖啡馆
chunk-4 → read_confirmed_terms(Le Condé) → 返回 勒孔代咖啡馆
```

4 次调用返回相同结果，LLM 没有利用已有信息做决策。

**影响**：浪费 token 和时间。

### 问题 3：record_confirmed_terms 参数格式错误

**日志证据**：

```
chunk-6 → record_confirmed_terms(entries=???)
        → 契约校验失败：invalid_argument:entries
        → LlmStructuredOutputException → 应用崩溃
```

LLM 不理解 `entries` 参数的格式要求（应为 `Map<String, String>`），返回了不合法的 JSON 结构。

**影响**：直接导致应用崩溃（已单独记录在 `2026-04-18-review-agent-structured-output-crash-bug.md`）。

---

## 二、根因分析

### 2.1 数据层面：D 阶段术语登记遗漏导致全局术语被后到者污染

**实际数据证据**（来自 baseline `book-smoke-1776178359703.json` 和 trace 日志）：

| 数据源 | 位置 | 实际值 | 说明 |
|--------|------|--------|------|
| chunk-4 `translatedText` | chunk 级别 | `...孔代咖啡馆...` | D 翻译时正文用了正确的"孔代" |
| chunk-4 `confirmedTermUpdates` | chunk 级别 | **无 Le Condé 条目** | D 没有把首次命名决定写入 confirmedTermUpdates |
| chunk-4 输入的 `confirmedTerms` | chunk 级别 | `Éditions Gallimard -> 伽利玛出版社`, `Patrick Modiano -> 帕特里克·莫迪亚诺` | chunk-4 翻译时，Le Condé 尚不在术语表中 |
| chunk-9 `confirmedTermUpdates` | chunk 级别 | `Le Condé -> 勒孔代咖啡馆` | 后续 chunk 把错误的"勒孔代"登记了 |
| `termState.effectiveConfirmedTerms` | 项目级别 | `Le Condé -> 勒孔代咖啡馆` | buildTermState 用 putIfAbsent，chunk-9 先登记者胜出 |

**完整因果链**：

1. chunk-4 翻译 "Le Condé" 时，LLM 在正文中正确翻译为"孔代咖啡馆"，但**没有把 `Le Condé -> 孔代咖啡馆` 写入 `confirmedTermUpdates`**
2. chunk-4 的 commentary 中声称 "Le Condé => 孔代咖啡馆"，但这只是 LLM 的叙述，不是结构化数据
3. 后续 chunk-9 遇到 "Le Condé"，LLM 错误地把法语定冠词 "Le" 也音译了，输出 `Le Condé -> 勒孔代咖啡馆` 并**写入了 `confirmedTermUpdates`**
4. `PostDraftReviewPackageAssembler.buildTermState()` 用 `putIfAbsent` 合并，chunk-9 的"勒孔代咖啡馆"成为全局术语
5. Review Agent 在 chunk-4 上看到：正文"孔代咖啡馆" vs 全局术语"勒孔代咖啡馆" → 正确发现冲突
6. 但当前 strategy=KEEP 不允许修订 → 反复查询同一术语 → 死循环风险

**根因不是 Review Agent 误读，也不是 validator 冲突丢弃，而是 D 阶段 LLM 的术语登记遗漏**：

- D 的 prompt 明确要求"不允许正文已经采用了某个稳定叫法，却不把该决定登记进 confirmedTermUpdates"
- 但 LLM 在 chunk-4 上违反了这条规则：用了"孔代咖啡馆"却不登记
- 这导致术语位空缺，被后续 chunk 的错误译名占据

**补充：法语定冠词问题**

"Le" 是法语阳性单数定冠词，不是专名的一部分。"Le Condé" 应译为"孔代"而非"勒孔代"。当前系统没有法语定冠词剥离逻辑，LLM 可能把 "Le Condé" 整体当作专名音译。这是 D 层 LLM 的知识盲区，也是数据预处理可以提前解决的场景。

### 2.2 代码层面：演化后的 projectMemory 丢失

`NovelTranslationWorkflowService.runDraftWorkflow()` 第 188 行：

```java
savePostDraftReviewPackage(state, projectMemory);  // 传的是原始 projectMemory
```

`translateChunks` 内部的 `effectiveProjectMemory`（逐 chunk 演化后的）是局部变量，翻译完就丢了。`buildTermState` 收到的是初始空 `projectMemory`。当前因为 `buildTermState` 会从 drafts 重新合并，结果碰巧一致，但这是脆弱的隐式依赖——如果 `evolveProjectMemory` 和 `buildTermState` 的合并逻辑出现分歧，就会产生不一致。

### 2.3 Prompt 层面：缺少关键决策规则

当前 investigation prompt 没有明确告诉 LLM：

1. **strategy 约束**：当前 strategy=KEEP 时，只能调用读取类工具和 `evaluate_focus`，不能调用 `draft_revision`
2. **避免重复查询**：如果 `read_confirmed_terms` 已返回结果，不需要再次调用
3. **升级策略**：发现专名不一致时，应调用 `evaluate_focus` 请求升级 strategy，而不是在 KEEP 约束下反复查询
4. **参数格式**：`record_confirmed_terms` 的 `entries` 参数必须是 `Map<String, String>` 格式

### 2.4 Schema 层面：record_confirmed_terms 的参数描述不够

当前 `ReviewToolRegistry` 中 `record_confirmed_terms` 的参数描述可能不够清晰，LLM 无法理解 `entries` 的正确格式。

---

## 三、短期修复：Prompt 优化

### 3.1 Investigation prompt 加 strategy 约束

在 `InvestigationPromptBuilder` 或 `ReviewAgentSystemPromptBuilder` 中加：

```
当前 strategy 约束：
- KEEP：只能调用读取类工具（read_previous_chunks, read_next_chunks, expand_block_context,
  read_decision_notes, read_transition_note, lookup_knowledge_cards, read_confirmed_terms）
  和 evaluate_focus。不能调用 draft_revision。
- LIGHT_EDIT / DEEP_EDIT / RETRANSLATE：可以调用所有工具。
- 如果发现当前 strategy 无法处理的问题（如专名不一致），应立即调用 evaluate_focus 请求升级 strategy。
```

### 3.2 System prompt 加避免重复查询指令

```
避免重复查询：
- 如果 read_confirmed_terms 已返回某个术语的确认译名，不需要再次查询同一术语。
- 如果 lookup_knowledge_cards 已返回相关知识卡，不需要再次查询同一关键词。
- 每次工具调用前，先回顾已有证据，确认是否需要新信息。
```

### 3.3 record_confirmed_terms 工具描述加参数格式示例

在 `ReviewToolRegistry` 中 `record_confirmed_terms` 的描述中加：

```
entries 参数格式：必须是 sourceTerm -> targetTerm 的映射。
示例：{"Le Condé": "勒孔代咖啡馆", "Louki": "露姬"}
key 是原文术语，value 是确认的中文译名。
```

### 3.4 evaluate_focus prompt 加升级策略引导

在 `EvaluationPromptBuilder` 中加：

```
升级策略判断：
- 如果 investigation 发现 chunk 译文与项目术语表不一致，应升级为 LIGHT_EDIT
- 如果发现结构性问题（如衔接断裂、漏译），应升级为 LIGHT_EDIT 或 DEEP_EDIT
- 如果发现严重误译或风格偏离，应升级为 DEEP_EDIT 或 RETRANSLATE
- 只有确认译文完全正确时才保持 KEEP
```

---

## 四、长期设计：人工数据预处理

### 4.1 目标

在 agent 开跑前，对项目数据做预检，标记已知问题，让 agent 优先处理。

### 4.2 D 层术语登记强化（中期修复）

**目标**：防止 D 阶段 LLM 遗漏术语登记，避免空缺被后续错误译名占据。

**方案 A：D 层 validator 后置校验**（推荐）

在 `ChunkTranslationResultValidator` 中增加"正文用词未登记"检测：

- 扫描 `translatedText` 中出现的专名（利用 chunk 的 `entities` 和 `personAliasHints`）
- 如果某个 entity 在正文中被翻译成了中文，但没有出现在 `confirmedTermUpdates` 中
- 自动追加该条目到 `allowedConfirmedUpdates`

这与现有的 `detectMissingFirstNamingConfirmation` 类似，但更强——现有逻辑只追加 decisionNote 提醒 LLM 在修订轮补写，新逻辑直接补写。

**方案 B：数据预处理阶段预填术语**

在 D 开跑前，对每个 chunk 的 `entities` 做预处理：
- 对高频核心人名/地名，提前查知识库确定标准译名
- 将结果作为 `confirmedTerms` 初始值传入 D，而非空 Map
- 这样 D 翻译时就能看到已有术语，不会产生空缺

**方案 C：法语定冠词剥离**

在 C0 预处理或 D 输入装配阶段，对法语定冠词做预处理：
- 识别 "Le/La/Les/L'" 开头的 entity
- 在 `ExecutionContextView` 或 `DraftStageGlobalGlossary` 中标注定冠词
- 让 D 的 prompt 明确告知 LLM："Le/La/Les 是法语定冠词，不是专名的一部分，翻译时应省略"

### 4.3 预检项

| 预检项 | 检查内容 | 输出 |
|--------|---------|------|
| **术语一致性** | chunk 的 `confirmedTermUpdates` 与项目级 `effectiveConfirmedTerms` 对比 | 不一致 chunk 列表 |
| **术语覆盖率** | chunk 中出现的专名是否都有对应的术语表条目 | 缺失术语列表 |
| **知识卡覆盖率** | chunk 是否有对应的知识卡 | 无知识卡的 chunk 列表 |
| **衔接完整性** | 相邻 chunk 的 `transitionNote` 是否完整 | 缺失衔接的 chunk 对 |
| **chunk 边界合理性** | chunk 是否在语义边界处切分 | 边界异常的 chunk 列表 |

### 4.4 预检结果的使用方式

1. **标记优先级**：术语不一致的 chunk 标记为"高优先级"，agent 优先处理
2. **注入已知问题**：预检发现的问题作为 `operatorNote` 注入 agent，让 LLM 知道哪些问题需要关注
3. **调整 strategy**：术语不一致的 chunk 直接从 LIGHT_EDIT 开始，跳过 KEEP

### 4.5 实施时机

不急。当前 agent 还在冒烟阶段，预检可以等 agent 稳定跑通后再做。但预检的设计应该与 agent 的入口（`StartProjectPostDraftReviewAgentCommand`）对齐——`operatorNote` 字段就是为此预留的。

---

## 五、优先级

| 优先级 | 事项 | 预估 | 前置条件 |
|--------|------|------|---------|
| **1** | 修复结构化输出崩溃 bug | 5 分钟 | 无 |
| **2** | Prompt 优化（3.1-3.4） | 半天-1 天 | bug 修复后 |
| **3** | 重新跑 127 chunk 验证 prompt 优化效果 | 2-4 小时 | prompt 优化后 |
| **4** | D 层术语登记强化（方案 A：validator 后置补写） | 1-2 天 | agent 稳定跑通后 |
| **5** | 数据预处理设计（预检 + 法语定冠词剥离） | 后置 | D 层强化后 |
