# Review-Agent Unregistered Tool Prompt Hardening Design

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以最小兼容改动修复 review-agent 在 next-step / structured-output-repair 阶段反复产出未注册工具名、并且 repair 无法收敛的问题。

**Architecture:** 保持现有 `system prompt -> investigation prompt -> structured generation -> validator -> repair loop` 总体链路不变，把主修范围收缩到三点：system prompt 显式 whitelist、schema / response-format 对精确 `toolName` 的更强表述、以及 `structured_output_repair` 的 `unregistered_tool` 定向修复。避免把问题转嫁给 executor，也不做大范围协议重构。

**Tech Stack:** Java, LangChain4j JSON schema response format, review-agent prompt builders, repair-loop prompt assembly, JUnit 5

---

## 背景与问题判断

### 当前观察到的故障

在 review-agent 的 next-step 阶段，模型反复输出不存在的工具名，例如：

- `read_chunks`
- `read_adjacent_chunks`

随后 client / validator 给出：

- `Review agent invalid structured tool decision: unregistered_tool`

系统进入：

- `structured_output_repair`

但 repair 后仍继续输出未注册工具名，无法收敛。

### 当前文档状态

上一版文档正文存在明显 mojibake，已经影响审查与执行。本文档本轮首先修复编码与表述问题，确保后续实现、评审、测试都能以这份文档为稳定依据。

### 根因判断

当前主缺陷不在 executor，但也不能简单定性成“只有 prompt 有问题”。

更准确的判断是三层叠加：

1. 原始 next-step prompt 没有在显式位置把合法 `toolName` 白名单作为强约束直接告诉模型。
2. schema / response-format 对 `toolName` 的约束仍偏弱，模型看到的是注册提示，不是 exact whitelist。
3. `structured_output_repair` 在 `unregistered_tool` 场景下没有回显上一轮非法名字，也没有给出定向替换规则，导致 repair 可操作性不足。

### 当前实现的具体不足

#### 1. system / investigation prompt 只有抽象约束，没有精确白名单

`ReviewAgentSystemPromptBuilder` 当前只说：

- 不要发明未注册工具
- 输出必须符合 structured contract

但没有直接列出：

- 合法 `toolName` 到底有哪些
- 必须逐字复制
- 不允许把 `read_previous_chunks` / `read_next_chunks` 概括成 `read_adjacent_chunks`

`InvestigationPromptBuilder` 当前也没有单独的工具白名单块，只是泛化地要求：

- make sure the tool choice and argument structure are valid

这不足以约束模型使用 registry 中的精确工具名。

#### 2. schema 有工具信息，但对精确工具名约束仍然偏弱

`OpenAiCompatibleReviewAgentStructuredGenerationClient` 的 schema description 确实拼出了工具说明，但它当前更像“说明性 registry 摘要”，不是“强枚举约束”：

- `toolName` 仍然是普通 string schema
- 描述是 `Must be a tool name registered in ReviewToolRegistry.`
- 没有 `Allowed toolNames are exactly [...]`
- 没有“禁止别名 / 概括名 / 合并名”

所以模型仍可能把两个合法工具抽象成一个不存在的概括工具名。这不是 prompt 单层问题，而是 schema 约束表达也不够硬。

#### 3. structured_output_repair 对 `unregistered_tool` 缺少可操作的专项 guidance

`buildStructuredOutputRepairPrompt(...)` 当前只提供：

- `structuredOutputError`
- `toolArgumentSummary`
- 一般性 repair constraints

但没有：

- 精确合法工具名集合
- `previousInvalidToolName`
- “上一个 toolName 未注册” 的明确说明
- 常见非法别名示例
- “先判断上一轮非法名原本想表达什么动作，再改成一个精确已注册工具” 的硬规则
- “如果上一个名字想表达相邻阅读，必须改成 `read_previous_chunks` 或 `read_next_chunks`” 这类定向修复规则

