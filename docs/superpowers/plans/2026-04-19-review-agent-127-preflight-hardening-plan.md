# Review Agent 127 Chunk Preflight Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本计划是 127 chunk 真实冒烟前的最小加固，不做完整 D-08 工具系统重构。

**Goal:** 在不扩大到完整工具层解耦的前提下，修正 Review Agent 在真实长跑中最可能卡死的 prompt、工具参数 schema、修订上下文、自检和 scripted e2e 缺口。

**Architecture:** 保持当前“共用 tool-call 外壳 + ReviewToolRegistry 定义工具参数 + validator/guardrail/executor 后置校验”的结构。先把 registry 变成工具参数说明、示例、repair、validator 的唯一事实源；revision/self-check 补齐当前 chunk 与项目术语上下文；用 scripted e2e 锁定“发现术语冲突 -> 升级策略 -> 修订 -> 自检 -> 完成”的路径。完整注册式 `ReviewTool` 解耦是 D-08，后置。

**Tech Stack:** Java 17, Spring Boot, LangChain4j structured output, JUnit 5, Maven, Jackson。

---

## 1. 当前问题

### 1.1 工具参数 schema 是“混合字段池”，LLM 容易错参

当前 [OpenAiCompatibleReviewAgentStructuredGenerationClient.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java) 的 `INVESTIGATION_SCHEMA` 使用共用外壳：

```json
{
  "toolName": "...",
  "arguments": {},
  "reason": "..."
}
```

但 `arguments` 允许所有工具参数混在一起：

```text
count
chunkIds
sourceTerms
queryTerms
entries
reason
```

这意味着 JSON Schema 层并不知道 `read_confirmed_terms` 只应该带 `sourceTerms`，也不知道 `complete_working_set` 只应该带 `chunkIds`。

同时 [InvestigationPromptBuilder.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java) 的输出示例把 `count/sourceTerms/entries/chunkIds` 放在同一个 `arguments` 里：

```json
{
  "toolName": "...",
  "arguments": {
    "count": 1,
    "sourceTerms": ["Louki"],
    "entries": {
      "Louki": "露姬"
    },
    "chunkIds": ["chunk-1"]
  },
  "reason": "..."
}
```

这会直接诱导 LLM 输出“工具 A + 工具 B 参数”的混合调用。长跑时一旦进入 repair 循环，错参和 guardrail 拒绝会累积成 `NO_PROGRESS`，项目失败。

### 1.2 当前 validator 只检查必填和类型，不拒绝无关参数

[ReviewToolDecisionContractValidator.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java) 当前按工具校验必填参数和类型，例如：

- `read_confirmed_terms` 校验 `sourceTerms`
- `record_confirmed_terms` 校验 `entries`
- `complete_working_set` 校验 `chunkIds`
- `request_human_review` 校验 `reason`

但它不会拒绝无关参数。例如：

```json
{
  "toolName": "read_confirmed_terms",
  "arguments": {
    "sourceTerms": ["Le Condé"],
    "chunkIds": ["chunk-4"]
  },
  "reason": "..."
}
```

这种调用会通过 validator，虽然 `chunkIds` 对 `read_confirmed_terms` 没有语义。无关参数不一定立刻导致执行错误，但会污染 transcript，使后续 repair 和诊断难以判断 LLM 到底理解了哪个工具。

### 1.3 revision prompt 缺少真正修订所需上下文

[RevisionPromptBuilder.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionPromptBuilder.java) 当前输入主要是：

- projectId
- focus
- observationState
- currentStrategy
- targetStrategy
- operatorNote
- keyRationales
- residualRisks
- previousFailure

它没有包含：

- 当前 chunk 原文 `sourceText`
- 当前 effective 译文 `effectiveTranslatedText`
- 当前 chunk 的 `confirmedTermUpdates`
- 项目级 confirmed terms
- 最近已读到的术语证据
- 本轮要修复的具体冲突点

结果是 agent 可能已经判断“需要修订”，但 revision LLM 没有足够材料生成正确修订稿。Le Condé 场景下，agent 即使知道“孔代咖啡馆”和“勒孔代咖啡馆”冲突，也可能在 revision 阶段没有明确看到原文、当前译文和项目术语约束。

### 1.4 self-check prompt 缺少术语一致性约束

[RevisionSelfCheckPromptBuilder.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java) 当前包含：

- sourceText
- currentTranslatedText
- draft formalTranslation
- draft keyRationales
- draft residualRisks

但它没有显式包含项目 confirmed terms 和本轮冲突约束。因此 self-check 可能只检查“译文是否像一段通顺译文”，而不是检查“是否解决了本轮术语一致性问题”。

长跑中这会产生两类风险：

- 修订稿没有真正改掉冲突，但 self-check 通过。
- 修订稿改掉了冲突，但引入新的术语漂移，self-check 未发现。

### 1.5 `record_confirmed_terms` 和 `complete_working_set` 的边界需要强化

当前 system prompt 已经说明：

