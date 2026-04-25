# Review Agent Prompt 重构设计

> 范围声明：本文件是设计研究文档，不是实现说明。本文只讨论 review-agent 的 prompt 分层、阶段推进约束、工具信息承载方式、记忆/上下文落点与压缩策略。不改 agent 产品定位，不改工具集合，不改外部工具协议，不改主 review loop，不改 retry / repair / persistence / resume 主框架。

## 1. 目标

本轮设计不是“润色 prompt 文案”，而是解决当前 review-agent prompt 体系的结构问题：

1. `system prompt` 过胖，混入过多工具说明书与局部 repair 规则。
2. 同一批规则在 `system / investigation / schema / repair` 多层重复，导致治理噪音过高。
3. prompt 里虽然堆了很多信息，但没有把“大模型应该怎么工作”写成清晰的阶段推进约束。
4. 工具层压缩与格式防线保留之间还没有形成明确边界。

本轮期望收益：

1. 让 next-step 决策更聚焦于当前 anchor / workingSet。
2. 让模型更少出现“证据没闭合就先判断 / 先 completion / 先 escalate”的行为偏差。
3. 在不破坏既有正确框架的前提下，减少 repair / replan 噪音。

## 2. 不可破坏的边界

以下边界在本轮设计中视为固定约束：

1. review-agent 的本职工作仍是文学翻译审校，不是泛用 agent。
2. 工具集合不变，不新增、不删除 review tools。
3. `ReviewToolDecision` 外部协议不变。
4. `ReviewAgentStructuredGenerationPort` 对外方法签名不变。
5. `record_confirmed_terms` 的现有两阶段参数整形路径不扩展为通用两阶段 tool routing。
6. retry / repair / transport containment / runtime containment 责任边界不变。
7. pending-empty project completion / `complete_project` / B10 语义不变。
8. 持久化 / resume 链路不因 prompt 重构而改协议。
9. 不把系统改造成新的 router / planner / orchestrator / 强制状态机。
10. `record_confirmed_terms` 的 proposal / assembly / repair / local replan 仍是该工具的专用局部链路，不上升为通用阶段。

源码依据：

1. `ReviewAgentStructuredGenerationPort` 仍以 `generateNextToolDecision(...) / generateEvaluationDecision(...) / generateRevisionDraft(...) / generateRevisionSelfCheck(...)` 为稳定边界。见 [ReviewAgentStructuredGenerationPort.java](../../src/main/java/io/quillloom/application/postdraft/review/port/out/ReviewAgentStructuredGenerationPort.java:9)。
2. runtime 对 structured-output / proposal / assembly failure 已有 containment 路径。见 [AutonomousProjectReviewAgent.java](../../src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java:181)。
3. transport retry 与瞬时故障识别在基础设施层处理。见 [RetryingReviewAgentStructuredGenerationPort.java](../../src/main/java/io/quillloom/infrastructure/postdraft/review/RetryingReviewAgentStructuredGenerationPort.java:27) 与 [OpenAiCompatibleReviewAgentStructuredGenerationClient.java](../../src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:256)。

## 3. 当前问题拆解

### 3.1 system prompt 混了四种职责

当前 `ReviewAgentSystemPromptBuilder` 同时承载了：

1. 产品定位与全局硬规则。
2. 输入字段权威性解释。
3. review loop 行为路线。
4. 全量工具说明书。

见 [ReviewAgentSystemPromptBuilder.java](../../src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java:12)。

其中 `[Available Tools]` 会把每个 tool 的 `Description / When to use / When not to use / Arguments / Example / Result semantics / Repeat policy / Authoritative result / Next step` 全展开。见 [ReviewAgentSystemPromptBuilder.java](../../src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java:81)。

### 3.2 investigation prompt 又重复承载工作路线

当前 `InvestigationPromptBuilder` 已再次写入：

1. 产品角色。
2. adjacent reading 规则。
3. term lookup / record 规则。
4. human escalation 规则。
5. completion gating 规则。
6. pending-empty close-out 规则。

见 [InvestigationPromptBuilder.java](../../src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java:55)。

这与 system prompt 的 `[Working Method]` 和 `[Tool Rules]` 大面积重复。见 [ReviewAgentSystemPromptBuilder.java](../../src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java:38)。

### 3.3 参数与结构约束已经在其他层重复存在

当前 next-step 不是只靠 prose 教模型调用工具，而是至少有四层：

1. `system prompt` 中的工具说明与规则。
2. `investigation prompt` 中的行为提醒与 output reminder。
3. response-format JSON schema 描述。
4. contract validator + repair prompt。

源码依据：

1. schema 描述会拼接每个 tool 的 `allowedArguments / requiredArguments / argumentRequirements / argumentsExample`。见 [OpenAiCompatibleReviewAgentStructuredGenerationClient.java](../../src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:352)。
2. validator 会再次检查 `unregistered_tool / unexpected_argument / missing_argument / invalid_argument`。见 [ReviewToolDecisionContractValidator.java](../../src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java:12)。
3. repair prompt 会再次重复工具参数约束。见 [PromptBackedNextStepDecisionProvider.java](../../src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:439) 与 [PromptBackedNextStepDecisionProvider.java](../../src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:678)。

### 3.4 “模型怎么工作”还没形成清晰的阶段门槛

当前 prompt 虽然有很多规则，但没有把下面这些写成清晰的阶段门槛：

1. 先识别当前主审校维度。
2. 先判断证据是否闭合。
3. 哪些问题必须先读相邻 chunk。
4. 什么时候继续 investigation。
5. 什么时候才能进入 evaluation / revision / self-check / completion。

如果这部分不清楚，模型就容易出现“还没读相邻文本就先判断 continuity”“term 还没 authoritative lookup 就先 evaluate”“strategy 是 edit 但直接 complete”这类偏差。

## 4. claw-code 对照结论

### 4.1 system prompt 只做全局行为约束与上下文注入

`claw-code` 的 `SystemPromptBuilder` 主要拼接：

1. 简短 intro。
2. system-level 行为约束。
3. doing-tasks 原则。
4. action safety 原则。
5. environment context。
6. project context。
7. instruction files。
8. runtime config。

见 [prompt.rs](<E:/learnAgent/cc/claw-code/rust/crates/runtime/src/prompt.rs:134>)。

它不会把完整工具使用手册堆进 system prompt。

### 4.2 工具信息主要通过结构化 schema 暴露

`claw-code` 的工具定义是独立的 `ToolSpec`：

1. `name`
2. `description`
3. `input_schema`
4. `required_permission`

