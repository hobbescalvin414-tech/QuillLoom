# Review Agent Structured Tool Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Review Agent 像 claw-code 一样拥有正式工具契约和可用工具工作记忆：模型能看到“有哪些工具、什么时候该用/不该用、参数是什么、结果语义是什么、刚才调用了什么和返回了什么”，执行层能拒绝同一 focus 内重复的成功取证调用，避免 127 chunk 长跑中空转烧钱。

**Architecture:** 不新增工具，不改变 LLM 自主选择下一步工具的核心架构，也不重构 `ReviewToolExecutor` 的 switch。把现有偏薄的 `ReviewToolDefinition` 升级为所有工具共用的正式定义源，驱动 prompt 渲染、参数校验和 schema 约束；沿用现有 `TranscriptStore` / `ReviewEvidenceBundle` / `ReviewToolTrace` / `HistoryLog` 分工，新增当前 focus 的结构化 `tool_use/tool_result` 工作记忆。精确工具调用记录只服务当前 focus，不做全局工具调用历史，不做工具调用记录压缩；完整 D-08 插件式工具系统和 D-09 结构化压缩摘要仍后置。

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Maven, Jackson, LangChain4j structured output.

---

## 1. 问题描述

### 1.1 真实失败模式

在 `book-draft-20260419151435` 的真实 Review Agent 启动中，agent 在 `chunk-1` 连续调用：

```text
read_confirmed_terms(sourceTerms=["Le Condé"])
```

控制台连续出现：

```text
[review-agent] event=tool_called ... tool=read_confirmed_terms reason=...
[review-agent] event=tool_completed ... tool=read_confirmed_terms status=success summary=read_confirmed_terms
```

但 agent 没有推进到 `evaluate_focus` 或 `complete_working_set`，而是不断给出“需要正式验证 Le Condé 是否为项目级 confirmed term”的理由。

关键观察：

- 工具是成功的，不是 guardrail 拒绝。
- 成功调用不会计入现有 `NO_PROGRESS`。
- 成功调用后 transcript 只写 `read_confirmed_terms -> 1 item(s)`，没有明确写参数和结果。
- LLM 后续声称“此前多次调用未指定 sourceTerms，本次是首次针对性查询”，说明它没有可用的工具调用工作记忆。

### 1.2 当前记忆格式太粗

当前 `ReviewToolExecutor.executeReadConfirmedTerms()` 最终通过 `applyEvidence()` 写入：

```text
transcript: read_confirmed_terms -> 1 item(s)
history: read_confirmed_terms | confirmedTerm=Le Condé->孔代咖啡馆
toolTrace: toolName=read_confirmed_terms, reason=..., notes=[confirmedTerm=...]
evidence: confirmedTerm=Le Condé->孔代咖啡馆
```

问题：

- `HistoryLog` 按方向锚点 D-02 不进入 prompt，不能指望 LLM 读到。
- `TranscriptStore` 进入 prompt，但内容太粗，缺少 `sourceTerms=[Le Condé]` 和返回值。
- `ReviewToolTrace` 目前虽然存了 notes，但 prompt 中没有直接渲染 tool traces；近期运行反馈只来自 transcript。
- `ConsoleReviewRuntimeVisualizer` 的 `summary=read_confirmed_terms` 对人也不够诊断。

### 1.3 现有 NO_PROGRESS 只覆盖失败，不覆盖成功空转

当前 `ReviewToolExecutor.appendAudit()` 只在 guardrail / 本地拒绝时记录 local failure：

```text
guardrail:<toolName>:<detail>
```

连续 3 次相同 local failure 才触发：

```text
NO_PROGRESS -> FAILED
```

但连续成功调用同一个工具同一组参数不会进入 `appendAudit()`，且成功路径会清空 local failures：

```java
withAutonomyState(session.autonomyState().afterInvestigationTurn().clearLocalFailures())
```

所以“成功但无信息增益”的空转不会被拦住。

### 1.4 prompt 不能单独解决

系统 prompt 已加入：

- 不要做全项目巡检。
- 当前 chunk 没出现某个 sourceTerm，不要为了未来可能出现去查。
- `confirmedTerm=A->B` 已出现就不要重复查询 A。

但真实运行仍反复查询。原因是 LLM 可以给出貌似合理的解释，例如：

```text
confirmedTerm=Le Condé->孔代咖啡馆 是 observation / operatorNote / 快照，不是正式工具查询结果。
```

结论：prompt 需要保留，但必须加执行层硬约束。

### 1.5 工具定义仍偏薄，prompt / schema / validator 容易漂移

当前代码已经有 `ReviewToolDefinition` 和 `ToolArgumentSchema`，不是完全没有工具定义。但它们目前主要承担：

- 工具名。
- 一段简短 description。
- required arguments。
- 简单参数 schema。

这还不是足够正式的工具契约。真实运行暴露出的问题是：LLM 不只需要知道“工具存在”和“参数叫什么”，还需要知道工具的操作规程：

- 什么时候该用。
- 什么时候禁止用。
- 查询结果是否权威。
- 同一 focus 内是否允许重复调用。
- 结果为空时下一步是什么。
- 成功后应该推进到哪个决策类工具，而不是继续取证。

如果这些规则继续分散在系统 prompt、registry description、validator、OpenAI schema 和 executor guard 里，就会继续出现漂移：

```text
prompt 说不要重复查
schema 仍允许重复调用
validator 只校验参数类型
executor 不知道该拒绝 successful duplicate
```

因此本轮不能只给 `read_confirmed_terms` 写几句说明。应该把所有工具一起纳入“正式定义”体系，但行为 guard 分阶段启用。

本轮结论：

- 所有工具都要有正式 `ReviewToolDefinition`。
- prompt 工具说明必须从 definition 渲染，不再单独维护散文式工具说明。
- validator 必须以 definition 的 allowed/required arguments 为准。
- LLM JSON schema 也应由 definition 派生或至少与 definition 对齐。
- 当前只对 `read_confirmed_terms` 启用 same-signature successful duplicate guard，因为它的重复语义确定。

---

## 2. claw-code 参考结论

参考路径：`E:\learnAgent\cc\claw-code`

### 2.1 claw-code 的工具记忆是结构化 conversation message

在 `rust/crates/runtime/src/conversation.rs` 中，claw-code 每轮会把 assistant 的工具调用原样 push 到 session：

```rust
self.session.messages.push(assistant_message.clone());
```

其中 assistant message 包含：

```rust
ContentBlock::ToolUse { id, name, input }
```

工具执行后再 push：

```rust
ConversationMessage::tool_result(tool_use_id, tool_name, output, is_error)
```

这意味着下一轮模型看到的是成对结构：

```text
assistant tool_use id=toolu_1 name=read_x input={...}
tool_result tool_use_id=toolu_1 output=...
```

而不是一句自然语言摘要。

### 2.2 claw-code 持久化时也保留 tool_use / tool_result

在 `rust/crates/runtime/src/session.rs` 中：

```rust
ContentBlock::ToolUse {
    id,
    name,
    input,
}

ContentBlock::ToolResult {
    tool_use_id,
    tool_name,
    output,
    is_error,
}
```

session JSON 中保留工具名、输入、结果、错误状态。

### 2.3 claw-code 压缩时仍保留工具输入和结果

在 `rust/crates/runtime/src/compact.rs` 中：

```rust
tool_use {name}({input})
tool_result {tool_name}: {output}
```

即使压缩，也不会丢掉“参数和结果”这两个关键工作记忆。

### 2.4 QuillLoom 不应照搬，但要吸收原则

QuillLoom 当前不是通用工具对话 runtime，而是项目级译后审校 agent。最合适的吸收方式：