- 如果当前 chunk 译文与项目 confirmed terms 不一致，不要重复查同一术语。
- 如果当前 strategy 是 `KEEP`，应先 `evaluate_focus` 升级到 `LIGHT_EDIT` 或 `DEEP_EDIT`。
- 升级后才允许 `draft_revision`。

但还不够明确：

- `record_confirmed_terms` 只表示“写入项目术语资产”，不等于当前 chunk 已修好。
- 如果当前 chunk 正文已经使用了与 confirmed term 不一致的译名，记录术语后不能直接 `complete_working_set`。
- `complete_working_set` 前必须确认当前 chunk 的 effective translation 已满足项目术语约束。

这类规则需要同时进入 system prompt、tool definition 描述、validator/e2e 场景，否则 LLM 可能走“查术语/记术语/完成”的捷径。

### 1.6 真实 per-tool JSON Schema 不是本轮最佳前置项

“每个工具一套 JSON Schema”方向正确，但如果现在引入 `oneOf`、discriminator 或“先选工具再按工具 schema 生成参数”的两阶段 LLM 调用，会带来新风险：

- LangChain4j/OpenAI 兼容模型对复杂 JSON Schema 分支支持不一定稳定。
- 两阶段调用会增加 127 chunk 的调用次数和成本。
- 工具层完整解耦会改动 `ReviewToolExecutor`、registry、validator、prompt builder、测试，容易在 smoke 前引入新 bug。

因此本轮不做完整 per-tool JSON Schema，不做完整 D-08 注册式工具系统；只做 per-tool 参数 schema 的 prompt/validator/repair 强化。

---

## 2. 目标行为

### 2.1 工具调用输出

LLM 仍输出统一外壳：

```json
{
  "toolName": "read_confirmed_terms",
  "arguments": {
    "sourceTerms": ["Le Condé"]
  },
  "reason": "查询项目中 Le Condé 的稳定译名，确认当前 chunk 是否违反术语一致性"
}
```

但每个工具只允许自己的参数：

| toolName | allowed arguments |
| --- | --- |
| `read_previous_chunks` | `count` |
| `read_next_chunks` | `count` |
| `expand_block_context` | 无，必须 `{}` |
| `read_decision_notes` | 无，必须 `{}` |
| `read_transition_note` | 无，必须 `{}` |
| `lookup_knowledge_cards` | `queryTerms` 可选 |
| `read_confirmed_terms` | `sourceTerms` |
| `record_confirmed_terms` | `entries` |
| `evaluate_focus` | 无，必须 `{}` |
| `draft_revision` | 无，必须 `{}` |
| `request_human_review` | `reason` |
| `complete_working_set` | `chunkIds` |
| `complete_project` | 无，必须 `{}` |

无关参数应视为结构化输出契约错误，触发 repair；连续 repair 仍失败则按现有 `NO_PROGRESS` 逻辑失败，不转 HITL。

### 2.2 术语冲突处理路径

当 agent 发现当前 chunk 译文与项目 confirmed terms 不一致时，目标路径是：

```text
read_confirmed_terms
-> evaluate_focus
-> draft_revision
-> self-check
-> complete_working_set
```

如果当前 strategy 是 `KEEP`：

- 不允许直接 `draft_revision`
- 不允许直接 `complete_working_set`
- 必须先 `evaluate_focus`，让策略升级到 `LIGHT_EDIT` 或 `DEEP_EDIT`

如果 self-check 发现修订稿仍未解决术语冲突：

- self-check 返回 `passed=false`
- revision retry prompt 带上 previous findings 和必须遵守的术语约束
- 达到现有 revision retry 上限后受控失败

### 2.3 HITL 边界

本轮不改变 D-07：

- HITL 只由 agent 主动调用 `request_human_review` 触发。
- 人工输入是证据，不是命令。
- `NO_PROGRESS`、guardrail 连续拒绝、结构化输出持续错误仍是 bug，不转 HITL。

---

## 3. 受影响文件

### 3.1 Prompt 与 schema

1. [ReviewToolDefinition.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDefinition.java)
   - 增加工具参数渲染能力：allowed arguments、JSON 示例、无参数工具 `{}` 说明。
   - 不引入执行逻辑，避免提前做完整 D-08。

2. [ToolArgumentSchema.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/model/ToolArgumentSchema.java)
   - 如现有字段不足，补充示例值渲染所需信息。
   - 如果可以从 type 推导示例，则不加字段，避免扩大 record 变更。

3. [ReviewToolRegistry.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java)
   - 确认 13 个工具参数定义完整。
   - 强化 `record_confirmed_terms` 描述：只允许新增或同值确认；如果当前 chunk 译文与已确认译名不一致，记录术语后必须修订或重新评估，不能直接完成。

4. [ReviewAgentSystemPromptBuilder.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java)
   - 按工具渲染参数要求和最小 JSON 示例。
   - 对无参数工具明确：`arguments` 必须是 `{}`。
   - 删除或避免任何混合参数示例。