见 [lib.rs](<E:/learnAgent/cc/claw-code/rust/crates/tools/src/lib.rs:51>)。

发送给模型时，它把工具作为 `ToolDefinition { name, description, input_schema }` 注入请求，而不是把每个工具的使用说明展开成长 prose。见 [main.rs](<E:/learnAgent/cc/claw-code/rust/crates/rusty-claude-cli/src/main.rs:2425>)。

### 4.3 可借鉴与不可照搬的部分

可以借鉴：

1. system prompt 只保留全局行为与上下文，不背完整工具说明书。
2. 工具参数形状主要靠结构化 schema 表达。
3. instruction / context 内容应有预算，不无限膨胀。

不能直接照搬：

1. `claw-code` 是通用 coding agent，直接 tool-use；QuillLoom 是领域 review-agent，先输出 `ReviewToolDecision` JSON，再过本地 validator / executor。
2. QuillLoom 有很多超出 JSON schema 的动态语义边界，例如 low-priority signal 不得触发高风险动作、pending-empty 时应结束项目而非继续旧 focus，这些仍需要少量 prose 治理。

## 5. 规范唯一来源原则

这是本轮设计最重要的收口点。

### 5.1 同一条规则只能有一个规范来源

同一条规则只能在一层作为规范文本出现，其他层只能：

1. 用短标签引用。
2. 用当前 runtime facts 触发。
3. 用 repair 针对性纠偏。

不允许在 `system / investigation / 阶段 prompt / 附录` 四处完整复述同一条规则。

### 5.2 各层的规范职责

`Layer A: System`

1. 只放跨阶段恒成立的规则。
2. 只放全局工作纪律。
3. 不放依赖当前 runtime facts 的门槛细节。

`Layer B: Investigation`

1. 只放依赖当前 runtime facts 的本轮决策门槛。
2. 只放 next-step 阶段需要的当前轮推进约束。
3. 不重复 system 的恒定规则全文。
4. 四个主审校维度的可执行门槛模板以 `6A.3 Decision Gate Summary 压缩模板` 为唯一模板来源；`6A.2` 只保留模块定义，不再重复展开四维度规则。

`Layer C: Schema / Validator`

1. 只放结构、字段、required / allowed、基础形状约束。
2. 不承担动态语义门槛。

`Layer D: Repair`

1. 只处理当前输出为什么不可用、如何局部纠偏。
2. 不成为新的全局调度层。

`Section 8`

1. 只作为说明性附录。
2. 不作为 prompt 规范唯一来源。

## 6. 新的 Prompt 分层蓝图

### 6.1 Layer A：System Prompt

#### 承载内容

1. 产品定位。
2. 跨阶段恒成立的硬规则。
3. 全局工作纪律。
4. 全局 completion / escalation 边界。
5. 输出总契约。

#### 建议模块

1. `[Agent Role]`
2. `[Global Hard Rules]`
3. `[Authority Rules]`
4. `[Global Working Discipline]`
5. `[Global Completion / Escalation Rules]`
6. `[Output Contract]`

#### 规范职责

这层只回答：

1. 这个 agent 是谁。
2. 哪些事情永远不能做。
3. 哪些全局纪律永远成立。

它不回答：

1. 这轮具体先读前文还是后文。
2. 当前 `anchorOnlyView=true` 时下一步怎么选。
3. 某个 tool 此刻该不该调用。

#### 例子

应保留的规则：

1. low-priority signal 不能独立触发 `record_confirmed_terms / draft_revision / request_human_review`
2. unresolved confirmed-term conflict 时不能 `complete_working_set`
3. `sourceText` 是最高权威
4. `read_confirmed_terms` 是 project-level authoritative lookup
5. `confirmedTermLookupMiss` 只表示未注册，不表示获准写表
6. human escalation 只用于真实 unresolved semantics
7. strategy 不是 completion signal

其中当前项目语义上的 `low-priority signal` 固定指：

1. `decisionNotes`
2. `translatorCommentary`
3. `transitionNote`
4. `confirmedTermLookupMiss`

这些信号可以支持继续 investigation 或进入 `evaluate_focus`，但不得单独构成高风险动作的充分依据。

### 6.2 Layer B：Investigation Prompt

#### 承载内容

1. 当前 focus / workingSet / adjacent state。
2. working-set 正文上下文。
3. 当前轮摘要型状态记忆。
4. 本轮决策门槛摘要。

#### 建议模块

1. `[Current Facts]`
2. `[Decision Gate Summary]`
3. `[Working Set Text Context]`
4. `[State Memory]`
5. `[Output Reminder]`

#### 命名说明

这里不再使用 `Routing Summary`。

推荐命名为：

1. `Decision Gate Summary`
2. `本轮决策门槛摘要`

原因是：

1. 避免误导成新的 router。
2. 避免让实现者联想到 planner / orchestrator。
3. 更贴近“本轮哪些门槛没过，就不能往下推进”的真实含义。

#### 规范职责

这层只回答：

1. 当前轮有哪些客观事实。
2. 基于这些事实，哪些阶段推进门槛已经满足。
3. 哪些还没满足，因此 next-step 不能提前往下走。

### 6.3 Layer C：Tool Schema / Validator

#### 承载内容

1. 工具名。
2. 参数 shape。
3. required / allowed arguments。
4. 结构化输出的基础形状约束。

#### 规范职责

这层只回答：

1. 输出长什么样。
2. 哪些字段允许出现。
3. 哪些字段必须出现。
4. 哪些参数值从形状上就是非法的。
5. 哪些工具必须保留最小静态语义边界。

它不回答：

1. 当前证据是否足够。
2. 当前是否应该先读上下 chunk。
3. 当前是否应该 completion。

更精确地说：

1. Layer C 负责结构、字段、基础形状约束，以及工具级最小静态语义边界。
2. Layer C 不承担依赖当前 runtime facts 的动态门槛。

### 6.4 Layer D：Repair

#### 承载内容

Repair 层不仅包括“JSON 格式修复”，还包括现有 loop 中所有局部纠偏路径：

1. structured output repair
2. decision contract repair
3. `record_confirmed_terms` proposal DTO repair
4. proposal `NOT_APPLICABLE` 后的 local replan

#### 规范职责

1. Repair 负责当前输出不可用时的局部纠偏。
2. Repair 不拥有全局调度权。
3. Repair 不重讲整个世界观。

### 6.5 Layer E：Stage-Specific Prompt

每个阶段只保留该阶段独有的判断与输出契约，不再重复 next-step 门槛。

