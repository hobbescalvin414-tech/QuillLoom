# Review Agent 本轮收敛设计（执行施工图）

## 0. 范围与边界

本稿只覆盖两项改造，且保持小范围：
- A：`invalid_argument:entries` repair 稳定性收敛
- B：next-step decision prompt 重组

明确不做：
- 不做协议重构
- 不新增工具
- 不做 executor / validator / handler 架构重构
- 不把“关闭 decisionNotes / transitionNote / translatorCommentary 注入”当主方案

---

## 1. 主结论（可直接执行）

1. A 必须把 entries repair 升级成“二选一结构化修复合同”，并在 provider 双入口共用。  
2. B 必须按文件级清单实施：  
   - `ReviewAgentSystemPromptBuilder`：删/改/增（明确到原句）  
   - `InvestigationPromptBuilder`：Keep/Delete/Compress 三清单  
   - `OpenAiCompatible...investigationSchemaDescription()`：只删重复治理文本，不动参数合同文本

---

## 2. 证据与问题定位

## 2.1 A：entries 失败路径
- structured-output 主路径：  
  `PromptBackedNextStepDecisionProvider.java:49-55`
- decision-repair 次路径：  
  `PromptBackedNextStepDecisionProvider.java:58-66`
- client 抛错源：  
  `OpenAiCompatibleReviewAgentStructuredGenerationClient.java:103-107`
- 当前 entries repair 挂点：  
  `PromptBackedNextStepDecisionProvider.java:124`、`:156`、`:159-180`

含义：必须双入口共用同一份“严格合同”，否则生产主路径仍会漏。

## 2.2 B：prompt 失稳来源
- system prompt 存在危险宽松出口（需强制改写）：  
  `ReviewAgentSystemPromptBuilder.java:18`
- investigation prompt 同时混入规则 + 动态上下文：  
  `InvestigationPromptBuilder.java:40-48`、`:76-87`
- schema description 重复注入治理长文：  
  `OpenAiCompatibleReviewAgentStructuredGenerationClient.java:285-311`
- 但 `entries` 参数合同来自 schema description 注入，不能削弱：  
  `OpenAiCompatibleReviewAgentStructuredGenerationClient.java:315-319`

---

## 3. 文件级改动清单（执行者按此改）

## 3.1 `ReviewAgentSystemPromptBuilder.java`

### 删除（Delete）
1. 删除“问题并不尖锐，可以直接 complete_working_set”这类宽松出口句。  
定位：`ReviewAgentSystemPromptBuilder.java:18`（该长行中的该语义片段）

### 改写（Rewrite）
1. 将“可直接提交”的描述改写为条件式：  
“仅当不存在未解决高优先级问题时，才允许 complete_working_set。”
2. 将低优先级信号规则改写为权限句：  
“低优先级信号只能触发调查/evaluate_focus，不得单独支持高风险动作。”

### 新增（Add）
新增 P0 硬阻断块（置于规则前段）：
1. 已识别 confirmed term 冲突未解 -> 禁止 complete_working_set。
2. 无明确 source->target term pair -> 禁止 record_confirmed_terms。
3. 仅低优先级信号 -> 禁止 record_confirmed_terms / draft_revision / request_human_review。

## 3.2 `InvestigationPromptBuilder.java`

### 保留（Keep）
1. 当前 focus / workingSet
2. evidence summaries
3. evidence gaps
4. recent transcript
5. local rejection diagnostics

理由：这些是本轮动态会话态，不能删。

### 删除（Delete）
1. 与 system prompt 重复的长治理段（证据权限、工具治理总述、输出总规则大段）。

理由：重复注入会冲淡优先级，增加模型自我解释空间。

### 压缩（Compress）
1. 低优先级信号规则压缩为一行：  
“低优先级信号仅支持调查/evaluate_focus，不单独支持高风险动作。”
2. JSON 输出约束在 investigation 仅保留一行提醒；完整输出合同保留在 system prompt。
3. tool example 仅保留最小 JSON 示例一条，不再附加治理解释段。

## 3.3 `OpenAiCompatibleReviewAgentStructuredGenerationClient.java`

### `investigationSchemaDescription()` 只删重复治理文本

删除项（可删）：
1. `whenToUse=...`
2. `whenNotToUse=...`
3. `repeatPolicy=...`（若仅用于治理提示）

