# Review Agent 专项设计：`reason` 正确但 `arguments.entries={}` 持续失败

## 1. 结论（先给结论）
1. 这次问题的主因不是重试次数，也不是 Java 侧把非空 map 清空；主因更像是 **repair 约束缺少“reason 与 arguments.entries 的绑定规则”**，导致模型把正确 term pair 写进 `reason`，却不落到结构化字段。  
2. 当前链路中，`record_confirmed_terms` 的参数合法性校验是硬的，但它只校验 `entries` 形状，不校验 `reason` 与 `entries` 一致性；因此会出现“语义上知道、结构上没填”的反复失败。  
3. `arguments` 仍是 generic union 表达，`reason` 又是自由文本，这种组合会放大“信息写到 reason 而不是 arguments”的漂移。  
4. 最小闭环修正应优先落在 **provider repair 文案 + schema/description 约束文本**，而不是继续加重试次数。

### 主因层级排序（从高到低）
1. `repair prompt`：缺“reason/entries 一致性绑定”约束（最高）。  
2. `structured-output schema/description`：generic union + 自由 reason，天然鼓励信息外溢到 reason。  
3. `provider repair 组织方式`：双入口都有修复块，但都是“格式约束”，没有利用上一轮输出里的 reason/entries 失配事实。  
4. `model 输出行为`：在当前约束下出现“会解释但不填字段”的模式。  
5. `client 解析/校验层`：低概率主因；当前更像正确暴露问题，而非制造问题。

---

## 2. 证据（文件路径 + 行号 + 含义）

### 2.1 不是“重试次数不够”
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:14`  
  含义：`MAX_REPAIR_ATTEMPTS = 5`。  
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:46`  
  含义：循环条件 `attempt <= MAX_REPAIR_ATTEMPTS`，一次决策最多 6 次尝试。  
- `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java:118-140`  
  含义：已有测试验证上限打满时 `prompts().size()==6`，仍可能返回最后一次非法决策。  

### 2.2 没有证据表明“Java 把非空 entries 吃空”
- `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:101-107`  
  含义：先得到 `ReviewToolDecision`，再走 contract validator，不合法直接抛 `invalid structured tool decision`。  
- `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:186-189`  
  含义：异常中的 `rawOutput` 来自 `decision` 再序列化，不是随手拼字符串。  
- `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:250-263`  
  含义：union 清理只删“所选工具不允许的字段”；`record_confirmed_terms` 允许 `entries`，此处不会主动清空它。  
- `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java:520-546`  
  含义：`entries:{}` 会稳定触发 `invalid_argument:entries`，并回带 rawOutput 片段。  

### 2.3 当前最可疑点：缺 reason/entries 绑定
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:165-187`  
  含义：entries repair 已有二选一和反例，但没有“如果 reason 已列出 pair，则 entries 必须逐项一致”的显式绑定。  
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java:12-49`  
  含义：仅校验 toolName、required/allowed 参数和参数类型；不校验 reason 与 arguments 的一致性。  
- `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:315-323`  
  含义：`arguments` 仍是 generic union；`entries` 仅是 union 分支里的 object 字段。  
- `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:276-278`  
  含义：`reason` 是自由文本字段，当前描述鼓励“把 rationale 放 reason”，但没有限制“候选 term pair 不得只写 reason”。  
- `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java:171-173`  
  含义：现有 structured-output repair 用例里 `reason` 只是 `record term`，并未覆盖“reason 已写明确 pair 但 entries 为空”这个真实故障模式。  

### 2.4 运行现象一致
- 本次问题输入中的最新运行 rawOutput：`toolName=record_confirmed_terms`、`arguments.entries={}`，同时 `reason` 已给出可登记 pair 与规则分析。  
  含义：与上述“缺绑定约束”高度一致。

---

## 3. 方案选项（小范围、可闭环）

### 方案 A：仅加强 provider repair 绑定约束（最小改动）
**改动层**
- `PromptBackedNextStepDecisionProvider` 的 entries 专项修复文案（双入口共用块）。