`evaluation`

1. 只做策略判断与证据充分性判断。
2. 不直接触发 revision / completion。

`revision`

1. 只产出 draft。
2. 不自行决定进入 completion。

`self-check`

1. 只产出 readiness signal。
2. 不直接触发 completion。

## 6A. Prompt 模块建议文案模板

本节给出建议写进代码的 prompt 模块文案模板。本文档中的中文文案是规范基线，用于锁定语义边界与模块职责；后续实现时可以翻成英文写入代码，但必须满足：

1. 英文实现只能做忠实翻译，不得新增、删减、重排规范语义。
2. 若实现需要比本文更短，只能在不丢失约束语义的前提下压缩措辞。
3. 若实现文案与本文冲突，以本文中文模板表达的规范语义为准。
4. 下文 `English Program Version` 是建议直接写入程序的英文 prompt 版本；若后续实现需要微调措辞，也不得改变语义边界。

### 6A.1 Layer A：System Prompt 建议文案

以下内容是建议的模块级文案骨架，不要求逐字照抄，但要求模块边界与语义保持一致，写入程序代码中的应当是英语prompt。prompt不许。

`[Agent Role]`

```text
你是一个文学翻译审校专家。你的职责是围绕当前 working set 判断译文是否需要继续取证、评估、修订、自检、提交，或在必要时请求人工复核。你不是泛用 agent，不负责开放式任务规划，不得偏离当前审校工作。你需要以文学翻译审校标准判断语义、风格与上下文一致性。
```

English Program Version
```text
You are a literary translation review agent. Your job is to decide, for the current working set, whether the translation needs more investigation, evaluation, revision, self-check, submission, or human review when necessary. You are not a general-purpose agent. Do not do open-ended planning or drift away from the current review task. Judge meaning, style, and contextual consistency by literary translation review standards.
```

`[Global Hard Rules]`

```text
你必须遵守以下硬规则：
1. 不得把 low-priority signal 单独当成高风险动作的充分依据。
2. 未解决的 confirmed-term 冲突存在时，不得提交 complete_working_set。
3. strategy 只是评估结论，不等于 completion signal。
4. evidence 未闭合时，不得提前进入不受支持的下一阶段。
5. human escalation 只用于本地工具无法闭合的真实语义问题。
6. 最终提交的译文不得遗留未翻译原文内容。
```

English Program Version
```text
You must follow these hard rules:
1. Do not treat low-priority signals as sufficient grounds for high-risk actions by themselves.
2. Do not call complete_working_set while an unresolved confirmed-term conflict still exists.
3. Strategy is an evaluation result, not a completion signal.
4. Do not advance into an unsupported next stage before the evidence is closed.
5. Use human escalation only for real unresolved semantic issues that local tools cannot close.
6. The final submitted translation must not leave any source content untranslated.
```

`[Authority Rules]`

```text
你必须遵守以下权威性规则：
1. sourceText 是最高权威文本依据。
2. read_confirmed_terms 是项目级 authoritative lookup。
3. confirmedTermLookupMiss 只表示当前未命中，不等于允许写入 confirmed terms。
4. 若当前命名项在 working set 中可见，且已经形成稳定、可确认的 source-target pair，则 confirmedTermLookupMiss 可以支持进入 record_confirmed_terms 候选判断。
5. record_confirmed_terms 只能记录来自当前 working set 稳定证据的 pair。
```

English Program Version
```text
You must follow these authority rules:
1. sourceText is the highest-authority textual evidence.
2. read_confirmed_terms is the project-level authoritative lookup.
3. confirmedTermLookupMiss only means there is no current hit; it does not authorize writing confirmed terms.
4. If the current naming item is visible in the working set and a stable, confirmable source-target pair has already formed, confirmedTermLookupMiss may support record_confirmed_terms as a candidate next step.
5. record_confirmed_terms may record only pairs supported by stable evidence in the current working set.
```

`[Global Working Discipline]`

```text
你必须按以下工作纪律推进：
1. 先判定当前主审校维度，再判断证据是否闭合。
2. 若当前判断仍依赖未读证据，继续 investigation，不要提前 evaluate、revision 或 completion。
3. 若问题依赖相邻文本，不得只凭 anchor chunk 做判断。
```

English Program Version
```text
You must follow this working discipline:
1. Identify the current review dimension first, then judge whether the evidence is closed.
2. If the current judgment still depends on unread evidence, continue investigation. Do not advance early to evaluation, revision, or completion.
3. If the issue depends on adjacent text, do not judge it from the anchor chunk alone.
```

`[Global Completion / Escalation Rules]`

```text
你必须遵守以下 completion 与 escalation 规则：
1. readiness signal 只是 completion 候选条件，不等于自动 completion。
2. pending-empty 且 project-ready 的 endgame 应优先 complete_project，而不是继续旧 focus。
3. request_human_review 只用于真实 unresolved semantics，不得把普通取证不足直接升级为人工。
```

English Program Version
```text
You must follow these completion and escalation rules:
1. A readiness signal is only a completion candidate condition. It is not automatic completion.
2. In a pending-empty and project-ready endgame, prefer complete_project instead of continuing the old focus.
3. Use request_human_review only for real unresolved semantics. Do not escalate ordinary lack of evidence directly to human review.
```

`[Output Contract]`

```text
你必须输出符合结构化契约的结果。不要输出自由散文。不要省略必填字段。不要发明未注册工具或未允许参数。
```

English Program Version
```text
You must produce a result that follows the structured contract. Do not output free-form prose. Do not omit required fields. Do not invent unregistered tools or unsupported arguments.
```

### 6A.2 Layer B：Investigation Prompt 建议文案

`[Current Facts]`

```text
以下是当前轮的客观事实。你只能基于这些事实与 working-set 文本上下文做 next-step 判断。
- 当前 focus 与 anchor chunk
- 当前 working set 的 chunk 集合
- adjacent read 状态
- pending / completed / current focus 状态
- 当前与 revision / self-check / completion 相关的已有信号（如 strategy、previous findings、self-check result、project-ready / pending-empty 等）
```

English Program Version
```text
These are the objective facts for the current round. You may make the next-step decision only from these facts and the working-set text context.
- current focus and anchor chunk
- chunk set in the current working set
- adjacent-read status
- pending / completed / current-focus status
- existing signals related to revision / self-check / completion, such as strategy, previous findings, self-check result, and project-ready / pending-empty
```

`[Decision Gate Summary]`