因此 repair 阶段仍容易继续输出语义接近但未注册的工具名。

#### 4. previousInvalidToolName 已被确认是必做项，但当前实现链路还没有稳定来源

当前 provider 侧 repair builder 只拿到 `errorMessage` 字符串；client 抛出的也是：

- `Review agent invalid structured tool decision: unregistered_tool` + `rawOutput=...`

这种拼接文本。

如果文档只要求“repair prompt 必须回显 `previousInvalidToolName`”，却不定义它从哪里来、如何传递，后续实现很容易退化成：

- 从拼接错误字符串里脆弱抽取 `rawOutput`
- 再从 `rawOutput` 文本里临时解析 `toolName`

这会直接削弱本轮主修点。因此本轮必须把来源机制写成明确实现要求。

#### 5. 现有测试主要覆盖参数修复，不覆盖未注册工具收敛

现有回归主要集中在：

- `missing_argument:*`
- `invalid_argument:entries`
- JSON parse failure

缺少以下关键回归：

- 首轮输出未注册工具名
- repair prompt 是否显式给出合法工具白名单
- repair 是否能从 `unregistered_tool` 收敛回已注册工具名
- 如果拿不到非法工具名，repair 是否仍然可诊断

---

## 设计目标

这次方案只做最小兼容改动，目标有且只有五个：

1. 在原始 next-step prompt 中，把合法工具集合表达成显式、精确、逐字匹配的强约束。
2. 在 schema / response-format 中，把 `toolName` 的允许集合表达成 exact whitelist，而不只是“registered in registry”。
3. 在 `structured_output_repair` 中，对 `unregistered_tool` 增加可操作的专项修复 guidance。
4. 明确 `previousInvalidToolName` 的来源机制与不可用时的降级路径。
5. 保持现有 prompt / schema / validator / executor / repair-loop 分层不变，并增加足够窄的回归测试锁住本次故障。

非目标：

- 不重写整体 prompt 架构
- 不把 `toolName` 升级成 schema enum 作为本轮前置条件
- 不把 `structured_output_repair` 与 `decision_repair` 大改合并
- 不修改 executor 语义
- 不把问题解释成“模型太笨”

---

## 推荐实施方案

### 方案总览

采用“system prompt 完整 whitelist + schema exact whitelist 表述 + `structured_output_repair` 定向修复”的最小兼容方案。

这套方案分三个主改动和一个可选改动：

1. 在 system prompt 的输出 contract 区域新增 `[Registered Tool Names]`
2. 在 schema / response-format 中把 `toolName` 允许集合改成 exact whitelist 表述
3. 在 `structured_output_repair` 中按错误类型注入 `unregistered_tool` 专项 guidance
4. 可选：在 investigation prompt 中保留一句弱提醒，但不重复协议正文

### 为什么白名单不能只放 schema description

白名单属于行为约束，不只是字段说明。

如果只放在 schema description：

- 信息仍然偏间接
- 模型更容易把它当成“背景说明”
- 对首轮 next-step 约束不够强

因此推荐：

- system prompt：唯一完整 whitelist
- schema / response-format：复用同一份 whitelist 做机器可见精确表述
- repair：仅在 `unregistered_tool` 时局部注入同一份 whitelist

而不是把完整协议分散复制在多层 prompt 正文里。

---

## 具体文案设计

### A. System Prompt：新增 `[Registered Tool Names]`

推荐位置：

- 放在 `[Output Contract]` 同一区域前后，保持工具协议信息集中
- 不建议丢到 prompt 很前面，避免与输出协议脱节

推荐文案结构：