保留项（不可删）：
1. `allowedArguments=...`
2. `requiredArguments=...`
3. `argumentRequirements=...`
4. `argumentsExample=...`
5. `entries` 参数 schemaDescription 注入链路  
   位置：`OpenAiCompatibleReviewAgentStructuredGenerationClient.java:315-319`

---

## 4. 原句替换清单（必须）

## 4.1 SystemPrompt 危险原句替换

原句语义（现状）：  
“如果问题并不尖锐，可以直接 complete_working_set。”

替换为：  
“只要存在未解决高优先级问题（包括 confirmed term 冲突），禁止 complete_working_set；必须先调查或 evaluate_focus。”

## 4.2 低优先级信号原句替换

原句语义（现状偏建议）：  
“低优先级信号可参考。”

替换为权限边界句：  
“低优先级信号仅可支持调查/evaluate_focus，不得单独触发 record_confirmed_terms、draft_revision、request_human_review。”

---

## 5. A：entries repair 二选一合同（结构化）

## 5.1 命中条件
- `validationError` 或 `structuredOutputError` 含 `invalid_argument:entries`。

## 5.2 合法输出只允许两类

### 方案 A：继续 `record_confirmed_terms`
必须同时满足：
1. `toolName="record_confirmed_terms"`
2. `arguments.entries` 为非空 `object{string:string}`
3. 形状合法（禁止 `{}` / pair-object / array / `["A=B"]`）

### 方案 B：放弃 `record_confirmed_terms`
允许改选工具（限定集）：
1. `read_previous_chunks`
2. `read_next_chunks`
3. `expand_block_context`
4. `lookup_knowledge_cards`
5. `read_confirmed_terms`
6. `evaluate_focus`

且必须同时满足：
1. 新工具 `arguments` 一次性合法（满足 requiredArguments）
2. `arguments` 只包含所选工具参数
3. 返回仍是合法 JSON 决策对象

## 5.3 明确禁止的“第三种输出”
1. 解释为什么放弃了，但 tool/arguments 仍不合法
2. reason 展开 union/schema/参数冲突分析
3. 非 JSON 或 JSON 外附加说明文本

---

## 6. 测试清单（执行者必须补）

## 6.1 `PromptBackedNextStepDecisionProviderTest`

新增/补强：
1. `invalid_argument:entries` structured-output 路径：  
   断言 repair prompt 包含“二选一合同”与禁止第三种输出条款。
2. `invalid_argument:entries` decision-repair 路径：  
   断言同样命中合同（双入口一致）。
3. 非 entries 错误：  
   断言不注入 entries 专项合同。

## 6.2 真实故障回归用例（核心）

新增用例模板（provider prompt + decision 双层回归）：
1. 输入 evidence/reason 显示：已识别 confirmed term 冲突；
2. 同时出现低优先级正面信号（如 transitionNote 已实现、decisionNotes 可能误报）；
3. prompt 层期望：必须出现 P0 禁止语句  
   “仅低优先级信号 -> 禁止 record_confirmed_terms / draft_revision / request_human_review”，  
   且不存在“问题不尖锐可直接 complete_working_set”类宽松出口语句。
4. decision 层期望：该场景下不得直接产出 `complete_working_set`（即 `toolName` 不能为 `complete_working_set`）。

目的：锁住“高优先级问题不能被低优先级信号冲淡”。

## 6.3 `OpenAiCompatibleReviewAgentStructuredGenerationClientTest`

补强：
1. schema 收敛后仍包含 `entries` 硬约束关键文本（non-empty map + 反例关键词）
2. schema 中不再包含重复治理长文（whenToUse/whenNotToUse 等）

---

## 7. 不做什么（防扩散）

1. 不改协议结构
2. 不新增工具
3. 不改 executor/validator/handler 主职责
4. 不把“总开关删低优先级信号”作为主推荐

---

## 8. 验收标准（本稿是否可交执行者）

1. 执行者可直接按“文件级改动清单”改，不需二次设计。
2. 系统 prompt 的危险宽松出口已明确列为“必须删/改”。
3. InvestigationPromptBuilder 的 Keep/Delete/Compress 三清单齐全。
4. entries repair 二选一合同含“放弃后工具与参数合法性要求”。
5. 已写明真实故障回归用例，能锁住“冲突已识别却直接提交”的回归风险。