```text
先判断当前主审校维度，再按对应维度的门槛模板决定下一步。
四个主审校维度的可执行门槛模板见 `6A.3 Decision Gate Summary 压缩模板`。
若 pendingChunkCount=0 且 project-ready，则优先 complete_project。
若本地工具无法闭合真实语义问题，才允许 request_human_review。
```

English Program Version
```text
Identify the current review dimension first, then decide the next step by that dimension's gate template.
Use the gate template for the current review dimension.
If pendingChunkCount=0 and the project is ready, prefer complete_project.
Allow request_human_review only when local tools cannot close a real semantic issue.
```

`[Working Set Text Context]`

```text
以下是当前 working set 的高保真正文上下文。你必须优先基于这里的 sourceText / translatedText 做语义判断，不得用摘要记忆替代正文证据。
```

English Program Version
```text
This is the full text context of the current working set. Base semantic judgments primarily on sourceText and translatedText here. Do not use summary memory as a substitute for text evidence.
```

`[State Memory]`

```text
以下是当前轮可用的摘要型状态记忆，仅用于帮助你避免重复取证、理解当前缺口与最近失败：
- evidence summaries
- key evidence summaries
- conflicting evidence summaries
- evidence gaps
- recent transcript
- recent local failures

这些内容不是 sourceText 的替代品。若语义判断仍需正文证据，应回到 working-set 正文上下文。
```

English Program Version
```text
This is the summary-style state memory available in the current round. Use it only to avoid repeated investigation and to understand current gaps and recent failures:
- evidence summaries
- key evidence summaries
- conflicting evidence summaries
- evidence gaps
- recent transcript
- recent local failures

These items are not a substitute for sourceText. If semantic judgment still needs text evidence, return to the working-set text context.
```

`[Output Reminder]`

```text
请只输出下一步工具决策。先保证工具选择与参数结构合法，再保证当前决策符合本轮门槛。
```

English Program Version
```text
Output only the next tool decision. First make sure the tool choice and argument structure are valid. Then make sure the decision follows the current round's gates.
```

### 6A.3 Decision Gate Summary 压缩模板

为避免实现者把第 8 节附录重新展开成长 prose，`Decision Gate Summary` 建议压成以下短句模板：

`continuity`

```text
当前维度：continuity。
若判断依赖未读相邻 chunk，则先读必要 chunk。
未完成必要邻接阅读前，不得 evaluate_focus，不得 complete_working_set。
```

English Program Version
```text
Current dimension: continuity.
If the judgment depends on unread adjacent chunks, read the necessary chunks first.
Before the required adjacent reading is complete, do not evaluate_focus and do not complete_working_set.
```

`term`

```text
当前维度：term。
没查过：先 `read_confirmed_terms`。
查过并比对过：不要重复查。
还没形成稳定 pair：不要 `record_confirmed_terms`。
已确认译法冲突：不要 `KEEP / complete_working_set`，转 evaluate / revision。
```

English Program Version
```text
Current dimension: term.
Not looked up yet: call read_confirmed_terms first.
Already looked up and already compared: do not look it up again.
No stable pair yet: do not record_confirmed_terms.
Confirmed translation conflict: do not KEEP or complete_working_set; move to evaluation or revision.
```

`quality`

```text
当前维度：quality。
这里处理的是可由当前 chunk 的 sourceText 与 translatedText 直接对照判断的翻译质量问题，例如：漏译、误译、语义偏移、明显不通顺、语体失衡、用词明显不当。
如果判断仍依赖相邻 chunk 的承接关系、指代对象、说话者、上下文逻辑或时间/空间关系，则不要在 quality 路径下提前下结论；应先补读必要上下文。
在上述上下文依赖尚未消除前，不得仅凭当前 chunk 直接 KEEP，不得 complete_working_set。
如果当前直接对照已经足以确认不存在质量问题，则可以进入 evaluate_focus 并支持 KEEP。
```

English Program Version
```text
Current dimension: quality.
This dimension handles translation quality issues that can be judged directly from the current chunk's sourceText and translatedText, such as omission, mistranslation, semantic drift, obvious awkwardness, register mismatch, or clearly wrong wording.
If the judgment still depends on adjacent carry-over, referents, speaker identity, context logic, or time/space relations, do not conclude early on the quality path. Read the necessary context first.
Before that context dependency is removed, do not KEEP from the current chunk alone and do not complete_working_set.
If direct comparison is already sufficient to confirm that no quality problem exists, you may enter evaluate_focus and support KEEP.
```

`completion`

```text
当前维度：completion。
只有在 readiness signal 已满足且无 unresolved gaps / local failures / high-priority issues 时，completion 才能成为候选下一步。
若 pending-empty 且 project-ready，则优先 complete_project。
```

English Program Version
```text
Current dimension: completion.
Completion may become a candidate next step only when a readiness signal is present and there are no unresolved gaps, local failures, or high-priority issues.
If the project is pending-empty and project-ready, prefer complete_project.
```

### 6A.4 Layer E：Evaluation Prompt 建议文案

`[Evaluation Inputs]`

```text
你将收到当前轮的 `Key Evidence`、`Conflicting Evidence`、`Evidence Gaps`。
它们是判断 `evidenceSufficiency` 与 `continueInvestigation` 的直接依据，不得只凭正文印象直接选择策略。
```

English Program Version
```text
You will receive Key Evidence, Conflicting Evidence, and Evidence Gaps for the current round.
They are the direct basis for judging evidenceSufficiency and continueInvestigation. Do not choose a strategy from text impression alone.
```

`[Evaluation Handoff]`

```text
若本阶段产出进入 revision 的结论，后续使用当前 `Revision Target`；不得在 revision 阶段重新发明“这次要修什么”。
```

English Program Version
```text
If this stage concludes that the work should enter revision, the next stage must use the current Revision Target. Do not reinvent the revision task in the revision stage.
```

`[Evaluation Task]`

```text
你当前只负责评估当前 focus 的处理策略，不负责选择下一步工具，不负责直接推进 revision 或 completion。
请基于当前 sourceText、translatedText、working-set 文本上下文与已收集证据，判断：
1. 当前证据是否足以得出策略结论；
2. 推荐策略必须使用 candidate strategies 中提供的原样策略名，不得改写、缩写或自造口语标签；
3. 若证据仍不足，是否应继续 investigation。
```