- 不改变现有 `ReviewToolDecision` / `ReviewToolExecutionResult` 主流程。
- 不引入真正的 OpenAI/Anthropic tool message protocol。
- 在 `TranscriptStore` 中写入 claw-code 风格的 `tool_use` / `tool_result` 文本块，供 LLM 可靠读取。
- 在 `ReviewToolTrace` / `HistoryLog` 中记录工具参数签名，供执行层去重和审计。
- 对重复成功取证调用加 guard，避免长跑空转。

### 2.5 本轮不照搬 claw-code 的全局历史和压缩机制

claw-code 是通用编码 agent，需要长期保存和压缩完整对话/工具历史。QuillLoom Review Agent 当前的阻塞点更窄：同一个 focus 内重复调用同一个成功取证工具。

因此本轮边界是：

- 只保留当前 focus 内的精确工具调用记录。
- focus 切换后，上一 focus 的精确 `tool_use` / `tool_result` 明细不继续进入下一 focus prompt。
- 不建立项目级全局工具调用日志给 LLM 消费。
- 不做工具调用记录压缩，也不引入 D-09 的跨 focus 结构化摘要。
- 跨 focus 的长期知识只依赖稳定项目状态，例如 confirmed terms、completed chunk outcomes、`revisedTranslatedText` 和 `mergedDraftText`。

这避免把“当前 focus 防重复查询”扩大成新的上下文压缩系统。

---

## 3. 目标行为

### 3.1 成功工具调用必须产生可见的结构化记忆

`read_confirmed_terms(sourceTerms=["Le Condé"])` 成功后，LLM 可见 transcript 应包含：

```text
tool_use read_confirmed_terms {"sourceTerms":["Le Condé"]}
tool_result read_confirmed_terms sourceTerms=["Le Condé"] -> confirmedTerm=Le Condé->孔代咖啡馆
```

如果未命中：

```text
tool_use read_confirmed_terms {"sourceTerms":["Foo"]}
tool_result read_confirmed_terms sourceTerms=["Foo"] -> confirmedTermLookupMiss=[Foo]
```

这条信息必须进入 `TranscriptStore`，因为方向锚点规定 prompt 可见历史来自 transcript。

注意：`confirmedTermLookupMiss` 也算一次成功工具调用。它的语义是“已经确认这些 sourceTerms 当前没有项目级 confirmed term”，不是失败。因此同一 focus 内再次用相同 normalized sourceTerms 查询，也应被 same-signature guard 拒绝。否则 agent 可能在未命中术语上形成另一种成功空转。

### 3.2 执行层拒绝重复成功取证调用

同一 focus session 内，如果已经成功执行过并记录了 tool trace，无论结果是命中 confirmed term 还是 `confirmedTermLookupMiss`：

```text
read_confirmed_terms sourceTerms=[le condé]
```

再次调用语义相同的参数，例如：

```json
{"sourceTerms":["Le Condé"]}
{"sourceTerms":[" le condé "]}
{"sourceTerms":["LE CONDÉ"]}
```

应被本地拒绝，不再查库：

```text
redundant_successful_tool_call:read_confirmed_terms:sourceTerms=[le condé]
```

拒绝结果走现有 `appendAudit()`，因此：

- transcript/history 会记录拒绝。
- local rejection 会进入 diagnostics。
- 连续 3 次仍重复时，现有 `NO_PROGRESS -> FAILED` 生效。
- 不转 HITL。

### 3.3 rejection hint 必须告诉 agent 下一步

重复成功工具调用被拒绝后，transcript 应给出明确 replan hint：

```text
local_replan_hint -> read_confirmed_terms 已经用 sourceTerms=[Le Condé] 成功查过；
不要重复查询同一术语。请消化已有 confirmedTerm 证据：
如果当前 chunk 译文一致，complete_working_set；
如果不一致，evaluate_focus；
如果本地证据无法判断，request_human_review。
```

### 3.4 控制台输出要能辅助人类诊断

`ConsoleReviewRuntimeVisualizer.toolCompleted()` 不必打印完整大结果，但对短结果应展示足够细节。目标日志：

```text
[review-agent] event=tool_completed ... tool=read_confirmed_terms status=success summary=read_confirmed_terms sourceTerms=[Le Condé] -> confirmedTerm=Le Condé->孔代咖啡馆
```

如果结果很长，继续受 `console-preview-max-length` 控制。

### 3.5 全工具正式定义，本轮只对确定性工具启用强 guard

本次真实阻塞点是 `read_confirmed_terms` 重复查询，但根因不只是这个工具本身，而是工具契约不够正式。第一阶段应覆盖所有 13 个工具的正式定义：

```text
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
```

每个工具都必须有同一套定义字段：

```text
toolName
description
whenToUse
whenNotToUse
parameters
requiredParameters
resultSemantics
repeatPolicy
authoritativeResult
nextStepGuidance
```

但不是所有工具都在本轮启用重复调用硬拦截。原因是不同工具的重复语义不同：

- `read_confirmed_terms`：本地、确定性、无副作用。同一 focus 同一 normalized sourceTerms 成功后再次调用没有信息增益，本轮必须强制拒绝。
- `lookup_knowledge_cards`：大概率也应避免同查询重复，但 queryTerms 和 evidence 状态变化会影响语义，本轮先定义 repeatPolicy，不启用硬拒绝。
- `read_previous_chunks` / `read_next_chunks`：workingSet 可能因工具调用扩展而变化，重复 `count=1` 是否 redundant 需要依赖当前 session 状态判断，本轮不硬拒绝。
- `evaluate_focus` / `draft_revision`：属于决策/生成动作，不适合用简单 same-signature 去重。
- `complete_working_set` / `complete_project`：状态转换工具，已有业务 guard，不纳入取证去重。

因此本轮边界是：

```text
所有工具：正式定义 + prompt 渲染 + validator/schema 对齐
read_confirmed_terms：额外做当前 focus same-signature successful duplicate guard
其他工具：只声明 repeatPolicy，暂不强制执行
```

### 3.6 工具记忆生命周期

本轮工具记忆的生命周期以 `PostDraftReviewSession` 的当前 focus 为边界。

当前 focus 内：

```text
tool_use read_confirmed_terms {"sourceTerms":["Le Condé"]}
tool_result read_confirmed_terms sourceTerms=["Le Condé"] -> confirmedTerm=Le Condé->孔代咖啡馆
```

这类精确记录必须进入当前 focus 的 transcript/evidence，并参与同签名成功调用去重。

切换 focus 后：

- 不把上一 focus 的完整工具调用明细继续塞给 LLM。
- 不把上一 focus 的工具调用记录压缩成全局工具摘要。
- 不新增项目级 `globalToolTrace` / `globalToolMemory` 一类状态。
- 只依赖已经存在的稳定状态继续推进，例如 project term state、completed chunk outcomes、chunk effective text。

HITL 暂停/恢复是例外但仍在同一 focus 内：如果 agent 在当前 focus 请求人工帮助并持久化 session，恢复后仍应保留当前 focus 的精确工具调用记录，避免 resume 后再次查询同一术语。

### 3.7 工具定义必须成为 prompt / validator / schema 的单一事实源

当前实现已经存在 `ReviewToolRegistry.defaultRegistry()`，但工具 definition 仍偏薄。升级后，工具定义应成为这三处的共同来源：

```text
ReviewToolRegistry.defaultRegistry()
  -> ReviewAgentSystemPromptBuilder / InvestigationPromptBuilder 的工具说明
  -> ReviewToolDecisionContractValidator 的 allowed/required/type 校验
  -> OpenAiCompatibleReviewAgentStructuredGenerationClient 的 tool arguments schema
```