5. [InvestigationPromptBuilder.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java)
   - 删除当前混合 `arguments` 示例。
   - 改为引用 system prompt 的 per-tool 示例，或者只保留统一外壳示例：
     ```json
     {
       "toolName": "read_confirmed_terms",
       "arguments": {"sourceTerms": ["Le Condé"]},
       "reason": "..."
     }
     ```

6. [PromptBackedNextStepDecisionProvider.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java)
   - repair prompt 只展示当前错误工具的参数要求和示例。
   - 如果工具名未知，则展示全部工具列表，但仍按工具分组展示。

### 3.2 Validator / guardrail

1. [ReviewToolDecisionContractValidator.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java)
   - 新增“无关参数拒绝”。
   - 使用 `ReviewToolDefinition.argumentSchemas()` 计算 allowed argument names。
   - 对无参数工具要求 `arguments` 为空 map。
   - 保留现有类型校验。

2. [ReviewToolGuardrail.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolGuardrail.java)
   - 本轮不扩展 guardrail 语义，但实施 Task 3 时必须显式复核它与 validator 的职责边界。
   - 当前 guardrail 只负责工具注册和必填参数的本地拒绝：`unregistered_tool`、`missing_argument:*`。
   - validator 负责结构化输出契约：必填参数、类型校验、无关参数拒绝 `unexpected_argument:*`。
   - 如果实现过程中发现两者出现重复检查，优先保持 validator 作为 schema/类型契约层；guardrail 不新增 `unexpected_argument` 检查，避免形成第二套工具 schema。

3. [OpenAiCompatibleReviewAgentStructuredGenerationClient.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java)
   - 本轮不做 `oneOf` / discriminator。
   - 可保留 `investigationArgumentsSchema()` 的混合字段池作为底层结构化输出容错。
   - 真正语义约束由 per-tool prompt + contract validator 承担。

### 3.3 Revision / self-check

1. [RevisionPromptBuilder.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionPromptBuilder.java)
   - 修改 `build()` 和 `buildRetryPrompt()` 签名，增加 `PostDraftChunkRecord chunk`。
   - prompt 增加：
     - sourceText
     - currentTranslatedText，即 `chunk.effectiveTranslatedText()`
     - chunk.confirmedTermUpdates
     - session evidence
     - 最近 transcript 中与术语相关的证据
   - 明确要求 formalTranslation 必须修订当前 chunk 的完整正式译文，不是局部 diff。

2. [PromptBackedRevisionDraftProvider.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedRevisionDraftProvider.java)
   - 当前 `generate(session, chunk, strategy)` 已有 chunk 参数。
   - 将 chunk 传入 `RevisionPromptBuilder`。
   - retry prompt 同样传入 chunk；尤其要同步修改 `catch (RuntimeException firstFailure)` 内的 `promptBuilder.buildRetryPrompt(...)` 调用，否则首次 prompt 有 chunk、retry prompt 仍丢失 chunk 上下文。

3. [RevisionSelfCheckPromptBuilder.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java)
   - 增加项目 confirmed terms / 本轮证据 / chunk confirmedTermUpdates。
   - 明确自检规则：
     - 如果 draft 没有遵守项目 confirmed terms，`passed=false`。
     - 如果 draft 没有解决 previous findings，`passed=false`。
     - 如果修订稿为空或只输出局部片段，`passed=false`。

4. [LlmBackedRevisionSelfCheckService.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/LlmBackedRevisionSelfCheckService.java)
   - 如 self-check builder 签名变化，同步传入所需上下文。

### 3.4 Scripted e2e / prompt tests

1. [ReviewPromptBuilderTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java)
   - 增加测试：system prompt 渲染 per-tool 参数示例。
   - 增加测试：investigation prompt 不再包含混合 `count/sourceTerms/entries/chunkIds` 示例。
   - 增加测试：revision prompt 包含 source text、current translation、confirmedTermUpdates。
   - 增加测试：self-check prompt 包含项目术语一致性规则。

2. [ReviewToolRegistryTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/ReviewToolRegistryTest.java)
   - 确认 13 个工具都有正确参数定义。
   - 确认无参数工具的示例是 `{}`。

3. [PromptBackedNextStepDecisionProviderTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java)
   - 增加测试：错误工具 repair prompt 渲染该工具的参数要求。
   - 增加测试：无关参数导致 structured output repair。

4. [PostDraftReviewAgentEndToEndSmokeTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentEndToEndSmokeTest.java)
   - 增加 scripted 路径：
     ```text
     read_confirmed_terms
     -> evaluate_focus 返回 LIGHT_EDIT
     -> draft_revision 返回使用 confirmed term 的译文
     -> self-check passed
     -> complete_working_set
     ```
   - 增加 scripted 失败路径：
     ```text
     draft_revision 返回仍违反 confirmed term 的译文
     -> self-check failed
     -> retry draft_revision
     -> self-check passed
     -> complete_working_set
     ```

5. [OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java)
   - 增加测试：`read_confirmed_terms` 带无关 `chunkIds` 时，client 通过 contract validator 抛 `LlmStructuredOutputException`。

---