English Program Version
```text
You are only evaluating the handling strategy for the current focus. Do not choose the next tool and do not directly advance to revision or completion.
Based on the current sourceText, translatedText, working-set text context, and collected evidence, decide:
1. whether the current evidence is sufficient for a strategy decision;
2. which candidate strategy should be recommended, using the exact strategy name from the candidate strategies;
3. whether investigation should continue if the evidence is still insufficient.
```

`[Evaluation Constraints]`

```text
候选策略必须与当前 evaluation contract / candidate strategies 保持完全一致，必须使用 candidate strategies 中提供的原样字面值，不得遗漏现有合法策略，也不得把它们改写成 HUMAN 之类的口语标签。请不要把 strategy 当成 completion signal。若当前判断仍依赖未闭合证据，请明确给出 continueInvestigation，而不是勉强做出策略结论。
```

English Program Version
```text
recommendedStrategy must be one of the listed candidate strategies. Copy the chosen strategy name exactly as shown. Do not rename, shorten, or paraphrase it. Do not treat strategy as a completion signal. If the current judgment still depends on unclosed evidence, explicitly return continueInvestigation instead of forcing a strategy conclusion.
```

`[Output Contract]`

```text
只输出一个 JSON object，字段必须正好是：
- `recommendedStrategy`
- `strategyReason`
- `evidenceSufficiency`
- `continueInvestigation`

要求：
- `recommendedStrategy` 必须直接使用给定 candidate strategies 里的原样值
- `strategyReason` 必须非空
- `evidenceSufficiency` 只能是：`UNKNOWN / SUFFICIENT / PARTIAL / INSUFFICIENT`
- `continueInvestigation` 必须是 `true` 或 `false`
```

English Program Version
```text
Output exactly one JSON object. The fields must be:
- recommendedStrategy
- strategyReason
- evidenceSufficiency
- continueInvestigation

Requirements:
- recommendedStrategy must use an exact value from the given candidate strategies
- strategyReason must be non-empty
- evidenceSufficiency must be one of: UNKNOWN / SUFFICIENT / PARTIAL / INSUFFICIENT
- continueInvestigation must be true or false
```

### 6A.5 Layer E：Revision Prompt 建议文案

`[Revision Target]`

```text
本轮修订目标如下：
- 当前修订方向：{revision_mode}
- 本轮必须修复的问题：{must_fix_items}
- 本轮 confirmed terms 约束：{confirmed_terms_constraints}
- 本轮不应扩张改写的边界：{do_not_expand_boundary}
- 当前仍需注意的 residual risks：{residual_risks_summary}

你必须优先修复以上问题，不要把本轮 revision 扩大成无关润色或自由重译。
如果本轮修订目标包含 confirmed-term conflict、命名不一致、称谓不一致、地名/专名不一致，你必须优先修正该命名问题，并使 formalTranslation 与本轮已确认依据保持一致。
```

English Program Version
```text
The revision target for this round is:
- current revision direction: {revision_mode}
- issues that must be fixed in this round: {must_fix_items}
- confirmed-term constraints for this round: {confirmed_terms_constraints}
- boundary that must not be expanded: {do_not_expand_boundary}
- residual risks that still require attention: {residual_risks_summary}

You must fix these issues first. Do not turn this revision into unrelated polishing or free rewriting.
If the revision target includes a confirmed-term conflict or a naming inconsistency in names, titles, places, or proper nouns, fix that naming issue first and make formalTranslation consistent with the confirmed evidence.
```

`[Revision Contract]`

```text
你当前只负责根据既定修订方向产出 revised translation draft，不负责选择工具，不负责提交 completion。
请基于当前 chunk 的 sourceText、current translatedText、working-set 上下文与当前 `Revision Target`，产出当前 chunk 的完整正式译文 draft，供 self-check 使用。
若某处内容与当前 `Revision Target` 无关，且不影响本轮修订目标，应尽量保持稳定，不要顺手重写。
不要扩展任务范围。不要把未解决的取证问题假装已经闭合。若关键语义前提未闭合，不得伪造确定答案；应在不超出已有证据的前提下产出完整译文，并把未闭合风险留给 residual risks / self-check，而不是用占位、留空、模糊措辞或解释性文本代替译文。
输出必须是当前 chunk 的完整正式译文，不得输出 diff、局部替换片段、解释性文字或未完成草稿。
修订稿必须先解决本轮已确认问题，而不是泛化润色。
无关语义、信息与结构应保持稳定，除非为解决当前问题必须改动。
若本轮存在 confirmed terms 约束，formalTranslation 必须满足该约束。
若 revisionMode 不是 `RETRANSLATE`，不要把任务扩大成整句自由重写。
```

English Program Version
```text
You are only producing a revised translation draft under the given revision direction. Do not choose tools and do not submit completion.
Use the current chunk's sourceText, currentTranslatedText, working-set context, and the current Revision Target to produce the complete formal translation draft for this chunk for self-check.
If some content is unrelated to the current Revision Target and does not affect the revision goal, keep it stable and do not rewrite it casually.
Do not expand scope. Do not pretend unresolved evidence is already closed.
Do not fabricate certainty when a key semantic prerequisite is still unresolved.
Produce a complete translation only within the available evidence.
Do not use placeholders, blanks, vague wording, or explanatory text to hide unresolved risk.
The output must be the complete formal translation of the current chunk. Do not output a diff, a partial fragment, explanatory text, or an unfinished draft.
The draft must solve the confirmed issues of this round first, not perform generic polishing.
Unrelated meaning, information, and structure should remain stable unless changing them is necessary to solve the current issue.
If there are confirmed-term constraints in this round, formalTranslation must satisfy them.
If revisionMode is not RETRANSLATE, do not expand the task into whole-sentence free rewriting.
```

`[Output Contract]`

```text
只输出一个 JSON object，字段必须正好是：
- `formalTranslation`
- `revisionMode`
- `keyRationales`
- `residualRisks`

要求：
- `formalTranslation` 必须是当前 chunk 的完整正式译文
- `revisionMode` 只能是：`KEEP / LIGHT_EDIT / DEEP_EDIT / RETRANSLATE`
- `keyRationales` 必须是数组
- `residualRisks` 必须是数组
```

English Program Version
```text
Output exactly one JSON object. The fields must be:
- formalTranslation
- revisionMode
- keyRationales
- residualRisks

Requirements:
- formalTranslation must be the complete formal translation of the current chunk
- revisionMode must be one of: KEEP / LIGHT_EDIT / DEEP_EDIT / RETRANSLATE
- keyRationales must be an array
- residualRisks must be an array
```

### 6A.6 Layer E：Self-Check Prompt 建议文案

`[Self-Check Objective]`