目标不是立刻实现完整 JSON Schema discriminator，也不是把 executor 改成插件式调用，而是先消除三类漂移：

- prompt 说法和 validator 允许参数不一致。
- schema 暴露 union arguments，导致 LLM 把别的工具参数塞进当前工具。
- 工具 description 没有表达“用一次后结果是否权威、是否可重复调用、下一步该做什么”。

验收标准：

- `ReviewToolRegistryTest` 能断言 13 个工具都有完整 definition。
- `ReviewPromptBuilderTest` 能断言 prompt 中渲染了 `whenToUse` / `whenNotToUse` / `repeatPolicy` / `nextStepGuidance`。
- `ReviewToolDecisionContractValidatorTest` 或现有 guardrail test 能断言 unexpected argument 仍被拒绝。
- `OpenAiCompatibleReviewAgentStructuredGenerationClientTest` 能断言 schema 不再靠一坨无说明 union arguments 引导模型误填。

---

## 4. 架构设计

### 4.0 D-08-lite：全工具正式定义，但不做插件式 executor

本轮不是完整 D-08。完整 D-08 以后应把工具系统解耦为注册式 executor / handler 结构。本轮只做 D-08-lite：

- 扩展现有 `ReviewToolDefinition`，不新建一套平行定义。
- 让 13 个工具都拥有正式操作规程。
- 让 prompt / validator / schema 读取同一份 definition。
- 保留 `ReviewToolExecutor` 现有 switch，不新增工具，不引入动态 handler。

建议扩展 `ReviewToolDefinition` 的语义字段，但不要让调用方直接使用 10 参数 canonical constructor。`ReviewToolDefinition` 当前是 record，继续暴露全位置参数会导致 registry 中 13 个工具定义难读且易错。

保留 record 可以接受，但创建方式必须改为 builder / factory：

```java
public record ReviewToolDefinition(
        String toolName,
        String description,
        String whenToUse,
        String whenNotToUse,
        String resultSemantics,
        ToolRepeatPolicy repeatPolicy,
        boolean authoritativeResult,
        String nextStepGuidance,
        Set<String> requiredArguments,
        List<ToolArgumentSchema> argumentSchemas
) {
    public static Builder builder(String toolName, String description) {
        return new Builder(toolName, description);
    }
}
```

新增枚举：

```java
public enum ToolRepeatPolicy {
    ALLOW,
    AVOID_SAME_SIGNATURE,
    FORBID_SAME_SIGNATURE_AFTER_SUCCESS,
    STATE_TRANSITION_ONLY
}
```

字段语义：

- `whenToUse`：告诉 LLM 何时应该调用。
- `whenNotToUse`：告诉 LLM 哪些场景禁止调用，尤其是不要预查未来可能出现的术语。
- `resultSemantics`：说明工具返回值的权威性和局限。
- `repeatPolicy`：声明重复调用策略；本轮只有 `read_confirmed_terms` 的 `FORBID_SAME_SIGNATURE_AFTER_SUCCESS` 会在 executor 中强制执行。
- `authoritativeResult`：成功结果是否可作为当前 focus 的权威证据。
- `nextStepGuidance`：成功/未命中后应推进到哪些下一步。

兼容策略：

- 保留现有 3 参数/4 参数构造器仅用于旧测试或非核心临时定义，内部填保守默认值。
- 旧构造器默认值不要用空字符串表示“未配置后续可忽略”；compact constructor 应把 `null` 规整为 `""`，并提供 `hasFormalGuidance()` 或 registry 测试确保内置工具不能留空。
- `ReviewToolRegistry.defaultRegistry()` 中 13 个内置工具必须全部使用 builder / factory 写法，不允许直接调用 10 参数 canonical constructor。
- 默认值必须显式保守，例如 `repeatPolicy=ALLOW`、`authoritativeResult=false`。
- 内置工具 definition 的 `whenToUse` / `whenNotToUse` / `resultSemantics` / `nextStepGuidance` 必须非空，由 `ReviewToolRegistryTest` 锁定。

推荐 builder 调用格式：

```java
ReviewToolDefinition.builder("read_confirmed_terms", "按原名查询项目中已确认的一致译名")
        .whenToUse("当前 focus/workingSet 中已经出现某个 source term，且需要核实其项目级稳定译名时使用。")
        .whenNotToUse("不要预查当前 focus 未出现的未来术语；不要重复查询 transcript 中已有同参数成功 tool_result 的术语。")
        .resultSemantics("命中项是项目级 confirmed term，在当前 focus 内视为权威证据；未命中表示该 source term 当前没有 confirmed term。")
        .repeatPolicy(ToolRepeatPolicy.FORBID_SAME_SIGNATURE_AFTER_SUCCESS)
        .authoritativeResult(true)
        .nextStepGuidance("命中后直接检查当前译文是否一致；一致则 complete_working_set，不一致则 evaluate_focus；证据不足才 request_human_review。")
        .requiredArguments(Set.of("sourceTerms"))
        .argumentSchemas(List.of(new ToolArgumentSchema("sourceTerms", "string[]", true, "要查询的原名列表")))
        .build()
```

这个写法比 10 个位置参数更长，但可读、可审查，不容易把 `repeatPolicy` 和 `authoritativeResult` 之类的参数传错位置。

### 4.1 新增工具调用签名概念

新增一个轻量 value object：

```text
ToolCallSignature
```

建议包：

```text
io.quillloom.application.postdraft.review.model
```

职责：

- 表示一个可比较的工具调用签名。
- 当前只需要对 `read_confirmed_terms` 生成可执行的 duplicate guard 签名。
- 负责归一化 source terms：trim + lower-case(Locale.ROOT) + 排序/去重。
- 提供 machine key 和 human display 两种表示。
- 预留通用工厂方法，但不要在本轮对其他工具启用硬去重。

示例：

```java
ToolCallSignature.forReadConfirmedTerms(List.of(" Le Condé "))
```

输出：

```text
toolName=read_confirmed_terms
key=read_confirmed_terms:sourceTerms=[le condé]
display=read_confirmed_terms sourceTerms=[Le Condé]
```

设计边界：

- 这是运行期 agent 记忆，不写回 `PostDraftReviewPackage` 或 `ProjectKnowledgeBase`。
- 不进入 `TranslationTaskInput`。
- 不新增工具。

### 4.2 扩展 ReviewToolTrace

当前：

```java
public record ReviewToolTrace(
        String toolName,
        String reason,
        List<String> notes
)
```

建议扩展为：

```java
public record ReviewToolTrace(
        String toolName,
        String reason,
        List<String> notes,
        String callSignature
)
```

兼容策略：

- 增加重载构造器，旧调用继续可编译：

```java
public ReviewToolTrace(String toolName, String reason, List<String> notes) {
    this(toolName, reason, notes, "");
}
```

用途：

- `callSignature` 供 executor 检查当前 focus 内是否已有相同成功调用。
- `notes` 继续放工具结果摘要。
- 该字段会随 `ProjectReviewRuntimeSession` 持久化，HITL resume 后仍可避免重复查询。

### 4.3 transcript 增强

新增 helper 方法，不建议把拼接散落在 executor：

```text
ReviewToolMemoryFormatter
```

建议包：

```text
io.quillloom.application.postdraft.review.service
```

职责：

- 格式化 `tool_use ...`
- 格式化 `tool_result ...`
- 格式化重复调用 rejection hint。

示例方法：

```java
static String renderToolUse(ToolCallSignature signature)
static String renderToolResult(ToolCallSignature signature, List<String> resultSummaries)
static String renderRedundantToolCallHint(ToolCallSignature signature)
```

### 4.4 executor 重复检测

在 `ReviewToolExecutor.executeReadConfirmedTerms()` 开头：