```text
[Registered Tool Names]
You may choose only from the following registered tool names for next-step decisions:

read_previous_chunks
read_next_chunks
expand_block_context
read_decision_notes
read_transition_note
lookup_knowledge_cards
read_confirmed_terms
record_confirmed_terms
evaluate_focus
draft_revision
request_human_review
complete_working_set
complete_project

Hard rules:
1. toolName must be exactly one of the registered names above.
2. Copy the selected toolName exactly as written.
3. Do not invent aliases, summaries, merged names, or paraphrases.
4. Forbidden invalid aliases include: read_chunks, read_adjacent_chunks, adjacent_read, read_context_chunks.
5. If you need previous and next context, choose the single registered tool that matches the immediate need in this step. Do not merge multiple registered tools into one invented tool name.
```

实现要求：

- 这份 whitelist 文案必须由 `ReviewToolRegistry` 单源渲染
- 不允许在 system / schema / repair 中各写一份手工名单

### B. Schema / Response Format：补强 `toolName` exact whitelist 表述

推荐位置：

- `toolName` 字段 description
- `investigationSchemaDescription()` 中的工具说明总览

推荐文案方向：

```text
Allowed toolNames are exactly: [...]
toolName must exactly match one registered tool name from that list.
Do not invent aliases, merged names, or paraphrases.
```

实现要求：

1. `toolName` 字段 description 必须包含 exact whitelist。
2. `investigationSchemaDescription()` 必须复用同一份 whitelist。
3. `toolName` description 与 `investigationSchemaDescription()` 不能一处升级、一处仍保留 generic string 提示。
4. forbidden alias 规则至少要在 schema 层出现一处，不能只留在 prompt 层。
5. 以上文本必须与 system prompt 使用同一 registry 渲染源。

说明：

- 本轮不强制要求实现 enum
- 但不能把“增强 schema 对 allowed toolNames 的精确表述”排除出范围
- 如果底层 builder 不方便直接表达 enum，至少要把 description 提升为 exact whitelist 文案

### C. Investigation Prompt：仅保留一句弱提醒

如果保留 investigation 层补强，只保留一句提醒，不在这里重定义协议正文。

推荐文案结构：

```text
Before producing JSON, verify that toolName exactly matches one registered tool name from the output contract.
```

不建议在 investigation prompt 再重复：

- 短白名单
- adjacent-reading 示例
- 大段 forbidden aliases

这些应由 system prompt 和 repair 承担。

### D. Structured Output Repair：新增 `unregistered_tool` 专项 guidance

推荐实现方式：

- 维持现有 `nextStepEntriesCompatibilityRepairGuidance(...)` 模式
- 新增 `nextStepUnregisteredToolRepairGuidance(...)`
- 仅在 `errorMessage` 包含 `unregistered_tool` 时追加该块

推荐文案结构：

```text
[unregistered_tool repair]
The previous output used a toolName that is not registered.
validationError: unregistered_tool
previousInvalidToolName: <previous-invalid-tool-name or unavailable>

Allowed toolNames are exactly:
read_previous_chunks
read_next_chunks
expand_block_context
read_decision_notes
read_transition_note
lookup_knowledge_cards
read_confirmed_terms
record_confirmed_terms
evaluate_focus
draft_revision
request_human_review
complete_working_set
complete_project

Repair rules:
1. First determine what action type previousInvalidToolName was trying to express.
2. Then replace it with exactly one registered toolName from the list above.
3. Copy the selected toolName exactly. Do not rename, summarize, merge, or paraphrase tool names.
4. Forbidden invalid aliases include: read_chunks, read_adjacent_chunks, adjacent_read, read_context_chunks.
5. If previousInvalidToolName is read_adjacent_chunks or any adjacent-reading alias, do not invent a combined tool. Choose exactly one of read_previous_chunks or read_next_chunks based on the immediate evidence need.
6. If previousInvalidToolName is unavailable, choose exactly one registered toolName by using the current-round evidence and the whitelist above. Do not invent a fallback alias.
7. After changing toolName, arguments must also match the selected registered tool in the same response.
8. Return one valid JSON object only.
```

这里 `previousInvalidToolName` 不是可选增强，而是本轮必做要求。

### E. previousInvalidToolName 的来源机制与降级路径

这是本轮新增的强制实现边界。