**核心规则补强**
1. 新增硬约束：若 `reason` 中出现明确 `source->target` 候选，则 `arguments.entries` 必须同步包含，且不得为空。  
2. 显式禁止：`reason` 给出候选 map/pair，但 `entries={}`。  
3. 限制 `record_confirmed_terms` repair 场景的 `reason`：只允许写“登记依据简述”，不再展开候选 entries 明细（候选必须放 `arguments.entries`）。

**收益**
- 直接对准本次“知道但不填字段”的失败模式。  
- 不改协议、不改工具、不改职责边界。

**风险**
- 完全依赖提示遵从，仍可能被模型偶发违背。  

---

### 方案 B：A + schema/description 同步收紧（推荐）
**改动层**
- 方案 A 全部。  
- `OpenAiCompatibleReviewAgentStructuredGenerationClient.investigationSchemaDescription()` 与 `reason` 描述文本（仅文案约束，不改 schema 结构）。  
- 可选：`ReviewToolRegistry` 的 `record_confirmed_terms` 说明文案补一条“pair 只能写 entries”。

**核心补强**
1. 在 schema 描述中增加 tool-specific 语义约束：
   - 当 `toolName=record_confirmed_terms` 时，候选 pair 必须写在 `arguments.entries`；`reason` 不得单独承载候选 map。  
2. 保持 generic union，不引入 `oneOf/discriminator`；仅增强文本约束一致性。  

**收益**
- 同时约束“repair 提示”和“常规生成 schema 描述”，降低反复把内容写进 reason 的概率。  
- 仍在小修边界内，可快速闭环验证。  

**风险**
- 仍属于提示/描述层约束，不能像协议重构那样彻底硬化。  

---

### 方案 C：A + 最小观测（用于确认是否命中同一失配模式）
**改动层**
- Provider 失败诊断信息（不扩展到全局日志工程）。

**建议记录**
- `toolName`、`validationError`、`entriesEmpty`、`reasonContainsPairHint`（布尔）
- 记录点：provider 在进入 entries repair 时，以及最终失败抛错前。  

**收益**
- 快速确认“reason/entries 失配”是否仍为主导失败原因。  

**风险**
- 只提升可见性，不直接降低失败率。  

---

## 4. 主推荐方案
推荐 **方案 B（A + schema/description 同步收紧）**。

**为什么不是只做 A**
- A 能止血，但只在 repair 重试路径生效；首次生成路径仍可能把候选写进 reason。  

**为什么不是继续加次数**
- 已有 6 次上限，且有打满失败证据（见 2.1），继续加次数只会放大成本，不改变失配机制。  

**为什么不是改协议**
- 本轮目标是小范围闭环；无需引入 `oneOf/discriminator` 或 tool protocol 重构。

---

## 5. 测试补点（必须覆盖“reason 正确但 entries 为空”）

### 5.1 Provider repair 测试（新增）
文件：`src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`

新增用例：
- 第一次返回：
  - `toolName=record_confirmed_terms`
  - `arguments.entries={}`
  - `reason` 包含明确 pair（例如 `{"Éditeur d’art":"艺术出版人"}` 或等价 `A->B` 表达）
- 断言：repair prompt 必须出现“reason/entries 必须一致”的硬约束，并明确禁止“reason 有 pair 但 entries 为空”。

### 5.2 Client/schema 侧测试（新增）
文件：`src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java`

新增用例：
- 抓取 investigation schema 文本，断言包含：
  - `toolName=record_confirmed_terms` 时，candidate pairs 必须写 `arguments.entries`
  - `reason` 不得单独承载候选 map（或同义约束）
- 目的：证明当前 schema/description 已对“只写 reason 不写 entries”施加明确约束。

### 5.3 观测验证（若采纳方案 C）
文件：`PromptBackedNextStepDecisionProvider` 对应测试

新增断言：
- 当触发 `invalid_argument:entries` 且 reason 含 pair 提示时，诊断字段中 `entriesEmpty=true`、`reasonContainsPairHint=true`。  
- 目的：验证观测最小闭环，不引入全局日志工程。

---

## 6. 本轮边界确认
- 不做协议重构。  
- 不新增工具。  
- 不改 executor / handler 主职责。  
- 不做全局 prompt 重构。  
- 不把“继续加重试次数”作为主方案。