1. 从 arguments 解析 `sourceTerms`。
2. 构造 `ToolCallSignature`。
3. 检查当前 session 的 `toolTraces()` 是否已有相同 `callSignature` 且 toolName 为 `read_confirmed_terms`。
4. 如果存在，返回 rejected，不查库。
5. 如果不存在，正常查库。

重复调用被拒绝时：

```java
return ReviewToolExecutionResult.rejected(
    call,
    appendAudit(runtime, call, "redundant_successful_tool_call:" + signature.key()),
    ReviewGuardrailRejection.rejected(call.toolName(), "redundant_successful_tool_call:" + signature.key())
);
```

`appendAudit()` 的 hint 需要识别：

```text
redundant_successful_tool_call:
```

并生成明确下一步提示。

### 4.5 成功路径记录

`executeReadConfirmedTerms()` 成功后，不再只走通用 `applyEvidence()` 的弱 transcript。

建议做局部专用成功路径：

```text
applyReadConfirmedTermsEvidence(runtime, call, signature, summaries)
```

它做：

- evidenceBundle merge：保留 `confirmedTerm=...` 或 `confirmedTermLookupMiss=...`。
- toolTrace：新增 `callSignature=signature.key()`。
- transcript：
  - `tool_use read_confirmed_terms {"sourceTerms":["Le Condé"]}`
  - `tool_result read_confirmed_terms sourceTerms=[Le Condé] -> confirmedTerm=Le Condé->孔代咖啡馆`
- history：
  - title: `tool_use:read_confirmed_terms`
  - detail: `sourceTerms=[Le Condé]`
  - title: `tool_result:read_confirmed_terms`
  - detail: `confirmedTerm=Le Condé->孔代咖啡馆`

注意：HistoryLog 仍不进 prompt；它只是审计。

### 4.6 Console visualizer 增强

当前 `ReviewToolExecutionResult.summary()` 对 read_confirmed_terms 是固定字符串：

```text
read_confirmed_terms
```

建议让 `ReviewToolExecutionResult.summary()` 在该工具成功时包含短结果：

```text
read_confirmed_terms sourceTerms=[Le Condé] -> confirmedTerm=Le Condé->孔代咖啡馆
```

`ConsoleReviewRuntimeVisualizer` 继续按 `console-preview-max-length` 截断。

### 4.7 prompt / validator / schema 对齐

#### Prompt 渲染

`ReviewAgentSystemPromptBuilder.renderToolDefinitions()` 不能只输出：

```text
- toolName: description requiredArgs=...
```

升级后应按工具 definition 渲染操作规程：

```text
工具：read_confirmed_terms
用途：查询当前 focus 已出现且需要核实一致译名的 source terms。
适用：当前 sourceText/translatedText/workingSet 里出现该 source term，且 transcript 中尚无同参数 tool_result。
禁止：不要预查未来可能出现的术语；不要重复查询 transcript 中已有同参数成功结果的术语。
参数：sourceTerms:string[] required
结果语义：命中项是项目级 confirmed term，在当前 focus 内视为权威证据；未命中只表示该 source term 尚无 confirmed term。
重复策略：FORBID_SAME_SIGNATURE_AFTER_SUCCESS
下一步：命中后直接检查当前译文是否一致；一致则 complete_working_set，不一致则 evaluate_focus；证据不足才 request_human_review。
```

#### Validator 对齐

`ReviewToolDecisionContractValidator` 应继续以 `definition.allowedArguments()` 和 `definition.requiredArguments()` 为第一层校验，并补齐按 `ToolArgumentSchema.type()` 做的通用类型校验，减少 switch 中的重复专用校验。

本轮不要求完全删除 switch，因为部分工具仍有业务语义校验：

- `count > 0`
- `chunkIds` 非空且是字符串列表
- `request_human_review` 的顶层 reason 必须非空

但所有 unexpected argument / missing argument / 基础类型错误必须从 definition 派生。

#### LLM schema 对齐

`OpenAiCompatibleReviewAgentStructuredGenerationClient` 目前的 investigation schema 容易暴露 union arguments，导致模型把 `queryTerms` / `reason` 等错误参数塞进其他工具。本轮应至少做到：

- schema 中每个工具的参数说明来自 `ReviewToolDefinition.argumentSchemas()`。
- 如果仍使用 union arguments，也必须在 schema description 里明确“只允许 selected tool definition 中声明的 arguments”。
- 更推荐生成 per-tool 分支描述，但不强制引入完整 `oneOf` discriminator，避免本轮卡在 provider schema 兼容性上。

验收重点不是 schema 形式有多漂亮，而是 schema、prompt、validator 三者不再互相矛盾。

---

## 5. 受影响文件

### 5.1 生产代码

- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDefinition.java`
- Modify/Create: `src/main/java/io/quillloom/application/postdraft/review/model/ToolRepeatPolicy.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ToolArgumentSchema.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java`
- No change expected: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolGuardrail.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ToolCallSignature.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolMemoryFormatter.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolTrace.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ConsoleReviewRuntimeVisualizer.java` only if current summary rendering cannot display the richer `ReviewToolExecutionResult.summary()`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java` only if needed to label recent transcript as authoritative tool memory

`ReviewToolGuardrail` 边界说明：本轮不改 guardrail。guardrail 仍负责工具是否注册、必填参数是否存在这类进入 executor 前的快速拒绝；`ReviewToolDecisionContractValidator` 负责 allowed/required/type 契约校验；`ReviewToolExecutor` 内部负责依赖当前 runtime session 的重复成功调用拒绝。`redundant_successful_tool_call` 不能放在 guardrail，因为 guardrail 不应读取 focus session 的历史 tool traces。

### 5.2 测试代码

- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolRegistryTest.java`
- Modify/Create: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolDecisionContractValidatorTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Modify: `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewStructuredResultModelTest.java` if `ReviewToolTrace` constructor behavior needs contract coverage
- Create: `src/test/java/io/quillloom/application/postdraft/review/ToolCallSignatureTest.java`
- Create: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolMemoryFormatterTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ConsoleReviewRuntimeVisualizerTest.java` if visualizer needs direct assertions
- Existing e2e: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentEndToEndSmokeTest.java` may add a scripted path for repeated read_confirmed_terms rejection.

### 5.3 文档

- Modify: `docs/superpowers/plans/2026-04-19-review-agent-127-preflight-hardening-plan.md` with a short implementation note after code lands.
- Modify: `docs/handoff.md` or current handoff doc with “tool memory hardening landed” after verification.

---

## 6. 实施任务

### Task 0: All-tool formal definitions

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDefinition.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ToolRepeatPolicy.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ToolArgumentSchema.java` only if current schema cannot express examples/constraints clearly enough
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolRegistryTest.java`

- [ ] **Step 1: Write failing registry tests**

Add tests that assert every default tool has formal operating rules:

```java
@Test
void shouldDefineFormalContractForEveryDefaultTool() {
    ReviewToolRegistry registry = ReviewToolRegistry.defaultRegistry();

    assertEquals(13, registry.definitions().size());
    for (ReviewToolDefinition definition : registry.definitions()) {
        assertFalse(definition.toolName().isBlank());
        assertFalse(definition.description().isBlank());
        assertFalse(definition.whenToUse().isBlank());
        assertFalse(definition.whenNotToUse().isBlank());
        assertFalse(definition.resultSemantics().isBlank());
        assertNotNull(definition.repeatPolicy());
        assertFalse(definition.nextStepGuidance().isBlank());
    }
}

@Test
void shouldMarkReadConfirmedTermsAsAuthoritativeAndNonRepeatableAfterSuccess() {
    ReviewToolDefinition tool = ReviewToolRegistry.defaultRegistry().require("read_confirmed_terms");

    assertTrue(tool.authoritativeResult());
    assertEquals(ToolRepeatPolicy.FORBID_SAME_SIGNATURE_AFTER_SUCCESS, tool.repeatPolicy());
    assertTrue(tool.whenNotToUse().contains("不要预查"));
    assertTrue(tool.whenNotToUse().contains("不要重复"));
    assertTrue(tool.nextStepGuidance().contains("complete_working_set"));
    assertTrue(tool.nextStepGuidance().contains("evaluate_focus"));
}
```