## 4. 实施任务

### Task 1: 工具参数 schema 渲染最小化

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDefinition.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ToolArgumentSchema.java`（仅在必要时）
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolRegistryTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

- [ ] **Step 1: 写 failing tests**

新增测试覆盖：

```java
@Test
void noArgumentToolsRenderEmptyArgumentsExample() {
    ReviewToolRegistry registry = ReviewToolRegistry.defaultRegistry();

    assertThat(registry.require("draft_revision").renderArgumentsExample())
            .isEqualTo("{}");
    assertThat(registry.require("evaluate_focus").renderArgumentsExample())
            .isEqualTo("{}");
}

@Test
void argumentToolsRenderOnlyTheirOwnArguments() {
    ReviewToolRegistry registry = ReviewToolRegistry.defaultRegistry();

    assertThat(registry.require("read_confirmed_terms").renderArgumentsExample())
            .contains("sourceTerms")
            .doesNotContain("chunkIds")
            .doesNotContain("entries")
            .doesNotContain("count");

    assertThat(registry.require("complete_working_set").renderArgumentsExample())
            .contains("chunkIds")
            .doesNotContain("sourceTerms")
            .doesNotContain("entries")
            .doesNotContain("count");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -q "-Dtest=ReviewToolRegistryTest,ReviewPromptBuilderTest" test
```

Expected: FAIL because `renderArgumentsExample()` 尚不存在或输出仍是旧格式。

- [ ] **Step 3: 实现最小渲染方法**

在 `ReviewToolDefinition` 增加：

```java
public Set<String> allowedArguments() {
    return argumentSchemas.stream()
            .map(ToolArgumentSchema::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
}

public String renderArgumentsExample() {
    if (argumentSchemas.isEmpty()) {
        return "{}";
    }
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < argumentSchemas.size(); i++) {
        ToolArgumentSchema schema = argumentSchemas.get(i);
        if (i > 0) {
            sb.append(", ");
        }
        sb.append("\"").append(schema.name()).append("\": ").append(schema.exampleJsonValue());
    }
    sb.append("}");
    return sb.toString();
}
```

在 `ToolArgumentSchema` 增加按 type 推导示例：

```java
public String exampleJsonValue() {
    return switch (type) {
        case "integer" -> "1";
        case "string" -> "\"...\"";
        case "string[]" -> "[\"...\"]";
        case "object{string:string}" -> "{\"sourceTerm\": \"译名\"}";
        default -> "\"...\"";
    };
}
```

- [ ] **Step 4: 跑测试确认通过**

Run:

```powershell
mvn -q "-Dtest=ReviewToolRegistryTest,ReviewPromptBuilderTest" test
```

Expected: PASS。

### Task 2: System / investigation prompt 改为 per-tool 示例

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

- [ ] **Step 1: 写 failing tests**

测试 system prompt：

```java
@Test
void systemPromptRendersPerToolArgumentExamples() {
    String prompt = new ReviewAgentSystemPromptBuilder()
            .build(ReviewToolRegistry.defaultRegistry().definitions());

    assertThat(prompt).contains("read_confirmed_terms");
    assertThat(prompt).contains("\"sourceTerms\"");
    assertThat(prompt).contains("complete_working_set");
    assertThat(prompt).contains("\"chunkIds\"");
    assertThat(prompt).contains("draft_revision");
    assertThat(prompt).contains("arguments={}");
}
```

测试 investigation prompt 不再有混合示例：

```java
@Test
void investigationPromptDoesNotShowMixedToolArguments() {
    InvestigationPromptBuilder builder = new InvestigationPromptBuilder();
    PostDraftReviewSession session = ReviewAgentFixtures.singleChunkSession();

    String prompt = builder.build(
            session,
            ReviewToolRegistry.defaultRegistry().definitions(),
            List.of()
    );

    assertThat(prompt).doesNotContain("\"count\": 1,\n                    \"sourceTerms\"");
    assertThat(prompt).doesNotContain("\"entries\":");
    assertThat(prompt).contains("\"toolName\"");
    assertThat(prompt).contains("\"arguments\"");
    assertThat(prompt).contains("\"reason\"");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest" test
```

Expected: FAIL because prompt 仍包含混合 arguments 示例。

- [ ] **Step 3: 修改 system prompt 工具列表渲染**

`ReviewAgentSystemPromptBuilder.renderToolDefinitions()` 对每个工具输出：

```text
- read_confirmed_terms: ...
  requiredArguments=[sourceTerms]
  arguments={"sourceTerms": ["..."]}
  参数: sourceTerms:string[] required ...
```

无参数工具输出：

```text
- draft_revision: ...
  requiredArguments=[]
  arguments={}
```

- [ ] **Step 4: 修改 investigation prompt 输出契约**

将混合示例替换为：

```json
{
  "toolName": "read_confirmed_terms",
  "arguments": {"sourceTerms": ["Le Condé"]},
  "reason": "为什么此时需要这个工具"
}
```

并增加文字约束：

```text
arguments 必须只包含所选 toolName 的参数；无参数工具必须输出 "arguments": {}。
不要把多个工具的参数混在同一个 arguments 对象里。
```

- [ ] **Step 5: 跑测试确认通过**

Run:

```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest" test
```

Expected: PASS。

### Task 3: Contract validator 拒绝无关参数

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java`
- Inspect: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolGuardrail.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolRegistryTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`

- [ ] **Step 1: 写 failing tests**

Validator 测试：

```java
@Test
void rejectsArgumentsThatDoNotBelongToSelectedTool() {
    ReviewToolDecisionContractValidator validator = new ReviewToolDecisionContractValidator();
    ReviewToolDecision decision = new ReviewToolDecision(
            "read_confirmed_terms",
            Map.of("sourceTerms", List.of("Le Condé"), "chunkIds", List.of("chunk-4")),
            "查询术语"
    );

    assertThat(validator.validate(decision, ReviewToolRegistry.defaultRegistry()))
            .contains("unexpected_argument:chunkIds");
}

@Test
void rejectsArgumentsForNoArgumentTool() {
    ReviewToolDecisionContractValidator validator = new ReviewToolDecisionContractValidator();
    ReviewToolDecision decision = new ReviewToolDecision(
            "draft_revision",
            Map.of("sourceTerms", List.of("Le Condé")),
            "修订"
    );

    assertThat(validator.validate(decision, ReviewToolRegistry.defaultRegistry()))
            .contains("unexpected_argument:sourceTerms");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -q "-Dtest=ReviewToolRegistryTest,PromptBackedNextStepDecisionProviderTest,OpenAiCompatibleReviewAgentStructuredGenerationClientTest" test
```

Expected: FAIL because validator 当前不拒绝无关参数。

- [ ] **Step 3: 实现 unexpected argument 校验**

先确认 `ReviewToolGuardrail` 当前只处理：

```java
if (!registry.contains(call.toolName())) {
    return ReviewGuardrailRejection.rejected(call.toolName(), "unregistered_tool");
}
for (String requiredArgument : definition.requiredArguments()) {
    Object value = call.arguments().get(requiredArgument);
    if (value == null) {
        return ReviewGuardrailRejection.rejected(call.toolName(), "missing_argument:" + requiredArgument);
    }
}
```

不要在 guardrail 增加 `unexpected_argument`。本任务的职责分配固定为：

```text
ReviewToolGuardrail:
- unregistered_tool
- missing_argument:*

ReviewToolDecisionContractValidator:
- unregistered_tool
- missing_argument:*
- invalid_argument:*
- unexpected_argument:*
```

两者都可能看见 `unregistered_tool` / `missing_argument:*`，这是当前调用链历史遗留的重叠；本轮不扩大重叠面。新增的无关参数拒绝只放在 validator。

在 required argument 检查前后加入：

```java
Set<String> allowedArguments = toolRegistry.require(decision.toolName()).allowedArguments();
for (String argumentName : decision.arguments().keySet()) {
    if (!allowedArguments.contains(argumentName)) {
        return Optional.of("unexpected_argument:" + argumentName);
    }
}
```

注意：

- `arguments` 必须非 null；如果 `ReviewToolDecision` record 已经归一化为 empty map，则不重复处理。
- 无参数工具的 `allowedArguments` 为空，任何参数都会 rejected。
- 不改变已有 `missing_argument` 和 `invalid_argument` 语义。

- [ ] **Step 4: 更新 repair prompt 测试**

`PromptBackedNextStepDecisionProvider` 的 repair prompt 应包含：

```text
unexpected_argument:chunkIds
read_confirmed_terms
arguments={"sourceTerms": ["..."]}
```

并且不应建议 `chunkIds`。

- [ ] **Step 5: 跑测试确认通过**

Run:

```powershell
mvn -q "-Dtest=ReviewToolRegistryTest,PromptBackedNextStepDecisionProviderTest,OpenAiCompatibleReviewAgentStructuredGenerationClientTest" test
```

Expected: PASS。

### Task 4: Revision prompt 补齐 chunk 与术语上下文

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedRevisionDraftProvider.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftRevisionServiceTest.java`

- [ ] **Step 1: 写 failing tests**

```java
@Test
void revisionPromptIncludesSourceCurrentTranslationAndChunkTermUpdates() {
    RevisionPromptBuilder builder = new RevisionPromptBuilder();
    PostDraftReviewSession session = ReviewAgentFixtures.singleChunkSession();
    PostDraftChunkRecord chunk = ReviewAgentFixtures.chunkWithTermUpdate(
            "chunk-4",
            "Le Condé était plein.",
            "孔代咖啡馆里坐满了人。",
            Map.of("Le Condé", "孔代咖啡馆")
    );

    String prompt = builder.build(
            session,
            chunk,
            ReviewStrategy.LIGHT_EDIT,
            List.of("项目术语要求 Le Condé 使用孔代咖啡馆"),
            List.of()
    );

    assertThat(prompt).contains("Le Condé était plein.");
    assertThat(prompt).contains("孔代咖啡馆里坐满了人。");
    assertThat(prompt).contains("confirmedTermUpdates");
    assertThat(prompt).contains("Le Condé");
    assertThat(prompt).contains("孔代咖啡馆");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest,PostDraftRevisionServiceTest" test
```

Expected: FAIL because `RevisionPromptBuilder.build()` 当前没有 chunk 参数。

- [ ] **Step 3: 修改 builder 签名和 prompt**

将签名改为：

```java
public String build(PostDraftReviewSession session,
                    PostDraftChunkRecord chunk,
                    ReviewStrategy targetStrategy,
                    List<String> keyRationales,
                    List<String> residualRisks)
```

retry 同步增加 chunk：

```java
public String buildRetryPrompt(PostDraftReviewSession session,
                               PostDraftChunkRecord chunk,
                               ReviewStrategy targetStrategy,
                               List<String> keyRationales,
                               List<String> residualRisks,
                               String previousFailure)
```

prompt 增加：

```text
[当前 chunk]
- chunkId
- sourceText
- currentTranslatedText
- confirmedTermUpdates

[修订要求]
- formalTranslation 必须是当前 chunk 的完整正式译文，不是局部 diff。
- 如果 keyRationales 指出术语冲突，必须在 formalTranslation 中解决。
- 不允许把人工回答、transcript 或工具诊断原样写进译文。
```

- [ ] **Step 4: 修改 provider 调用**

`PromptBackedRevisionDraftProvider.generate(session, chunk, strategy)` 调用：

```java
String userPrompt = promptBuilder.build(session, chunk, strategy, keyRationales, residualRisks);
```

retry 调用必须同步修改。当前代码里的 retry 调用位于 `catch (RuntimeException firstFailure)` 内，如果漏改这一段，第一次生成失败后第二次 prompt 会重新丢失 source/current translation/confirmedTermUpdates：

```java
String retryPrompt = promptBuilder.buildRetryPrompt(session, chunk, strategy, keyRationales, residualRisks, previousFailure);
```

- [ ] **Step 5: 跑测试确认通过**

Run:

```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest,PostDraftRevisionServiceTest" test
```

Expected: PASS。

### Task 5: Self-check prompt 增强术语一致性检查

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/LlmBackedRevisionSelfCheckService.java`（如签名需传更多上下文）
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftRevisionServiceTest.java`

- [ ] **Step 1: 写 failing tests**

```java
@Test
void selfCheckPromptRequiresConfirmedTermConsistency() {
    RevisionSelfCheckPromptBuilder builder = new RevisionSelfCheckPromptBuilder();
    PostDraftReviewSession session = ReviewAgentFixtures.singleChunkSessionWithEvidence(
            List.of("read_confirmed_terms: Le Condé -> 孔代咖啡馆")
    );
    PostDraftChunkRecord chunk = ReviewAgentFixtures.chunkWithTermUpdate(
            "chunk-4",
            "Le Condé était plein.",
            "勒孔代咖啡馆里坐满了人。",
            Map.of("Le Condé", "孔代咖啡馆")
    );
    RevisionDraft draft = new RevisionDraft(
            "孔代咖啡馆里坐满了人。",
            "LIGHT_EDIT",
            List.of("修正 Le Condé 译名"),
            List.of()
    );

    String prompt = builder.build(session, chunk, ReviewStrategy.LIGHT_EDIT, draft);

    assertThat(prompt).contains("confirmed terms");
    assertThat(prompt).contains("Le Condé");
    assertThat(prompt).contains("孔代咖啡馆");
    assertThat(prompt).contains("passed=false");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest,PostDraftRevisionServiceTest" test
```

Expected: FAIL because self-check prompt 当前没有项目术语约束。

- [ ] **Step 3: 修改 self-check prompt**

增加：

```text
[必须检查]
1. formalTranslation 是否是完整正式译文。
2. formalTranslation 是否遵守本轮证据中的项目级 confirmed terms。
3. 如果 currentTranslatedText 与 confirmed terms 冲突，draft 是否已修复该冲突。
4. 如果 previous self-check findings 非空，draft 是否逐条解决。
5. 如果以上任一项失败，必须输出 passed=false，并在 findings 中说明原因。
```

术语证据来源本轮不新增复杂投影，先从以下已有上下文渲染：

- `chunk.confirmedTermUpdates()`
- `session.transcriptStore().replay()` 最近记录
- `session.evidenceGaps()` / evidence summaries，如现有模型可取

- [ ] **Step 4: 跑测试确认通过**

Run:

```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest,PostDraftRevisionServiceTest" test
```

Expected: PASS。

### Task 6: 强化 `record_confirmed_terms` 与完成边界

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolRegistryTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

- [ ] **Step 1: 写 failing tests**

```java
@Test
void recordConfirmedTermsDescriptionDoesNotImplyCurrentChunkCompletion() {
    String description = ReviewToolRegistry.defaultRegistry()
            .require("record_confirmed_terms")
            .description();

    assertThat(description).contains("不等于当前 chunk 已完成");
    assertThat(description).contains("如果当前译文与已确认译名不一致");
}
```

System prompt 测试：

```java
@Test
void systemPromptRequiresRevisionBeforeCompletionWhenConfirmedTermConflicts() {
    String prompt = new ReviewAgentSystemPromptBuilder()
            .build(ReviewToolRegistry.defaultRegistry().definitions());

    assertThat(prompt).contains("record_confirmed_terms 不等于 complete_working_set");
    assertThat(prompt).contains("当前译文与 confirmed term 不一致");
    assertThat(prompt).contains("必须 evaluate_focus 或 draft_revision");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -q "-Dtest=ReviewToolRegistryTest,ReviewPromptBuilderTest" test
```

Expected: FAIL because当前文案不够明确。

- [ ] **Step 3: 修改描述与 system prompt**

`record_confirmed_terms` 描述改为：

```text
将本轮新确定的稳定译名写回项目一致译名资产；只允许新增或同值确认。
调用它不等于当前 chunk 已完成。
如果当前译文与已确认译名不一致，必须继续 evaluate_focus/draft_revision 修复后才能 complete_working_set。
```

system prompt 增加：

```text
- record_confirmed_terms 不等于 complete_working_set；它只是写入术语资产。
- 如果当前译文与 confirmed term 不一致，记录术语后不能直接完成，必须修订或明确重新评估为无需修订。
```

- [ ] **Step 4: 跑测试确认通过**

Run:

```powershell
mvn -q "-Dtest=ReviewToolRegistryTest,ReviewPromptBuilderTest" test
```

Expected: PASS。

### Task 7: Scripted e2e 覆盖术语冲突修订路径

**Files:**
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentEndToEndSmokeTest.java`
- Modify: test fixture files if required by existing project structure

- [ ] **Step 1: 写 scripted happy repair path**

新增测试：

```java
@Test
void repairsConfirmedTermMismatchAfterStrategyUpgrade() {
    ScriptedReviewAgentGenerationPort generation = new ScriptedReviewAgentGenerationPort()
            .nextToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Le Condé")))
            .nextEvaluation(ReviewStrategy.LIGHT_EDIT, "confirmed term mismatch", "SUFFICIENT", false)
            .nextRevisionDraft("孔代咖啡馆里坐满了人。", "LIGHT_EDIT")
            .nextSelfCheck(true, "", List.of())
            .nextToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-4")));

    PostDraftReviewAgentResult result = runSingleFocusAgent(generation, chunkWithMismatch());

    assertThat(result.status()).isEqualTo(ProjectReviewStatus.COMPLETED);
    assertThat(savedChunk("chunk-4").revisedTranslatedText()).contains("孔代咖啡馆");
}
```

如果现有 `ScriptedReviewAgentGenerationPort` API 不同，按现有方法类型队列实现，原则是按方法类型分别排队，不按全局调用序号错位。

- [ ] **Step 2: 写 self-check retry path**

新增测试：

```java
@Test
void retriesRevisionWhenSelfCheckFindsConfirmedTermMismatch() {
    ScriptedReviewAgentGenerationPort generation = new ScriptedReviewAgentGenerationPort()
            .nextToolDecision("read_confirmed_terms", Map.of("sourceTerms", List.of("Le Condé")))
            .nextEvaluation(ReviewStrategy.LIGHT_EDIT, "confirmed term mismatch", "SUFFICIENT", false)
            .nextRevisionDraft("勒孔代咖啡馆里坐满了人。", "LIGHT_EDIT")
            .nextSelfCheck(false, "confirmed_term_mismatch", List.of("Le Condé must be 孔代咖啡馆"))
            .nextRevisionDraft("孔代咖啡馆里坐满了人。", "LIGHT_EDIT")
            .nextSelfCheck(true, "", List.of())
            .nextToolDecision("complete_working_set", Map.of("chunkIds", List.of("chunk-4")));

    PostDraftReviewAgentResult result = runSingleFocusAgent(generation, chunkWithMismatch());

    assertThat(result.status()).isEqualTo(ProjectReviewStatus.COMPLETED);
}
```

- [ ] **Step 3: 运行测试确认失败**

Run:

```powershell
mvn -q "-Dtest=PostDraftReviewAgentEndToEndSmokeTest" test
```

Expected: 初次可能 FAIL because prompt/provider/validator 尚未全部调整，或 scripted helper 缺少测试方法。

- [ ] **Step 4: 完成测试 fixture 适配**

如当前测试没有 `chunkWithMismatch()`，新增局部 fixture：

```java
private PostDraftChunkRecord chunkWithMismatch() {
    return ReviewAgentFixtures.chunkWithTermUpdate(
            "chunk-4",
            "Le Condé était plein.",
            "勒孔代咖啡馆里坐满了人。",
            Map.of("Le Condé", "孔代咖啡馆")
    );
}
```

如没有 `ReviewAgentFixtures.chunkWithTermUpdate()`，在 test fixture 中新增，确保 `translatedText` 是 D 初稿，`revisedTranslatedText` 初始为 null。

- [ ] **Step 5: 跑测试确认通过**

Run:

```powershell
mvn -q "-Dtest=PostDraftReviewAgentEndToEndSmokeTest" test
```

Expected: PASS。

### Task 8: 127 前置验证命令

**Files:**
- No source files unless previous tasks reveal missing test fixture.

- [ ] **Step 1: 跑 review agent prompt/schema/loop 定向测试**

Run:

```powershell
mvn -q "-Dtest=ReviewToolRegistryTest,ReviewPromptBuilderTest,PromptBackedNextStepDecisionProviderTest,PostDraftRevisionServiceTest,PostDraftReviewAgentEndToEndSmokeTest,OpenAiCompatibleReviewAgentStructuredGenerationClientTest" test
```

Expected: PASS。

- [ ] **Step 2: 跑 1-5 chunk 级 smoke 或短项目 smoke**

如果有现成短项目 smoke，先跑短项目。没有短项目时，用当前 book smoke 的新 projectId 跑小样本配置，不直接上 127。

Expected:

- agent 能调用 `read_confirmed_terms`
- 能从 `KEEP` 升级到 `LIGHT_EDIT`
- 能执行 `draft_revision`
- 能通过 self-check 后 `complete_working_set`
- console 日志不截断或完整写文件

- [ ] **Step 3: 127 chunk 前创建 baseline**

对新 D 输出 project 执行：

```powershell
.\scripts\review-create-baseline.ps1 -ProjectId <book-draft-timestamp>
```

Expected:

- baseline 文件创建成功
- review package 存在
- session 文件不存在或不影响 baseline

- [ ] **Step 4: 启动 127 chunk review agent**

```powershell
.\scripts\review-start.ps1 -ProjectId <book-draft-timestamp>
```

Expected:

- 开始输出 per-tool tool call 日志
- 如转 HITL，session 持久化到本地 JSON
- 如失败，FAILED session 保存用于诊断，但不能 resume

---

## 5. 不做事项

1. 不做完整 D-08 工具层解耦。
   - 不新增 `ReviewTool` interface。
   - 不把 `ReviewToolExecutor` switch 拆成 13 个工具类。
   - 不新增工具。

2. 不做真正 per-tool JSON Schema。
   - 不引入 `oneOf` / discriminator。
   - 不改成“先选工具，再按工具 schema 二次生成参数”的两次 LLM 调用。

3. 不改变 HITL 语义。
   - `NO_PROGRESS` 不转 HITL。
   - 人工回答仍只进 transcript，不进 `TranslationTaskInput`。

4. 不做 checkpoint 持久化。
   - 仍只在 `WAITING_HUMAN` 和 FAILED 诊断场景落 session。
   - 不做每 focus 自动 checkpoint。

5. 不做联网搜索。
   - Review Agent 仍只使用本地 chunk、上下文、知识库和人工证据。

---

## 6. 实施顺序建议

1. Task 1-3：先修工具 schema/prompt/validator。
   - 这是降低错工具调用和 repair 循环的前提。

2. Task 4-5：再修 revision/self-check 上下文。
   - 这是让 agent 真正能从“发现问题”走到“修掉问题”的前提。

3. Task 6：强化 record/complete 边界。
   - 防止记录术语后提前完成。

4. Task 7：补 scripted e2e。
   - 先证明术语冲突路径在本地可控通过。

5. Task 8：跑定向测试、短 smoke、baseline、127 chunk。

不要先做 D-08 工具层解耦。原因是它正确但不够小，容易在真实 smoke 前引入新的 Bean 装配、测试 fixture、执行路径问题。

---

## 7. 红线自检

| 红线 | 结果 | 说明 |
| --- | --- | --- |
| R-06 不把运行期状态写回稳定领域对象 | 未违反 | 本计划只改 prompt/schema/validator/revision，上下文不写回领域对象 |
| R-09 HITL 保持求助式 | 未违反 | 不改变人工输入语义 |
| R-10 NO_PROGRESS 保持 FAILED | 未违反 | 错参/guardrail 持续失败仍是 bug，不转 HITL |
| R-11 不继续往工具系统加新 switch | 未违反 | 本轮不新增工具，不扩展 executor switch |
| R-12 不做压缩摘要 | 未违反 | 不涉及摘要压缩 |
| R-13 不联网 | 未违反 | 不引入联网搜索 |
| R-14 人工回答只进 transcript | 未违反 | 不改 HITL 输入流 |

---

## 8. 审查重点

请重点审查以下决策：

1. 是否同意本轮不做真正 per-tool JSON Schema，只做 per-tool prompt/schema metadata/validator。
2. 是否同意无关参数直接作为 `LlmStructuredOutputException`，走 repair，而不是在 executor 中忽略。
3. 是否同意 revision prompt 增加 chunk 原文、当前译文、chunk confirmedTermUpdates，但不新增新的稳定领域字段。
4. 是否同意完整 D-08 工具层解耦放到 127 chunk 冒烟之后。
