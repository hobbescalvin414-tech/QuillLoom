# Review Agent `invalid_argument:entries` Repair 提示增强详细设计稿（执行版）

## 1. 目标与边界

### 1.1 目标
- 只解决当前最高频失败：`record_confirmed_terms` 的 `invalid_argument:entries`。
- 让第二次修复提示具备可执行性，明确告诉模型：
  - 错在什么位置（`arguments.entries`）。
  - 为什么非法（形状不对 / 为空 / 类型不对）。
  - 合法形状长什么样（非空 `object{string:string}`）。
  - 典型反例有哪些（pair-object / array / `"A=B"` / `{}`）。
  - 如果拿不出明确 term pair，不应继续硬选 `record_confirmed_terms`。

### 1.2 非目标（本稿明确不做）
- 不做协议重构（不改 `toolName + arguments + reason` 总协议）。
- 不新增工具。
- 不引入 `oneOf` / discriminator。
- 不做 repair 下沉或新架构层。
- 不扩展到 2B / 全局 tool governance 重构。

## 2. 现状路径核对（修正“主改点落错层”）

### 2.1 生产主路径先命中 structured-output repair
- `PromptBackedNextStepDecisionProvider.decide(...)` 中，`generationPort.generateNextToolDecision(...)` 抛出 `LlmStructuredOutputException` 后，会走 `buildStructuredOutputRepairPrompt(...)` 重试，不会先走 decision-repair。
- 证据：
  - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:49`
  - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:54`
  - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:65`
  - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:119`

### 2.2 `invalid_argument:entries` 在 client 侧先被判为结构化失败
- client 在反序列化后调用 contract validator，若不合法直接抛：
  `Review agent invalid structured tool decision: invalid_argument:entries; rawOutput=...`
- 证据：
  - `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:103`
  - `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:105`

### 2.3 结论
- 主修点必须覆盖两条 repair 链路，且以 structured-output repair 为第一优先：
  - `buildStructuredOutputRepairPrompt(...)`（生产主路径）
  - `buildDecisionRepairPrompt(...)`（本地校验路径）

## 3. 当前 repair 为何不够