Add a readability contract test that makes the intended construction style explicit:

```java
@Test
void shouldBuildToolDefinitionWithNamedBuilderFields() {
    ReviewToolDefinition tool = ReviewToolDefinition.builder("read_confirmed_terms", "查询 confirmed terms")
            .whenToUse("当前 focus 出现 source term 且需要核实稳定译名时使用。")
            .whenNotToUse("不要预查；不要重复查询已有同参数成功 tool_result。")
            .resultSemantics("命中和未命中都是权威查询结果。")
            .repeatPolicy(ToolRepeatPolicy.FORBID_SAME_SIGNATURE_AFTER_SUCCESS)
            .authoritativeResult(true)
            .nextStepGuidance("命中后检查译文一致性。")
            .requiredArguments(Set.of("sourceTerms"))
            .argumentSchemas(List.of(new ToolArgumentSchema("sourceTerms", "string[]", true, "要查询的原名列表")))
            .build();

    assertEquals("read_confirmed_terms", tool.toolName());
    assertEquals(ToolRepeatPolicy.FORBID_SAME_SIGNATURE_AFTER_SUCCESS, tool.repeatPolicy());
    assertTrue(tool.authoritativeResult());
}
```

Expected before implementation: compile failure or assertion failure because `ReviewToolDefinition` does not expose the new fields.

- [ ] **Step 2: Extend model minimally**

Add `ToolRepeatPolicy`:

```java
package io.quillloom.application.postdraft.review.model;

public enum ToolRepeatPolicy {
    ALLOW,
    AVOID_SAME_SIGNATURE,
    FORBID_SAME_SIGNATURE_AFTER_SUCCESS,
    STATE_TRANSITION_ONLY
}
```

Extend `ReviewToolDefinition` with:

```java
String whenToUse,
String whenNotToUse,
String resultSemantics,
ToolRepeatPolicy repeatPolicy,
boolean authoritativeResult,
String nextStepGuidance
```

Add `ReviewToolDefinition.Builder` and use it for all built-in tool definitions. Do not write 10 positional arguments in `ReviewToolRegistry.defaultRegistry()`.

Builder shape:

```java
public static Builder builder(String toolName, String description) {
    return new Builder(toolName, description);
}

public static final class Builder {
    private final String toolName;
    private final String description;
    private String whenToUse = "";
    private String whenNotToUse = "";
    private String resultSemantics = "";
    private ToolRepeatPolicy repeatPolicy = ToolRepeatPolicy.ALLOW;
    private boolean authoritativeResult;
    private String nextStepGuidance = "";
    private Set<String> requiredArguments = Set.of();
    private List<ToolArgumentSchema> argumentSchemas = List.of();

    public Builder whenToUse(String value) { this.whenToUse = value; return this; }
    public Builder whenNotToUse(String value) { this.whenNotToUse = value; return this; }
    public Builder resultSemantics(String value) { this.resultSemantics = value; return this; }
    public Builder repeatPolicy(ToolRepeatPolicy value) { this.repeatPolicy = value; return this; }
    public Builder authoritativeResult(boolean value) { this.authoritativeResult = value; return this; }
    public Builder nextStepGuidance(String value) { this.nextStepGuidance = value; return this; }
    public Builder requiredArguments(Set<String> value) { this.requiredArguments = value; return this; }
    public Builder argumentSchemas(List<ToolArgumentSchema> value) { this.argumentSchemas = value; return this; }

    public ReviewToolDefinition build() {
        return new ReviewToolDefinition(
                toolName,
                description,
                whenToUse,
                whenNotToUse,
                resultSemantics,
                repeatPolicy,
                authoritativeResult,
                nextStepGuidance,
                requiredArguments,
                argumentSchemas);
    }
}
```

Keep old constructors only for source compatibility by routing them to conservative defaults:

```java
this(toolName, description, "", "", "", ToolRepeatPolicy.ALLOW, false, "", requiredArguments, List.of());
```

The empty string defaults are only compatibility values. Code must not treat `field != null` as “configured”; use `!field.isBlank()` or registry tests. `ReviewToolRegistry.defaultRegistry()` must use builder definitions for all 13 built-in tools, and tests must fail if any built-in formal guidance field is blank.

- [ ] **Step 3: Fill all 13 default tool definitions**

Each definition must explicitly state:

- `read_previous_chunks`: use only when current focus needs immediate previous context; do not use for global review.
- `read_next_chunks`: use only when current focus needs immediate following context; do not prefetch unrelated future chunks.
- `expand_block_context`: use when local chunk boundary lacks enough context inside same block.
- `read_decision_notes`: use when D-stage decision notes are directly relevant to current focus.
- `read_transition_note`: use when chunk transition/continuity is the focus.
- `lookup_knowledge_cards`: use for current focus terms/events that need project knowledge support; do not replace C0 retrieval.
- `read_confirmed_terms`: use only for source terms currently visible in focus/workingSet; authoritative; duplicate success forbidden.
- `record_confirmed_terms`: use only after the agent has established a stable term in current focus; does not complete chunk work.
- `evaluate_focus`: use when enough evidence exists to choose KEEP / LIGHT_EDIT / REQUIRE_HUMAN_REVIEW.
- `draft_revision`: use after evaluation requires revision.
- `request_human_review`: use only for genuine ambiguity; human input is evidence, not command.
- `complete_working_set`: use when current workingSet chunks are actually finished.
- `complete_project`: use only when no pending chunks remain.

- [ ] **Step 4: Run registry tests**

Run:

```powershell
mvn -q "-Dtest=ReviewToolRegistryTest" test
```

Expected: PASS.

### Task 0.5: Prompt rendering from formal definitions

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

- [ ] **Step 1: Write failing prompt tests**

Add assertions that the rendered tool section includes formal operating rules:

```java
@Test
void shouldRenderFormalToolOperatingRules() {
    String prompt = new ReviewAgentSystemPromptBuilder().build(ReviewToolRegistry.defaultRegistry().definitions());

    assertTrue(prompt.contains("工具：read_confirmed_terms"));
    assertTrue(prompt.contains("适用"));
    assertTrue(prompt.contains("禁止"));
    assertTrue(prompt.contains("结果语义"));
    assertTrue(prompt.contains("重复策略"));
    assertTrue(prompt.contains("FORBID_SAME_SIGNATURE_AFTER_SUCCESS"));
    assertTrue(prompt.contains("不要预查"));
    assertTrue(prompt.contains("不要重复"));
}
```

- [ ] **Step 2: Render fields from `ReviewToolDefinition`**

Update `renderToolDefinitions()` to render each tool in a stable format:

```text
工具：<toolName>
说明：<description>
适用：<whenToUse>
禁止：<whenNotToUse>
参数：<renderArgumentRequirements>
示例：<renderArgumentsExample>
结果语义：<resultSemantics>
重复策略：<repeatPolicy>
下一步：<nextStepGuidance>
```

Do not duplicate these rules elsewhere in free-form prompt text except for high-level principles.

- [ ] **Step 3: Run prompt tests**

Run:

```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest" test
```

Expected: PASS.

