# Review Agent 两阶段工具调用设计评审稿（先救火，后重构）

## 0. 范围与约束

- 本稿只做设计，不改代码，不提交 patch。
- 分两部分：
  - 第一部分：格式稳定性最小收敛（优先级最高，先补最容易炸穿点）。
  - 第二部分：下一阶段协议重构评审（前提是第一部分完成并验证）。
- 架构红线保持不变：不回退大 orchestrator，不改造成通用 agent/runtime 平台，不做 repair 下沉。

证据：
- 单 agent 自主决策边界：`docs/superpowers/plans/2026-04-18-review-agent-direction-anchor.md:36`
- 本轮不做 repair 下沉：`docs/superpowers/plans/2026-04-20-review-agent-action-id-hardening-plan.md:83-84,206-210`

---

## 第一部分：增强工具调用提示与范例（格式稳定性最小收敛）

### A1. 当前格式炸穿的直接原因

1. `arguments` 仍是通用 union，`entries` 在 provider schema 中仍是宽对象，模型容易产出“看似合理但形状错误”的 JSON。  
证据：`src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:42-43,290-299`

2. `invalid_argument:entries` 是后置暴露：模型先产出，再由 validator 拒绝；拒绝后会抛为 structured output failure。  
证据：`src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java:34-40`，`src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:101-106`

3. 运行时遇到该异常会导致项目失败（`LLM_CALL_FAILED`），不是软降级。  
证据：`src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java:173-180`

4. 真实现象仍集中在 `invalid_argument:entries`。  
证据：`docs/superpowers/plans/2026-04-20-review-agent-stability-hardening-plan.md:96-103`

---

### B1. 现有提示/示例/模板污染清单

1. `InvestigationPromptBuilder` 仍有固定词条示例 `Le Condé`，属于模板污染。  
证据：`src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java:49-52`

2. `entries` 示例仍使用具体实体词条（`Bernolle`），会强化“词条记忆”而非“结构记忆”。  
证据：`src/main/java/io/quillloom/application/postdraft/review/model/ToolArgumentSchema.java:37`，`src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java:103`

3. `ReviewToolDefinition.renderArgumentsExample()` 会把具体词条示例传播到 system prompt / repair / schema summary。  
证据：`src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDefinition.java:83-90`，`src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:100-102,130-133`，`src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:273-276`

4. 本地纠偏提示中也有具体词条示例（`Le Bouquet`），会继续注入固定词条模式。  
证据：`src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java:665-670`

结论：应统一改为占位示例（如 `<SOURCE_TERM>`、`<TARGET_TERM>`），避免任何具体词条作为默认模板。

---

### C1. `record_confirmed_terms.entries` 最小强化方案

目标：只强化最脆弱点，不做协议重构。

1. 明确“单工具专用强化”，不能仅依赖通用 schema 说明。  
理由：当前 provider 层是 generic union（见 A1-1）。

2. 对 `record_confirmed_terms.entries` 给出强正反例（在 system prompt / investigation prompt / repair prompt 三处同源文案）。

正确示例（仅此形状）：
```json
{
  "toolName": "record_confirmed_terms",
  "arguments": {
    "entries": {
      "<SOURCE_TERM>": "<TARGET_TERM>"
    }
  },
  "reason": "..."
}
```

错误示例（显式禁止）：
```json
{"entries":{"sourceTerm":"...","targetTerm":"..."}}
{"entries":[{"sourceTerm":"...","targetTerm":"..."}]}
{"entries":["A=B"]}
```

3. 保持当前业务门槛不变：仅 map 形状通过后，仍需满足 executor 写表依据约束。  
证据：`src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java:306-321`，`docs/superpowers/plans/2026-04-20-review-agent-action-id-hardening-plan.md:45-52`

---

### D1. repair 提示策略建议（仅修格式，不改语义）

1. 继续由上层 provider 发起 repair，不下沉到 client。  
证据：`docs/superpowers/plans/2026-04-20-review-agent-action-id-hardening-plan.md:83-84,206-210`

2. 对 `validationError=invalid_argument:entries` 增加专用 repair 子模板，明确：
- 只改 `arguments.entries` 的 JSON 形状；
- 不改 `toolName` 的业务意图；
- 不重写 `reason` 的语义结论；
- 不把失败“修复”为换工具。

3. 保留现有总原则“只修结构化输出”，并把 `entries` 三类典型错形状列成固定反例。  
证据：`src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:93-114,137-151`

---

### E1. 最小实施建议（小收敛，不扩散）

现在就该做（仅小修）：
1. `InvestigationPromptBuilder`：去掉 `Le Condé` 固定示例，改占位。
2. `ToolArgumentSchema` / `ReviewToolRegistry`：`entries` 示例词条占位化；补统一正反例短句。
3. `PromptBackedNextStepDecisionProvider`：增加 `invalid_argument:entries` 专用 repair 文案（只修格式）。
4. `OpenAiCompatible...Client`：把 `entries` 的正反例文本嵌入 `entriesDescription`，提升模型可见约束强度。

后置项（本阶段不做）：
1. 不改 provider JSON schema 主结构（不上 `oneOf` / discriminator）。
2. 不做 tool protocol 重写。
3. 不做 repair 下沉。

---