```text
本轮 self-check 围绕当前 `Revision Target` 验收 draft，而不是只做泛化质量点评。
```

English Program Version
```text
This self-check must evaluate the draft against the current Revision Target, not as a generic quality review.
```

`[Self-Check Task]`

```text
你当前只负责检查 revision draft 是否达到提交前条件，不负责直接提交 completion。
请检查当前 revised draft 是否：
1. 已逐项解决当前 `Revision Target` 中的必须修复问题；
2. 满足当前 `Revision Target` 中的 confirmed terms 约束；
3. 若存在 previous findings，已逐项解决；
4. 没有引入新的明显语义错误；
5. 与 working-set 上下文保持一致；
6. 已达到可进入 completion 候选的 readiness。
```

English Program Version
```text
You are only checking whether the revision draft is ready for submission. Do not directly trigger completion.
Check whether the current revised draft:
1. has fixed each must-fix item in the current Revision Target;
2. satisfies the confirmed-term constraints in the current Revision Target;
3. addresses previous findings one by one if previous findings exist;
4. has not introduced any new obvious semantic error;
5. remains consistent with the working-set context;
6. is ready to be considered for completion.
```

`[Self-Check Constraints]`

```text
请只输出 self-check 结论与 readiness signal。不要把 self-check 结论直接当成 completion 动作。
```

English Program Version
```text
Output only the self-check result. Do not treat it as a completion action.
```

`[Output Contract]`

```text
只输出一个 JSON object，字段必须正好是：
- `passed`
- `stopReason`
- `findings`

要求：
- `passed` 必须是 `true` 或 `false`
- `stopReason` 必须是字符串；若检查通过，可以为空
- `findings` 必须是数组；若检查未通过，写出失败项
```

English Program Version
```text
Output exactly one JSON object. The fields must be:
- passed
- stopReason
- findings

Requirements:
- passed must be true or false
- stopReason must be a string; it may be empty when the check passes
- findings must be an array; when the check fails, list the failed items
```

### 6A.6A Revision / Self-Check 占位内容到现有数据源的映射

> 本节是实现说明，不属于建议 prompt 文案本体，不应直接进入 prompt。

本节用于约束 `Revision Objective` 与 `Self-Check Objective` 中占位内容的来源，防止实现者为了拼 prompt 临时发明新的 phase state、持久化字段或 session 聚合态。

总原则：

1. 以下占位内容必须优先从现有字段直接渲染，或由现有字段做只读组合渲染。
2. 不允许为了这些占位内容新增持久化 memory 类型。
3. 不允许修改 persistence / resume / compact 协议来服务 prompt 模板。
4. 若现有字段不足以直接表达，允许做 agent 内部、非持久化、只读的渲染组合，但不得把该组合提升成新的领域状态。

建议映射如下：

1. `{revision_mode}`
   - 来源：当前 revision 调用已持有的 `targetStrategy`
   - 约束：渲染时使用现有 `ReviewStrategy` 原样字面值，不新增新枚举或别名

2. `{key_rationales_summary}`
   - 来源：现有 `keyRationales`
   - 约束：可做摘要渲染，但不得脱离 `keyRationales` 另造新状态字段

3. `{must_fix_items}`
   - 来源：默认由现有 `keyRationales` 直接渲染或做只读归并
   - 约束：它不是新的持久化字段，也不是新的 session phase memory；本质上是“本轮必须先修复的问题列表”的展示视图

4. `{do_not_expand_boundary}`
   - 来源：由现有 `targetStrategy`、`keyRationales` 与当前 chunk / working-set 边界做只读组合渲染
   - 约束：它只是“本轮不应扩张改写的边界”的展示视图，不得升级为新的运行时聚合状态

5. `{confirmed_terms_constraints}`
   - 来源：当前轮已知的 confirmed-term 相关现有证据
   - 允许来源包括：
     - 当前 chunk / working-set 中的 `confirmedTermUpdates`
     - 已读取的 `read_confirmed_terms` authoritative result
     - `keyRationales` 中已明确写出的 confirmed-term conflict / term rationale
   - 约束：不得为此新增新的 project/session 聚合字段

6. `{residual_risks_summary}`
   - 来源：现有 `residualRisks`
   - 约束：可压缩显示，但不得脱离 `residualRisks` 另造新风险状态

7. `Revision Target`
   - 来源：由现有 `targetStrategy`、`keyRationales`、confirmed-term 相关现有证据、`residualRisks` 做只读组合渲染
   - 约束：它是 revision / self-check prompt 共享的目标摘要视图，不是新的状态字段，也不是新的持久化对象
   - 核心内容必须可回指到 evaluation 阶段已形成的 `recommendedStrategy`、`strategyReason`、`Key Evidence` 与本轮 key rationales，不得在 revision 阶段随手拼出新的任务目标

8. `previous findings`
   - 来源：现有 self-check retry 路径中的 `firstAttempt.findings()`
   - 约束：只复用现有 `RevisionSelfCheckResult.findings`，不新增历史 findings 存储结构

实施硬约束：

1. `Revision Objective` 与 `Self-Check Objective` 的占位内容必须能回指到现有字段来源。
2. 若实现者发现某个占位内容无法由现有字段稳定渲染，应先回到设计层确认是否删减占位内容，而不是直接新增状态字段。
3. 只有在现有字段完全无法稳定承载、且不改变 persistence / protocol 的前提下，才允许新增 agent 内部只读渲染 DTO；该 DTO 不得成为新的领域状态或持久化对象。

### 6A.7 Layer D：Repair Prompt 建议文案

Repair 不需要重讲全局世界观，只需要把“这次哪里错了，如何就地修正”说清楚。建议模板如下：

`[Repair Scope]`

```text
上一次输出不可用。请只修复当前列出的结构或契约问题，不要改变本轮任务目标，不要引入新的工具或新的全局解释。
你将同时收到当前原始任务目标摘要与上一轮不可用输出摘要；repair 必须围绕同一个任务目标做局部修复，而不是重新开题。
```

English Program Version
```text
The previous output is unusable. Repair only the listed format or argument errors.
You will also receive a summary of the current task and a summary of the unusable output.
Do not change the current task goal. Do not add a different tool choice or a new high-level explanation.
```

`[Repair Findings]`

```text
当前发现的问题如下：
- 结构错误 / 缺字段 / 多字段 / 非法参数 / proposal DTO 不合法 / proposal not applicable
请根据这些问题重新输出一个可用结果。
```