### Task 0.6: Validator and LLM schema alignment

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- Modify/Create: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolDecisionContractValidatorTest.java`
- Modify: `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java`

- [ ] **Step 1: Add validator tests for definition-driven behavior**

Cover:

```java
@Test
void shouldRejectUnexpectedArgumentBasedOnToolDefinition() {
    var decision = new ReviewToolDecision("read_confirmed_terms", Map.of("queryTerms", List.of("Le Condé")), "lookup");

    assertEquals(
            Optional.of("unexpected_argument:queryTerms"),
            new ReviewToolDecisionContractValidator().validate(decision, ReviewToolRegistry.defaultRegistry())
    );
}

@Test
void shouldRequireTopLevelReasonForHumanReviewAndEmptyArguments() {
    var decision = new ReviewToolDecision("request_human_review", Map.of("reason", "help"), "");

    assertEquals(
            Optional.of("unexpected_argument:reason"),
            new ReviewToolDecisionContractValidator().validate(decision, ReviewToolRegistry.defaultRegistry())
    );
}
```

- [ ] **Step 2: Keep definition-driven allowed/required validation as first gate**

Do not add ad-hoc parameter names outside definition. `unexpected_argument` and `missing_argument` must come from `ReviewToolDefinition`.

- [ ] **Step 3: Align LLM schema descriptions with definitions**

Update schema generation or schema text so that each tool's allowed arguments and operating rule are visible to the LLM. If provider compatibility makes full `oneOf` risky, keep current shape but include per-tool argument table generated from registry definitions.

Expected test assertions:

```java
assertThat(schemaText).contains("read_confirmed_terms");
assertThat(schemaText).contains("sourceTerms");
assertThat(schemaText).contains("request_human_review");
assertThat(schemaText).contains("arguments must be {}");
assertThat(schemaText).contains("只允许 selected tool definition 声明的 arguments");
```

- [ ] **Step 4: Run alignment tests**

Run:

```powershell
mvn -q "-Dtest=ReviewToolDecisionContractValidatorTest,OpenAiCompatibleReviewAgentStructuredGenerationClientTest" test
```

Expected: PASS.

### Task 1: ToolCallSignature

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ToolCallSignature.java`
- Create: `src/test/java/io/quillloom/application/postdraft/review/ToolCallSignatureTest.java`

- [ ] **Step 1: Write failing tests**

Test cases:

```java
@Test
void shouldNormalizeReadConfirmedTermsSignatureCaseInsensitively() {
    ToolCallSignature signature = ToolCallSignature.forReadConfirmedTerms(List.of(" Le Condé ", "LE CONDÉ"));

    assertEquals("read_confirmed_terms", signature.toolName());
    assertEquals("read_confirmed_terms:sourceTerms=[le condé]", signature.key());
    assertEquals("read_confirmed_terms sourceTerms=[Le Condé]", signature.display());
}

@Test
void shouldSortReadConfirmedTermsForStableSignature() {
    ToolCallSignature left = ToolCallSignature.forReadConfirmedTerms(List.of("Zoo", "Alpha"));
    ToolCallSignature right = ToolCallSignature.forReadConfirmedTerms(List.of("alpha", "zoo"));

    assertEquals(left.key(), right.key());
}

@Test
void shouldUseSameSignatureForConfirmedTermHitAndLookupMiss() {
    ToolCallSignature hit = ToolCallSignature.forReadConfirmedTerms(List.of("Le Condé"));
    ToolCallSignature miss = ToolCallSignature.forReadConfirmedTerms(List.of(" le condé "));

    assertEquals(hit.key(), miss.key());
}

@Test
void shouldRejectEmptyReadConfirmedTermsSignature() {
    assertThrows(IllegalArgumentException.class, () -> ToolCallSignature.forReadConfirmedTerms(List.of(" ")));
}
```

- [ ] **Step 2: Run tests and confirm RED**

Run:

```powershell
mvn -q "-Dtest=ToolCallSignatureTest" test
```

Expected: compilation failure because `ToolCallSignature` does not exist.

- [ ] **Step 3: Implement minimal value object**

Implementation requirements:

- record fields:
  - `String toolName`
  - `String key`
  - `String display`
- static factory:
  - `forReadConfirmedTerms(List<String> sourceTerms)`
- normalization:
  - drop blank terms
  - trim display terms
  - lower-case key terms with `Locale.ROOT`
  - dedupe by normalized key
  - sort normalized key terms for stable key
  - preserve first display spelling for display output

- [ ] **Step 4: Run tests and confirm GREEN**

Run:

```powershell
mvn -q "-Dtest=ToolCallSignatureTest" test
```

Expected: PASS.

### Task 2: ReviewToolTrace call signature

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolTrace.java`
- Modify/Create tests: `src/test/java/io/quillloom/application/postdraft/review/ReviewStructuredResultModelTest.java`

- [ ] **Step 1: Write failing test**

Add test:

```java
@Test
void shouldKeepToolTraceCallSignatureWhenProvided() {
    ReviewToolTrace trace = new ReviewToolTrace(
            "read_confirmed_terms",
            "lookup",
            List.of("confirmedTerm=Le Condé->孔代咖啡馆"),
            "read_confirmed_terms:sourceTerms=[le condé]"
    );

    assertEquals("read_confirmed_terms:sourceTerms=[le condé]", trace.callSignature());
}

@Test
void shouldDefaultToolTraceCallSignatureForLegacyConstructor() {
    ReviewToolTrace trace = new ReviewToolTrace("read_confirmed_terms", "lookup", List.of("ok"));

    assertEquals("", trace.callSignature());
}
```

- [ ] **Step 2: Run test and confirm RED**

Run:

```powershell
mvn -q "-Dtest=ReviewStructuredResultModelTest" test
```

Expected: compilation failure for missing constructor/accessor.

- [ ] **Step 3: Extend record**

Add `String callSignature` to `ReviewToolTrace`.

Keep existing constructor:

```java
public ReviewToolTrace(String toolName, String reason, List<String> notes) {
    this(toolName, reason, notes, "");
}
```

Normalize `callSignature`:

```java
callSignature = callSignature == null ? "" : callSignature.trim();
```

- [ ] **Step 4: Run focused tests**

Run:

```powershell
mvn -q "-Dtest=ReviewStructuredResultModelTest,FileReviewSessionStoreTest" test
```

Expected: PASS, proving JSON serialization/resume-compatible enough for the extended record.

### Task 3: Tool memory formatter

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolMemoryFormatter.java`
- Create: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolMemoryFormatterTest.java`

- [ ] **Step 1: Write failing tests**

Test cases:

```java
@Test
void shouldRenderToolUseAndResult() {
    ToolCallSignature signature = ToolCallSignature.forReadConfirmedTerms(List.of("Le Condé"));

    assertEquals(
            "tool_use read_confirmed_terms {\"sourceTerms\":[\"Le Condé\"]}",
            ReviewToolMemoryFormatter.renderReadConfirmedTermsUse(signature)
    );
    assertEquals(
            "tool_result read_confirmed_terms sourceTerms=[Le Condé] -> confirmedTerm=Le Condé->孔代咖啡馆",
            ReviewToolMemoryFormatter.renderToolResult(signature, List.of("confirmedTerm=Le Condé->孔代咖啡馆"))
    );
}

@Test
void shouldRenderRedundantHint() {
    ToolCallSignature signature = ToolCallSignature.forReadConfirmedTerms(List.of("Le Condé"));

    String hint = ReviewToolMemoryFormatter.renderRedundantToolCallHint(signature);

    assertTrue(hint.contains("已经成功查过"));
    assertTrue(hint.contains("evaluate_focus"));
    assertTrue(hint.contains("complete_working_set"));
    assertTrue(hint.contains("request_human_review"));
}
```

- [ ] **Step 2: Run tests and confirm RED**

Run:

```powershell
mvn -q "-Dtest=ReviewToolMemoryFormatterTest" test
```

Expected: compilation failure.

- [ ] **Step 3: Implement formatter**

Output must be stable and concise. Use Chinese for hints.

Do not include large evidence dumps; join summaries with `; `.

- [ ] **Step 4: Run tests and confirm GREEN**

Run:

```powershell
mvn -q "-Dtest=ReviewToolMemoryFormatterTest" test
```

Expected: PASS.

### Task 4: read_confirmed_terms structured memory

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`