要求如下：

1. `previousInvalidToolName` 不得以“从整段错误文本脆弱正则抽取”作为正式主路径。
2. 本轮必须明确由 client 侧产出该字段，而不是由 provider 在 repair 阶段临时反解析自由文本。
3. 本轮承载方式明确指定为：扩展现有 `LlmStructuredOutputException`，让它携带结构化异常上下文，而不是继续扩展异常文本格式，也不新增 provider/client 间的旁路包装层。
4. 具体要求是：在 `LlmStructuredOutputException` 上新增一个可选的结构化上下文字段，例如 review-agent 专用的 error-context record / DTO；`OpenAiCompatibleReviewAgentStructuredGenerationClient` 在发现 `unregistered_tool` 时，必须把上一轮非法 `toolName` 放入该上下文；`PromptBackedNextStepDecisionProvider` 只消费该结构化字段并传给 repair prompt builder。
5. 本轮必须定义一个具体的结构化上下文类型，不能只停留在“有结构化上下文”这一抽象表述。也就是说，要在类型层明确它的名字、字段和使用边界，而不是靠 `Map`、自由文本或临时约定传递。
6. 本轮不采用以下方案作为正式承载方式：
   - 继续扩展 `LlmStructuredOutputException` 的 message 文本格式
   - 由 provider 从 `errorMessage` / `rawOutput` 反解析原始 JSON，再回填 `previousInvalidToolName`
   - 在 client/provider 之间再包一层仅为传递 `previousInvalidToolName` 的旁路字符串协议
7. 这样做的原因是：`ReviewAgentStructuredGenerationPort` 现有失败通道已经是 `LlmStructuredOutputException`，在本轮最小兼容边界下，直接扩展异常对象本身是最小、最稳的接线点。
8. 如果当前 `LlmStructuredOutputException` 设计确实无法承载结构化上下文，则本轮必须先扩展异常对象本身；不接受退回文本拼接方案。
9. 可接受的降级只允许发生在“client 无法从本轮原始模型输出中稳定得到 `toolName`”的极端场景，而不是因为链路设计偷懒把结构化字段丢失。
10. 降级时 repair prompt 必须显式写：
   - `previousInvalidToolName: unavailable`
11. 降级时仍必须：
   - 给出 exact whitelist
   - 给出 forbidden alias 规则
   - 禁止模型发明新别名
12. 换言之，本轮允许“非法名不可用时降级”，但不允许“非法名来源机制缺省”。

### F. `unregistered_tool` repair 与现有 `entries repair` 的组合规则

这是本轮必须写死的 repair 组装边界，避免后续继续把 prompt 堆成多块半重叠规则。

组合规则如下：

1. `unregistered_tool` guidance 不是通用 repair 附件，只在错误类型命中 `unregistered_tool` 时单独注入。
2. 现有 `entries repair` guidance 只在错误类型命中 `invalid_argument:entries` 时单独注入。
3. 在当前实现路径下，这两类 guidance 预期互斥，不应把 `unregistered_tool` 和 `entries repair` 同时注入到同一轮 `structured_output_repair` prompt。
4. 原因是：
   - `unregistered_tool` 的首要目标是先把非法工具名收敛到一个精确已注册工具
   - `entries repair` 的首要目标是修复 `record_confirmed_terms` 的参数形状
   - 两者若同轮叠加，容易把 repair 目标从“单点修复”稀释成“同时重选工具并重修参数”
5. 如果未来出现错误文本同时包含多个信号，本轮优先级明确为：
   - 先处理 `unregistered_tool`
   - 只有当工具名已经合法、且当前错误明确是 `invalid_argument:entries` 时，才进入 `entries repair`