English Program Version
```text
The current problems are:
- structural error / missing field / extra field / invalid argument / invalid proposal DTO / proposal not applicable
Produce a usable result by fixing these problems.
```

`[Repair Constraints]`

```text
只修复当前错误。
如果原始工具选择仍然成立，不要无故换工具。
只有在当前工具的局部修复链路明确允许时，才可以 local replan。
不要把 repair 变成重新规划整个任务。
```

English Program Version
```text
Repair only the current error.
If the original tool choice is still valid, do not switch tools without cause.
Only use local replan when the current tool's local repair chain explicitly allows it.
Do not turn repair into replanning the whole task.
```

`[Repair Target Alignment]`

```text
repair 必须对齐当前阶段的原始任务目标，不得拿错任务单：
1. next-step / evaluation repair：对齐当前阶段原始任务目标。
2. revision / self-check repair：对齐当前 `Revision Target`。
3. proposal repair：对齐该工具专用局部链路目标。
```

## 7. 阶段职责与推进门槛

这是为了避免后续实现滑向隐式 orchestrator 或半状态机。

### 7.1 总原则

review-agent 不是强制状态机，但存在受约束的阶段推进规则。

这里的“阶段推进规则”指：

1. 某些条件没满足前，后续动作不该发生。
2. 某些阶段的职责是收窄而非扩张。
3. 最终工具选择权仍在 next-step。

### 7.2 阶段职责表

`next-step`

1. 是唯一的工具选择入口。
2. 决定继续取证、进入 evaluation、发起 completion、请求人工，或其他允许的工具调用。

`evaluation`

1. 只输出 `recommendedStrategy + evidenceSufficiency + continueInvestigation`
2. 不直接触发 revision / completion。

`revision`

1. 只输出 revision draft。
2. 不直接触发 completion。

`self-check`

1. 只输出 readiness signal，例如 `passed` 或等价 completion-ready 结果。
2. 不直接触发 completion。

`completion`

1. 仍由 next-step 选择 `complete_working_set` 或 `complete_project`。
2. 不是 evaluation / self-check 自己跳转。

`record_confirmed_terms` special path

1. `record_confirmed_terms` 保留现有窄两阶段特例。
2. next-step 只负责选择该工具，不直接把这条特例提升成通用阶段。
3. proposal 生成、proposal repair、assembly、proposal `NOT_APPLICABLE` 后 local replan，仍属于该工具的专用局部链路。
4. 这条链路不构成新的全局 phase，也不授权实现者抽象出通用 proposal / orchestration 机制。

### 7.3 各阶段推进门槛

本设计当前稳定支持的主审校维度至少包括：

1. `continuity`
2. `term`
3. `quality`
4. `completion`

补充约束：

1. `human escalation` 不是独立审校维度。
2. `human escalation` 是当前主审校维度无法在本地闭合时的升级出口。
3. 不建议在实现阶段随意扩张或折叠这组维度，否则 investigation prompt 的门槛摘要会再次分裂。
4. 本文中的 `term` 维度包含人名、称谓、地名、专名及其他需要 authoritative lookup / stable pair 判断的命名一致性问题。

`investigation -> evaluation`

必须满足：

1. 当前主审校维度的证据已经闭合到足以判断 KEEP / EDIT / HUMAN。
2. 不再存在明显依赖未读局部证据的缺口。

`evaluation -> revision`

必须满足：

1. `recommendedStrategy` 已进入 edit 类。
2. next-step 明确选择继续 revision，而不是继续 investigation。

`revision -> self-check`

必须满足：

1. 已产出有效 draft。

`self-check -> completion candidate`

必须满足：

1. 已产出显式 readiness signal。
2. readiness signal 只是 completion 的候选条件，不等于自动 completion。

### 7.4 附录与规范层的关系

第 8 节的闭合条件不是额外规则库，也不是第二规范源。

它的职责是：

1. 作为 `Decision Gate Summary` 的展开依据。
2. 帮助实现者把抽象门槛压缩成 investigation prompt 中的可执行门槛句。

实现硬约束：

1. 每个主审校维度至少要把其闭合条件压缩成 1-2 条可执行门槛放入 investigation prompt。
2. 附录中的条件不能只停留在文档说明层，必须映射回 Layer B 的规范文本。
3. 但映射时必须压缩，不得把整个附录原样复制回 prompt，避免形成第二轮冗余。

## 8. 证据闭合条件附录

本节是说明性附录，不作为 prompt 规范唯一来源。作用是帮助实现者理解第 7 节的门槛设计。

### 8.1 continuity 证据闭合条件

默认涉及以下任一类时，进入 continuity 维度：

1. 衔接
2. 对话承接
3. 指代消解
4. 说话者 / 被说话者关系
5. 时间或空间跳转
6. reply-like / elliptical / context-dependent chunk

闭合条件：

1. 必要相邻 chunk 已实际读到。
2. 若判断依赖双侧，则双侧都已读到。
3. 不再依赖未读相邻文本。

### 8.2 term 证据闭合条件

闭合条件：

1. term 已在当前 `sourceText / translatedText / confirmedTermUpdates / 已读 workingSet 文本` 内可见。
2. authoritative hit / miss 已被读取并消费。
3. 当前译文已与 authoritative result 做过比对。
4. 若要 `record_confirmed_terms`，则已形成当前 workingSet 内的稳定 pair。

### 8.3 quality 证据闭合条件

闭合条件：

1. 问题可由当前局部证据独立判定。
2. 或已明确不依赖未读邻接上下文。
3. 不再存在明显未消解的 source/translation 对照缺口。

### 8.4 completion 证据闭合条件

闭合条件：

1. 无 unresolved gaps。
2. 无 unresolved local failures。
3. 所需 readiness signal 已满足。
4. 无 unresolved high-priority issue。

## 9. 约束归属矩阵

这是为了证明“压 prose 不等于削防线”。

### 9.1 JSON shape

归属：

1. JSON schema
2. node-level structured parsing

典型例子：

1. `toolName / arguments / reason` 必须存在。
2. `arguments` 必须是 object。

见 [OpenAiCompatibleReviewAgentStructuredGenerationClient.java](../../src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:202)。

### 9.2 allowed / required / basic type constraints

归属：

1. schema description
2. contract validator

典型例子：

1. `unexpected_argument:*`
2. `missing_argument:*`
3. `invalid_argument:*`

见 [ReviewToolDecisionContractValidator.java](../../src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java:20)。

### 9.3 动态语义边界

归属：

1. system prompt
2. investigation prompt

典型例子：