## 第二部分：下一阶段工具调用协议重构评审稿（第一部分验证后）

### A2. 当前协议的长期问题抽象

1. 当前主协议是 `toolName + arguments(Map) + reason` 的通用决策对象，不是强工具协议。  
证据：`src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDecision.java:5-8`

2. provider 侧仍为 union arguments，导致参数约束强度不足、错误暴露后置。  
证据：`src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:42-43,282-299`

3. 语义授权与格式约束天然分离，继续堆 repair 无法治本。

---

### B2. 根因拆解

1. Schema 根因：union + 宽对象导致形状漂移高发。  
2. 职责边界根因：provider 与 client 都参与格式清洗链路，语义责任边界容易模糊。  
证据：`src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:46-66`，`src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:209-233`
3. Tool protocol 根因：缺少 `tool_use/tool_result` 对偶主语义与关联 ID。

---

### C2. 与 claw-code 的对照

claw-code 的稳定机制：
1. 模型输出原生 `ToolUse` 事件，不是自由拼业务 JSON。  
证据：`E:/learnAgent/cc/claw-code/rust/crates/runtime/src/conversation.rs:23-31`

2. runtime 从 `ToolUse` 提取调用，执行后写入 `ToolResult`，并用 `tool_use_id` 关联。  
证据：`E:/learnAgent/cc/claw-code/rust/crates/runtime/src/conversation.rs:200-212,218-270`，`E:/learnAgent/cc/claw-code/rust/crates/runtime/src/session.rs:18-33`

3. 会话消息持久化与回放是结构化的。  
证据：`E:/learnAgent/cc/claw-code/rust/crates/runtime/src/session.rs:42-45,89-104,251-313`

4. 压缩时仍保留 tool 语义。  
证据：`E:/learnAgent/cc/claw-code/rust/crates/runtime/src/compact.rs:200-214`

5. 工具输入按工具名分发到具体输入类型反序列化，且多数 schema `additionalProperties:false`。  
证据：`E:/learnAgent/cc/claw-code/rust/crates/tools/src/lib.rs:65-76,383-412`

可借鉴：对偶消息、per-tool 强类型输入、压缩保语义。  
不宜直接搬：整套通用 runtime/tool 平台能力（超 QuillLoom 边界）。

---

### D2. 重构选项（至少 3 个）

选项 1：继续小收敛增强（definition/prompt/validator）  
- 收益：风险最低。  
- 局限：仍是后置纠错，协议根因不解。

选项 2：内部协议增强（保留外部 `ReviewToolDecision`，内部引入 `tool_use/tool_result(+id)` 结构）  
- 收益：不推翻现链路，先提升协议语义稳定性。  
- 风险：session/transcript/compact 要联动。

选项 3：高风险工具优先 DTO 化（executor 输入从 `Map` 迁到 per-tool DTO）  
- 收益：参数错误前移、执行边界更硬。  
- 风险：改造成本中等，需要迁移顺序。

选项 4（不推荐当前做）：直接切 provider 原生完整 tool protocol  
- 收益：理论最强。  
- 风险：迁移面过大，当前阶段不可控。

---

### E2. 主推荐方案

推荐：**选项 2 + 选项 3 分阶段组合**（先内部结构化，再高风险 DTO 化）。

理由：
1. 比纯小修更治本（缓解协议根因）。  
2. 比“一步到位重写协议”风险小。  
3. 不触发架构红线。  
4. 与“repair 不下沉”一致。

---

### F2. 分阶段迁移路线图

1. 阶段 0（前置门槛）  
- 第一部分落地并验证：`invalid_argument:entries` 显著下降；真实运行失败可被测试口径稳定看见。

2. 阶段 1（内部协议化）  
- 在 runtime session 内引入 `tool_use/tool_result` 结构记录（先覆盖高频高风险工具）。

3. 阶段 2（DTO 化）  
- `record_confirmed_terms`、`complete_working_set` 等高风险工具先 DTO 化；兼容层保留。

4. 阶段 3（扩面与压缩）  
- compact/transcript 策略升级为“保留工具语义摘要”，再扩大到更多工具。

5. 阶段 4（可选）  
- 稳定后再评估是否对接 provider 原生完整 tool protocol。

---

### G2. Reviewer 最该盯的风险

1. 是否把语义授权下沉到 client/repair（不允许）。  
2. 是否把阶段 2/3 误做成一次性大重构。  
3. tool memory 扩面是否超 token/compact 预算。  
4. DTO 化是否过度扩散到低价值工具。  
5. “测试绿但运行挂”是否仍未被纳入稳定验证口径。

---

### H2. 明确划线：小修 vs 协议级重构

属于小修边界：
1. prompt/example/repair 文案统一与污染清理。
2. definition/schema description/validator 对齐。
3. `entries` 专用格式修复提示。

属于协议级重构：
1. 引入 `tool_use/tool_result(+tool_use_id)` 作为主语义记录。
2. executor 输入从 `Map<String,Object>` 迁移到 per-tool DTO。
3. 评估并可能切换到 provider 原生完整 tool protocol。

---

## 结论

先做第一部分最小收敛，目标是马上降低 `invalid_argument:entries` 炸穿概率；  
第二部分作为下一阶段评审，不立即实施，不跨越当前架构边界。