6. 也就是说，本轮 repair 设计遵循“先修工具协议，再修该工具参数”的顺序，而不是在同一轮混修。
7. 若后续真的出现需要组合注入的新路径，应另立一轮设计，不在本轮最小兼容方案中扩展。
8. 对应到实现上，`buildStructuredOutputRepairPrompt()` 必须从当前的“正文 + 专项 guidance 尾部追加”改成“按错误类型单选注入”的分支结构。
9. 也就是说，本轮实现要求必须是：
   - `if unregistered_tool -> 注入 unregistered_tool guidance`
   - `else if invalid_argument:entries -> 注入 entries repair guidance`
   - `else -> 不注入这两类专项 guidance`
10. 不允许实现成：
   - `basePrompt + nextStepEntriesCompatibilityRepairGuidance(...) + nextStepUnregisteredToolRepairGuidance(...)`
11. specialized guidance 禁止链式叠加，只允许按错误类型单选注入。
12. 这条是实现规则，不是文案偏好。目标是从代码层消除多块 repair guidance 叠加的机会。

### G. Decision Repair：本轮不纳入主修范围

当前真实故障主路径是：

- structured generation 返回未注册工具名
- client 在 `generateNextToolDecision()` 内部先打出 `unregistered_tool`
- provider 进入 `structured_output_repair`

因此本轮不把 `decision_repair` 的同类 guidance 作为最小兼容主修项。

后续只有在出现“DTO 已形成后才发现未注册工具”的新路径时，才考虑单独补这一层。

---

## 文件与改动范围

### 需要修改的文件