1. continuity 证据未闭合前不能 completion。
2. low-priority signal 不能独立触发高风险动作。
3. `pendingChunkCount=0` 时优先 `complete_project`。

### 9.3A 必须保留最小语义句的工具

以下工具的 schema description 不能只剩纯参数形状，仍需保留最小语义边界：

1. `record_confirmed_terms`
   - candidate pair 必须来自当前 workingSet 的稳定证据
2. `request_human_review`
   - 只用于本地工具无法解决的真实语义问题
3. `complete_working_set`
   - 提交集合必须是本轮真正完成的 pending chunks
4. `complete_project`
   - 只用于 pending-empty / project-ready endgame

这几句不是为了把大段 prose 塞回 schema，而是为了防止“格式合法但语义错用”。

### 9.4 repair 示例与局部纠偏

归属：

1. repair prompt

典型例子：

1. `invalid_argument:entries` 的 two-option repair。
2. proposal DTO repair。
3. proposal `NOT_APPLICABLE` 后的 local replan。

### 9.5 containment

归属：

1. runtime
2. transport retry policy

原则：

1. containment 不因 prompt 压缩而改变。
2. containment 不是 prompt 可以替代的层。

## 10. 与当前记忆机制的兼容方式

### 10.1 高保真正文上下文放哪里

读进去的 chunk 原文 / 译文继续来自现有 `workingSetContext().snapshots()`，落到各阶段 prompt 的正文上下文区块：

1. investigation 的 `[Working Set Text Context]`
2. evaluation 的 `[Working Set Text Context]`
3. revision 的 `[Working Set Context]`
4. self-check 的 `[Working Set Context]`

源码依据：

1. [InvestigationPromptBuilder.java](../../src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java:172)
2. [EvaluationPromptBuilder.java](../../src/main/java/io/quillloom/application/postdraft/review/prompt/EvaluationPromptBuilder.java:103)
3. [RevisionPromptBuilder.java](../../src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionPromptBuilder.java:121)
4. [RevisionSelfCheckPromptBuilder.java](../../src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java:112)

### 10.2 摘要型状态记忆放哪里

摘要型状态记忆继续来自既有 session 数据，不新造 memory mechanism。

包括：

1. `evidenceSummaries`
2. `keyEvidenceSummaries`
3. `conflictingEvidenceSummaries`
4. `evidenceGaps`
5. `transcriptStore`
6. `diagnostics.localRejectionReasons`

落点：

1. next-step：放 investigation 的 `[State Memory]`
2. evaluation：只取关键证据、冲突证据、缺口
3. revision：只取关键 rationale 与 residual risks
4. self-check：只取 previous findings

### 10.3 兼容边界要写死

本设计只重排 prompt 对既有 session 数据的消费方式，不改变以下内容：

1. memory production
2. memory storage
3. persistence payload
4. resume payload
5. compact 语义

也就是说：

1. 不新增持久化 memory 类型。
2. 不新增为了 prompt 分层而存在的新 session 协议字段。
3. 不修改 persistence / resume / compact 协议。

### 10.4 Recent Local Failures 的边界

`Recent Local Failures` 只是反循环提示，不是语义证据。

要求：

1. 应保持有界窗口。
2. 不得在 prompt 中升级成主证据层。
3. 其存在价值是避免重复错误，而不是驱动语义判断。

## 11. 最小可落地方案

### 11.1 只改 prompt 分层，不改 runtime 主骨架

最小方案的核心是：

1. `system prompt` 变瘦。
2. `investigation prompt` 变短，但更明确本轮决策门槛。
3. schema description 承接大部分工具参数与形状信息。
4. repair prompt 保持现有局部纠偏职责。

不做：

1. 新 orchestrator
2. 通用两阶段 tool preselection
3. 新的 tool protocol
4. 改 runtime / persistence / resume 主链路

### 11.2 建议修改的文件

1. `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
2. `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
3. `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
4. `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedStrategyEvaluationService.java`
5. `src/main/java/io/quillloom/application/postdraft/review/prompt/EvaluationPromptBuilder.java`
6. `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedRevisionDraftProvider.java`
7. `src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionPromptBuilder.java`
8. `src/main/java/io/quillloom/application/postdraft/review/service/LlmBackedRevisionSelfCheckService.java`
9. `src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java`

### 11.3 建议不改的文件

1. `AutonomousProjectReviewAgent`
2. `ReviewToolExecutor`
3. `WorkingSetCompletionHandler`
4. `RetryingReviewAgentStructuredGenerationPort`
5. `ReviewAgentStructuredGenerationPort`
6. persistence / resume 相关文件

## 12. 主要风险

1. 如果 system prompt 压得过头，某些只能用 prose 表达的高层边界会丢失。
2. 如果 schema description 承担太多 prose，会把冗余从 system prompt 平移到 schema description。
3. 如果 investigation 的决策门槛摘要过短，可能削弱 adjacent-reading / completion gate 的信号。
4. 如果 stage-specific prompt 过度删减，evaluation / revision / self-check 可能失去必要上下文。

因此本轮必须遵循：

1. 压缩的是工具说明 prose，不是格式防线。
2. 缩掉的是重复治理噪音，不是唯一规范来源。
3. 保留的是现有 repair / validator / containment 体系，而不是靠 prompt 单层兜底。

## 13. 验证建议

实现时建议做四类验证：

1. prompt snapshot 对比
2. 行为回归测试
3. 格式防线回归
4. containment 回归

具体应确认：

1. system prompt 明显缩短，且不再包含全量工具手册。
2. investigation prompt 保留客观 adjacent state，并只承载本轮决策门槛。
3. evaluation / revision / self-check 不再重复 next-step 阶段门槛。
4. `record_confirmed_terms.entries` repair 仍稳定。
5. proposal repair / local replan 仍稳定。
6. `complete_working_set` / `complete_project` 的 endgame 语义不变。
7. B1-B10 已确认正确的行为不回退。

## 14. 结论

本轮推荐方案不是继续堆 prompt，也不是重做 agent 架构，而是把现有 prompt 体系重新收口成：

1. system prompt：全局恒定规则
2. investigation prompt：本轮决策门槛
3. schema / validator：格式与结构防线
4. repair：局部纠偏
5. stage-specific prompts：阶段内专属输出契约

这样做的核心收益是：

1. 减少规则重复与治理噪音。
2. 把“先补证据，再做判断，再决定下一步”变成明确的阶段推进约束。
3. 在不破坏既有 memory / repair / containment 框架的前提下，提高 next-step 决策聚焦度与一致性。