### 3.1 已有信息（不是“完全没 repair”）
- decision-repair 已包含 `validationError`、`previousArguments`、参数要求、参数示例。
- structured-output repair 已包含 `structuredOutputError` 与通用输出约束。
- 证据：
  - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:93`
  - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:96`
  - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:99`
  - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:127`
  - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:130`

### 3.2 缺口
- 对 `invalid_argument:entries` 没有专项、可执行的修复指令。
- structured-output repair 文案偏通用，未把 `entries={}`、pair-object、array 等错误逐项点名。
- 因此模型常在“知道错了”但“不知道怎么改成可执行 map”之间反复失败。

## 4. 主方案：共用 `entries` 专项修正块（最小收敛）

### 4.1 设计原则
- 只修格式，不改语义授权。
- 不把 repair 变成业务意图改写器。
- 文案在多入口一致，避免“一个入口讲透，一个入口泛化”。

### 4.2 方案结构
- 在 provider 内引入“共用 `entries` 专项修正块”文本构造函数（概念名，不限定实现名）。
- 触发条件：
  - decision-repair：`validationError == invalid_argument:entries`
  - structured-output repair：`structuredOutputError` 文本包含 `invalid_argument:entries`
- 注入位置：
  - `buildDecisionRepairPrompt(...)`
  - `buildStructuredOutputRepairPrompt(...)`
- 相关入口证据：
  - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:80`
  - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:119`

### 4.3 专项修正块必须包含的硬信息
- 错误定位：`arguments.entries`。
- 合法格式：必须是非空 map，形如 `{"<SOURCE_TERM>":"<TARGET_TERM>"}`。
- 显式声明：`entries={}` 也非法（空 map 非法）。
- 显式反例：
  - `{"entries":{"sourceTerm":"...","targetTerm":"..."}}`
  - `{"entries":[{"sourceTerm":"...","targetTerm":"..."}]}`
  - `{"entries":["A=B"]}`
- 执行动作提示：
  - 若继续 `record_confirmed_terms`，必须给出至少一组明确 `source->target`。
  - 若当前证据不足以给出 term pair，应改选调查/评估动作，不要硬凑 `entries`。

### 4.4 语义边界（防扩散）
- 允许：修正 `arguments.entries` 的结构化格式。
- 不允许：
  - 自动改写 `toolName`
  - 自动重写 `reason` 的业务结论
  - 自动把失败“修复”为另一个业务动作

## 5. 配套文案收敛（非主改点，低风险）

### 5.1 `ToolArgumentSchema` 示例去具体词条化
- 当前 `object{string:string}` 示例仍是具体词条：
  - `{"Bernolle":"Bernolle CN"}`
- 建议改为占位示例，强调结构而非词条记忆。
- 证据：
  - `src/main/java/io/quillloom/application/postdraft/review/model/ToolArgumentSchema.java:37`

### 5.2 `ReviewToolRegistry` 的 `entries` 描述补充可执行限制
- 在 `record_confirmed_terms.entries` 描述中明确“非空 map + 空对象非法 + 典型反例非法”。
- 证据：
  - `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java:103`

### 5.3 client 层处理
- 不新增 client 逻辑；沿用“从 registry 注入 entries 描述”的现有机制即可。
- 证据：
  - `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:315`
  - `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:317`
  - `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:328`
  - `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java:262`
  - `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java:295`

## 6. 测试设计（必须补齐真实入口口径）

### 6.1 Validator 测试
- 新增：`entries={}` -> `invalid_argument:entries`
- 现状依据：当前 `validateStringMap` 已将空 map 判 invalid，但测试未覆盖。
- 证据：
  - `src/main/java/io/quillloom/application/postdraft/review/model/ToolArgumentSchema.java:79`
  - `src/test/java/io/quillloom/application/postdraft/review/ReviewToolDecisionContractValidatorTest.java:20`
  - `src/test/java/io/quillloom/application/postdraft/review/ReviewToolDecisionContractValidatorTest.java:34`
  - `src/test/java/io/quillloom/application/postdraft/review/ReviewToolDecisionContractValidatorTest.java:48`
  - `src/test/java/io/quillloom/application/postdraft/review/ReviewToolDecisionContractValidatorTest.java:62`

### 6.2 Client 测试
- 新增：rawOutput 为 `entries:{}` 时，抛错应包含：
  - `invalid_argument:entries`
  - `rawOutput=...`
- 现状依据：已有 pair-object rawOutput 用例，无 `entries:{}` 用例。
- 证据：
  - `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java:482`

### 6.3 Provider 测试（关键）
- 新增“真实主路径”用例：
  1. 第一次 `generationPort` 抛：`Review agent invalid structured tool decision: invalid_argument:entries; rawOutput=...`
  2. 第二次返回合法 `record_confirmed_terms` map
  3. 断言第二次 prompt 命中 `entries` 专项修正块关键句
- 现状依据：当前仅覆盖 missing_argument decision-repair 与泛化 structured-output repair。
- 证据：
  - `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java:39`
  - `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java:63`
  - `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java:85`

### 6.4 Schema 文本断言
- 若示例改为占位符，需要同步更新 schema 文本断言，避免继续绑定具体词条。
- 证据：
  - `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java:289`
  - `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java:290`

## 7. 验收标准

### 7.1 功能验收
- 同一轮 `invalid_argument:entries` 失败后，repair prompt 必须明确包含：
  - `entries` 非空 map 规则
  - `entries={}` 非法
  - 三类典型反例
  - “无法给出 term pair 时，不应继续硬选 record_confirmed_terms”
- 两条 repair 链路均可命中专项修正块：
  - structured-output repair
  - decision-repair

### 7.2 回归验收
- 不改变现有 client 语义处理边界。
- 不新增协议结构复杂度。
- 不影响既有工具权限/业务 guardrail。

## 8. 风险与控制

### 8.1 风险
- 只改 decision-repair、漏改 structured-output repair，生产仍挂。
- 测试只测“希望入口”，不测“真实入口”。
- repair 文案写得过于业务化，滑向语义改写器。

### 8.2 控制
- 强制双入口接入专项修正块。
- 强制新增 provider 真实主路径测试。
- 文案审查按“格式修复优先”准则，禁止业务意图改写。

## 9. 小修边界 vs 架构重构（明确划线）

### 9.1 属于小修边界（本稿）
- provider repair 文案增强（双入口接入同一专项修正块）。
- `ToolArgumentSchema` / `ReviewToolRegistry` 的示例与描述收敛。
- validator / client / provider 的测试口径补齐。

### 9.2 属于架构重构（本稿不做）
- 改造工具调用主协议（如完整 `tool_use/tool_result` 迁移）。
- 引入 per-tool provider `oneOf` 结构化协议。
- 新增 repair 下沉层或跨层语义授权机制。

## 10. 一句话结论（交付 Reviewer）
- 先把 `invalid_argument:entries` 的专项修正块做成“provider 双入口共用能力”，并补齐“真实 structured-output repair 入口”测试；暂不做协议级重构与 repair 下沉。