- `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
- `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java`
- 视是否保留弱提醒可选：
  - `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`

### 不应扩散修改的文件

本轮不应扩散到：

- `ReviewToolExecutor`
- `ReviewToolDecisionContractValidator`
- `ReviewToolRegistry` 的业务语义
- 运行态状态机
- 人工升级流程

如果需要更强 schema enum 约束，应另立一轮，不与本轮最小 prompt hardening 混做。

---

## 测试方案

### 必补回归 1：system prompt 必须包含显式工具白名单

目标：

- 断言 `ReviewAgentSystemPromptBuilder` 输出中包含 `[Registered Tool Names]`
- 包含精确工具列表
- 包含 `must be exactly one of the registered names`
- 包含禁止别名示例 `read_adjacent_chunks`

### 必补回归 2：schema / response-format 必须包含 exact whitelist 表述

目标：

- 断言 `toolName` description 或 schema description 包含：
  - `Allowed toolNames are exactly`
  - 至少一个精确工具名
  - 禁止 alias / merged names 的表述

### 必补回归 3：`unregistered_tool` repair prompt 必须带专项 guidance

目标：

- 模拟首轮 structured generation 抛：
  - `Review agent invalid structured tool decision: unregistered_tool`
- 检查第一个 `structured_output_repair` prompt 中包含：
  - `[unregistered_tool repair]`
  - `validationError: unregistered_tool`
  - `previousInvalidToolName`
  - `Allowed toolNames are exactly:`
  - `read_previous_chunks`
  - `read_next_chunks`
  - `read_adjacent_chunks`

### 必补回归 4：`unregistered_tool` repair 能收敛到已注册工具

目标：

- 第一轮返回未注册工具，例如 `read_adjacent_chunks`
- 第二轮返回合法工具，例如 `read_previous_chunks`
- 断言 provider 最终接受第二轮结果

### 必补回归 5：真实故障路径进入的是 `structured_output_repair`

目标：

- 当首轮是未注册工具名时，断言 repair prompt kind 是 `structured_output_repair`
- 不应错误落到 `decision_repair`

### 必补回归 6：多次 repair 仍输出未注册工具时预算耗尽失败

目标：

- 连续多轮返回未注册工具名
- 断言最终按既有预算耗尽路径失败
- 并保留 `unregistered_tool` 相关诊断信息

### 必补回归 7：whitelist 来自 registry 渲染，而不是硬编码常量

目标：

- 至少有一类测试能证明 prompt / schema / repair 使用的是 registry 渲染结果
- 避免 system / schema / repair 三处名单漂移

### 必补回归 8：拿不到非法工具名时的降级 repair 仍可诊断

目标：

- 模拟 `errorMessage` 里只有 `unregistered_tool`，没有可稳定解析的 `rawOutput`
- 断言 repair prompt 仍包含：
  - `validationError: unregistered_tool`
  - `previousInvalidToolName: unavailable`
  - exact whitelist
- 断言 repair prompt 不会伪造非法工具名

### 必补回归 9：`unregistered_tool` 与 `entries repair` 不会同轮混注入

目标：

- 当错误为 `unregistered_tool` 时，断言 repair prompt 只包含 `[unregistered_tool repair]`
- 当错误为 `invalid_argument:entries` 时，断言 repair prompt 只包含 `[entries repair]`
- 当前主路径下不应出现同一轮 prompt 同时包含两块 guidance

### 可选回归 10：schema description 仍保留现有工具说明

目标：

- 确保本轮 prompt hardening 不会误删当前 schema description 中已有的工具说明文本

### 必做迁移说明：旧测试预期需要同步更新

本轮不是只“补新测试”，还必须显式迁移已有断言。

需要同步更新的历史测试方向包括：

1. 任何断言 system prompt 不包含工具区块、或假定不存在显式工具白名单的测试，都必须改成与 `[Registered Tool Names]` 新结构一致。
2. 任何把 schema 对 `toolName` 的预期固定在 generic string 提示上的测试，都必须改成 exact whitelist 预期。
3. 任何假定 `structured_output_repair` 只有通用 repair 文案、不会出现 `unregistered_tool` 专项块的测试，都要按本轮新结构迁移。
4. 迁移目标必须在实现前先写明，避免实现时先被旧测试撞停，再临时逐个改断言。

---

## 实施顺序

### Task 1：修正文档与实现边界

先确保本文档本身可读、无乱码、边界清晰，并把 `previousInvalidToolName` 来源机制写实。

### Task 2：补 system prompt 白名单块

这是首轮预防的主修复点。

### Task 3：补 schema / response-format 的 exact whitelist 表述

与 Task 2 配套，作为机器可见的精确工具集合约束。

### Task 4：补 `structured_output_repair` 的 `unregistered_tool` guidance

这是 repair 收敛修复的核心。

### Task 5：可选保留 investigation prompt 的弱提醒

只保留一句引用提醒，不重复协议正文。

### Task 6：补回归测试

优先补：

- prompt 文案存在性
- schema exact whitelist 表述
- `unregistered_tool` repair prompt 内容
- 从未注册工具名收敛到合法工具名
- 多轮仍不收敛的预算耗尽路径
- `previousInvalidToolName` 不可用时的降级路径
- 旧测试预期迁移

### Task 7：迁移旧测试预期

这是独立任务，不包含在“补新测试”里。

本轮至少要显式迁移：

1. `shouldShrinkSystemPromptToLayerAWithoutLegacyAvailableToolsManual()` 这类把 system prompt 预期固定为“不包含工具区块”的旧测试。
2. system prompt 相关任何假定不存在 `[Registered Tool Names]` 的旧断言。
3. schema description 相关任何仍把 `toolName` 预期固定为 generic string 提示的旧断言。
4. repair prompt 相关任何假定只有通用 repair 文案、不会出现 `unregistered_tool` 专项块的旧断言。

当前已知必须点名迁移的冲突测试至少包括：

1. `ReviewPromptBuilderTest.shouldShrinkSystemPromptToLayerAWithoutLegacyAvailableToolsManual()`
2. 任何直接断言 system prompt 不包含工具白名单区块的同类测试
3. 任何直接断言 schema description 只包含 generic `registered in ReviewToolRegistry` 提示的同类测试

要求：

- 先识别并迁移旧预期，再补新测试
- 避免执行时先被历史断言绊住，再临时返工

---

## 风险与边界

### 风险 1：工具名白名单分散

如果 system / schema / repair 各自手写名单，后续 registry 改动时极易漂移。

这不是普通风险提示，而是本轮必须满足的实现要求：

- 所有 whitelist 文案必须由 `ReviewToolRegistry` 单源生成
- system prompt 用完整名单
- schema description 用同一份名单
- `structured_output_repair` 命中 `unregistered_tool` 时局部注入同一份名单

### 风险 2：previousInvalidToolName 退化成脆弱文本解析

如果本轮没有先定义来源机制，后续实现大概率会退化成：

- 从 `errorMessage` 文本里找 `rawOutput=...`
- 再从 raw JSON 字符串里临时抽 `toolName`

这不适合作为长期稳定协议。

因此本轮要求：

- 结构化传递优先
- 文本解析最多作为过渡或降级分支
- 降级时必须显式标注 `unavailable`

### 风险 3：repair guidance 继续堆叠，反而削弱定向修复

如果不把 `unregistered_tool` 与 `entries repair` 的注入条件和优先级写死，后续实现很容易继续叠块：

- 既要求重选工具
- 又要求同时修 `entries`
- 再混入通用 repair 解释

这会直接削弱本轮“先收敛到合法工具名”的目标。

因此本轮要求：

- `unregistered_tool` 与 `entries repair` 预期互斥
- 先修工具协议，再修参数形状
- 需要组合修复时另立设计

### 风险 4：白名单过长，导致 prompt 噪音上升

当前工具数仍可控，且这次是高优先级协议约束，收益大于噪音成本。

建议：

- system prompt 放唯一完整长版
- investigation prompt 最多只保留一句引用提醒
- repair prompt 只在 `unregistered_tool` 时局部重复

### 风险 5：把这轮工作扩展成 schema / validator 重构

这是本轮明确禁止的扩散。

本轮边界是：

- 提升模型对合法工具集合的可见性
- 提升 `unregistered_tool` repair 的可收敛性

不是：

- 改写整个 tool protocol
- 改造 response schema 编码模型

---

## 责任边界

1. system prompt：定义全局工具协议、完整 whitelist、禁止 alias / merged-name / paraphrase。
2. investigation prompt：仅提醒当前轮次遵守协议，不重定义协议正文。
3. schema / response format：提供机器可见的 exact whitelist 与参数摘要。
4. structured_output_repair：修复 parse / schema / pre-validation 阶段失败，尤其是 `unregistered_tool` 的定向修复。
5. `previousInvalidToolName` 的结构化产出：由 structured generation client 侧负责稳定带出，不由 provider 从自由文本反解析。
6. decision_repair：处理 DTO 已形成后的业务参数错误；本轮不承担当前 `unregistered_tool` 主修。
7. validator：输出稳定错误类型；不是根因，但其错误信息会直接影响 repair 可操作性。
8. executor：拒绝非法调用，但不承担协议教育职责。

---

## 结论

本轮应采用最小兼容 hardening：

1. 在 output contract 邻近区域显式列出合法工具白名单。
2. 在 schema / response-format 中把 `toolName` 允许集合表达成 exact whitelist。
3. 在 `structured_output_repair` 中加入 `unregistered_tool` 专项 guidance，并强制回显 `previousInvalidToolName`。
4. `previousInvalidToolName` 必须有明确来源机制；若不可用，必须显式降级为 `unavailable`。
5. investigation prompt 如保留，只保留一句弱提醒。
6. 用窄回归测试锁住：
   - 白名单显式存在
   - schema exact whitelist 表述存在
   - repair prompt 显式给出合法工具名集合
   - repair prompt 显式给出 `previousInvalidToolName`
   - `read_adjacent_chunks` 这类未注册别名能够被修回合法工具名
   - 拿不到非法工具名时 repair 仍可诊断
   - 多轮 repair 仍不收敛时按既有预算耗尽失败

这能更准确地命中当前故障根因，同时保持现有 prompt / schema / repair / validator / executor 分层不变。