- [ ] **Step 1: Write failing test for visible memory**

Add test:

```java
@Test
void shouldRecordReadConfirmedTermsWithArgumentsAndResultInTranscript() {
    InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
    reader.confirmedTerms = Map.of("Le Condé", "孔代咖啡馆");
    ReviewToolExecutor executor = newExecutor(reader);
    ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

    ReviewToolExecutionResult result = executor.execute(
            runtime,
            new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Le Condé")), "lookup term")
    );

    assertTrue(result.success());
    PostDraftReviewSession session = result.nextRuntime().currentFocusSession().orElseThrow();
    assertTrue(session.transcriptStore().entries().stream()
            .anyMatch(entry -> entry.contains("tool_use read_confirmed_terms")
                    && entry.contains("Le Condé")));
    assertTrue(session.transcriptStore().entries().stream()
            .anyMatch(entry -> entry.contains("tool_result read_confirmed_terms")
                    && entry.contains("confirmedTerm=Le Condé->孔代咖啡馆")));
    assertTrue(session.toolTraces().stream()
            .anyMatch(trace -> trace.callSignature().equals("read_confirmed_terms:sourceTerms=[le condé]")));
    assertTrue(result.summary().contains("confirmedTerm=Le Condé->孔代咖啡馆"));
}
```

- [ ] **Step 2: Run test and confirm RED**

Run:

```powershell
mvn -q "-Dtest=ReviewToolExecutorGuardrailTest#shouldRecordReadConfirmedTermsWithArgumentsAndResultInTranscript" test
```

Expected: FAIL because transcript lacks `tool_use` / `tool_result` and trace lacks signature.

- [ ] **Step 3: Implement dedicated success path**

In `executeReadConfirmedTerms()`:

- parse sourceTerms
- build `ToolCallSignature`
- read confirmed terms
- build summaries as today
- call a new private method:

```java
private ReviewToolExecutionResult applyReadConfirmedTermsEvidence(
        ProjectReviewRuntimeSession runtime,
        ReviewToolCall call,
        ToolCallSignature signature,
        List<String> evidenceSummaries
)
```

The method must:

- merge evidence
- append `tool_use ...` transcript
- append `tool_result ...` transcript
- append `ReviewToolTrace(..., signature.key())`
- append history entries
- clear local failures like current successful investigation path
- return `ReviewToolExecutionResult.success(call, nextRuntime, ReviewToolMemoryFormatter.renderToolResult(...))`

- [ ] **Step 4: Run focused test**

Run:

```powershell
mvn -q "-Dtest=ReviewToolExecutorGuardrailTest#shouldRecordReadConfirmedTermsWithArgumentsAndResultInTranscript" test
```

Expected: PASS.

### Task 5: duplicate successful read_confirmed_terms guard

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`

- [ ] **Step 1: Write failing test**

Add test:

```java
@Test
void shouldRejectRepeatedSuccessfulReadConfirmedTermsWithSameSourceTerms() {
    InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
    reader.confirmedTerms = Map.of("Le Condé", "孔代咖啡馆");
    ReviewToolExecutor executor = newExecutor(reader);
    ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

    ProjectReviewRuntimeSession afterFirst = executor.execute(
            runtime,
            new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Le Condé")), "lookup term")
    ).nextRuntime();

    ReviewToolExecutionResult second = executor.execute(
            afterFirst,
            new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of(" le condé ")), "lookup again")
    );

    assertFalse(second.success());
    assertTrue(second.rejection().rejectionReason().contains("redundant_successful_tool_call"));
    PostDraftReviewSession session = second.nextRuntime().currentFocusSession().orElseThrow();
    assertTrue(session.transcriptStore().entries().stream()
            .anyMatch(entry -> entry.contains("不要重复查询")));
}
```

Add the miss variant:

```java
@Test
void shouldRejectRepeatedReadConfirmedTermsAfterLookupMiss() {
    InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
    reader.confirmedTerms = Map.of();
    ReviewToolExecutor executor = newExecutor(reader);
    ProjectReviewRuntimeSession runtime = initialRuntime(reader, "chunk-1");

    ProjectReviewRuntimeSession afterFirst = executor.execute(
            runtime,
            new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Unknown Place")), "lookup term")
    ).nextRuntime();

    ReviewToolExecutionResult second = executor.execute(
            afterFirst,
            new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of(" unknown place ")), "lookup again")
    );

    assertFalse(second.success());
    assertTrue(second.rejection().rejectionReason().contains("redundant_successful_tool_call"));
}
```

- [ ] **Step 2: Run test and confirm RED**

Run:

```powershell
mvn -q "-Dtest=ReviewToolExecutorGuardrailTest#shouldRejectRepeatedSuccessfulReadConfirmedTermsWithSameSourceTerms" test
```

Expected: FAIL because second call currently succeeds.

- [ ] **Step 3: Implement duplicate guard**

In `executeReadConfirmedTerms()` before `reader.readConfirmedTerms(...)`:

```java
if (hasSuccessfulToolCall(runtime.currentFocusSession().orElseThrow(), signature)) {
    String detail = "redundant_successful_tool_call:" + signature.key();
    return ReviewToolExecutionResult.rejected(
            call,
            appendAudit(runtime, call, detail),
            ReviewGuardrailRejection.rejected(call.toolName(), detail)
    );
}
```

Implement helper:

```java
private boolean hasSuccessfulToolCall(PostDraftReviewSession session, ToolCallSignature signature) {
    return session.toolTraces().stream()
            .anyMatch(trace -> signature.toolName().equals(trace.toolName())
                    && signature.key().equals(trace.callSignature()));
}
```

This helper intentionally checks only `callSignature`, not whether the prior result was a hit or miss. `confirmedTermLookupMiss` is a successful evidence result and must block same-signature repeat calls in the same focus.

- [ ] **Step 4: Extend local correction hint**

In `buildLocalCorrectionHint(...)`, add branch:

```java
if (detail.startsWith("redundant_successful_tool_call:")) {
    return "local_replan_hint -> " + ...;
}
```

The hint must mention:

- 已成功查过
- 不要重复查询
- 当前证据足够则 `complete_working_set`
- 发现问题则 `evaluate_focus`
- 无法判断才 `request_human_review`

- [ ] **Step 5: Run focused test**

Run:

```powershell
mvn -q "-Dtest=ReviewToolExecutorGuardrailTest#shouldRejectRepeatedSuccessfulReadConfirmedTermsWithSameSourceTerms" test
```

Expected: PASS.

### Task 6: NO_PROGRESS remains FAILED for repeated duplicate successful call

**Files:**
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`

- [ ] **Step 1: Write failing or confirming test**

Add test:

```java
@Test
void shouldFailNoProgressAfterRepeatedDuplicateSuccessfulReadConfirmedTerms() {
    InMemoryReader reader = new InMemoryReader(List.of(chunk("chunk-1", "translated-1")));
    reader.confirmedTerms = Map.of("Le Condé", "孔代咖啡馆");
    ReviewToolExecutor executor = newExecutor(reader);
    ProjectReviewRuntimeSession current = initialRuntime(reader, "chunk-1");

    current = executor.execute(
            current,
            new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Le Condé")), "lookup term")
    ).nextRuntime();

    for (int i = 0; i < 3; i++) {
        current = executor.execute(
                current,
                new ReviewToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Le Condé")), "lookup again")
        ).nextRuntime();
    }

    assertEquals(ProjectReviewStatus.FAILED, current.status());
    assertEquals(ReviewProjectStopReason.NO_PROGRESS, current.stopReason());
    assertTrue(current.humanReviewRequest().isEmpty());
}
```

- [ ] **Step 2: Run test**

Run:

```powershell
mvn -q "-Dtest=ReviewToolExecutorGuardrailTest#shouldFailNoProgressAfterRepeatedDuplicateSuccessfulReadConfirmedTerms" test
```

Expected before Task 5: FAIL. Expected after Task 5: PASS.

### Task 7: console and prompt visibility verification

**Files:**
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ConsoleReviewRuntimeVisualizerTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java` if necessary

- [ ] **Step 1: Add visualizer test if current test does not cover full summary**

Test should verify that a `ReviewToolExecutionResult.success(..., summary)` containing `confirmedTerm=...` appears in rendered console output when preview length is 0 or large enough.

- [ ] **Step 2: Add prompt test for transcript visibility**

Build a session with transcript entries:

```text
tool_use read_confirmed_terms {"sourceTerms":["Le Condé"]}
tool_result read_confirmed_terms sourceTerms=[Le Condé] -> confirmedTerm=Le Condé->孔代咖啡馆
```

Assert `InvestigationPromptBuilder` includes both.

- [ ] **Step 3: Run focused tests**

Run:

```powershell
mvn -q "-Dtest=ConsoleReviewRuntimeVisualizerTest,ReviewPromptBuilderTest" test
```

Expected: PASS.

### Task 8: scripted e2e coverage

**Files:**
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentEndToEndSmokeTest.java`

- [ ] **Step 1: Add scripted path**

Scenario:

```text
LLM decision 1: read_confirmed_terms(sourceTerms=[Le Condé])
executor: success
LLM decision 2: read_confirmed_terms(sourceTerms=[Le Condé])
executor: rejected redundant_successful_tool_call
LLM decision 3: complete_working_set(chunkIds=[chunk-1])
executor: success
```

Assertions:

- first call succeeds
- second call is rejected and transcript contains `redundant_successful_tool_call`
- agent can recover by choosing `complete_working_set`
- final status can progress, not stuck in repeated read

- [ ] **Step 2: Run e2e smoke tests**

Run:

```powershell
mvn -q "-Dtest=PostDraftReviewAgentEndToEndSmokeTest" test
```

Expected: PASS.

### Task 9: real one-minute debug validation

**Files:** no production file changes.

- [ ] **Step 1: Reset project from baseline**

Run:

```powershell
.\scripts\review-reset-from-baseline.ps1 -ProjectId book-draft-20260419151435
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Run controlled real agent start**

Run:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--spring.main.web-application-type=none --quillloom.postdraft.review.runtime.cli-enabled=true --quillloom.postdraft.review.runtime.cli-action=start --quillloom.postdraft.review.runtime.cli-project-id=book-draft-20260419151435 --quillloom.postdraft.review.runtime.max-wall-clock-minutes=1 --quillloom.postdraft.review.llm.timeout-seconds=60 --quillloom.postdraft.review.llm.max-retries=1"
```

Expected:

- No `unexpected_argument:*`.
- First `read_confirmed_terms` logs clear `tool_use` and `tool_result`.
- If model repeats same lookup, executor rejects with `redundant_successful_tool_call`.
- The model either recovers to `evaluate_focus` / `complete_working_set`, or after repeated duplicate calls fails as `NO_PROGRESS`.

- [ ] **Step 3: Reset project again**

Run:

```powershell
.\scripts\review-reset-from-baseline.ps1 -ProjectId book-draft-20260419151435
```

Expected: BUILD SUCCESS.

---

## 7. Red Line Self-Check

| Red line | Status | Explanation |
| --- | --- | --- |
| R-06: 不把运行期状态写回 PostDraftReviewPackage / ProjectKnowledgeBase | 未违反 | 工具调用记忆只在 runtime session 的 transcript/toolTrace/history 中，不写稳定领域对象。 |
| R-09: 不把 HITL 做成排障式 | 未违反 | 重复工具调用不会转人工；只有 agent 主动 `request_human_review` 才 WAITING_HUMAN。 |
| R-10: NO_PROGRESS 保持 FAILED | 未违反 | 重复成功调用被本地拒绝后仍走现有 NO_PROGRESS。 |
| R-11: 不新增工具、不往 executor 加新 switch case | 未违反 | 本计划升级 13 个既有工具的 definition，不新增工具；executor 只在既有 `read_confirmed_terms` case 内加 duplicate guard。 |
| R-12: 不做粗糙压缩摘要 | 未违反 | 本轮不做工具调用记录压缩，不做全局工具调用记录；只让当前 focus 的 transcript 记录更有结构。 |
| R-13: 不让 LLM 自由联网 | 未违反 | 无联网。 |
| R-14: 不把 loop 临时状态塞回 TranslationTaskInput | 未违反 | 不触碰 D 输入契约。 |

---

## 8. 实施顺序建议

1. Task 0 全工具正式定义：先让工具契约完整，避免 prompt/schema/validator 继续漂移。
2. Task 0.5 prompt 从 definition 渲染：让 LLM 看到操作规程。
3. Task 0.6 validator/schema 对齐：让本地契约和 LLM 契约一致。
4. Task 1 `ToolCallSignature`：锁定签名归一化，避免后面重复逻辑散落。
5. Task 2 `ReviewToolTrace`：让 session 能携带签名。
6. Task 3 formatter：统一 transcript 文案，避免 executor 字符串拼接膨胀。
7. Task 4 成功工具记忆：先让 agent 看得懂自己做过什么。
8. Task 5 duplicate guard：再加硬约束。
9. Task 6 NO_PROGRESS 验证：确保不转 HITL。
10. Task 7 prompt/console 可见性：保证人和模型都能诊断。
11. Task 8 scripted e2e：锁定恢复路径。
12. Task 9 真实 1 分钟验证：只验证行为，不跑完整项目。

---

## 9. 不做事项

- 不做完整 D-08 工具系统解耦。
- 不新增 ReviewTool。
- 不强制做完整 per-tool JSON Schema `oneOf` / discriminator；本轮只要求 schema 文本和 definition 对齐，避免 provider 兼容性风险。
- 不改变 `HumanInTheLoopGateway`。
- 不改变 D 流水线。
- 不做 checkpoint 崩溃恢复。
- 不做工具调用记录压缩。
- 不做项目级全局工具调用记录。
- 不把上一 focus 的完整工具调用明细继续塞进下一 focus prompt。
- 不跑完整 127 chunk agent，完整运行由用户手动执行。

---

## 10. 审核关注点

请重点审核：

1. 全工具 definition 字段是否足够表达操作规程，是否还缺少字段。
2. 是否接受“所有工具正式定义，但只有 `read_confirmed_terms` 本轮强制 duplicate guard”的分层。
3. `ReviewToolTrace` 加 `callSignature` 是否是合适承载点。
4. 重复成功工具调用是否应直接 rejected，还是应返回 success 但附加强 replan hint。当前计划选择 rejected，因为需要纳入 NO_PROGRESS。
5. transcript 中的 `tool_use/tool_result` 文本是否足够接近 claw-code 的结构化记忆，同时不引入真正 tool-message protocol。
6. 是否接受 `HistoryLog` 继续不进 prompt，只作为审计。
7. 是否接受“当前 focus 精确记忆，跨 focus 不保留工具明细”的生命周期边界。
