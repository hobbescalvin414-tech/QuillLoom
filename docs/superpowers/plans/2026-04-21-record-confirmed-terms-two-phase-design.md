# Review Agent `record_confirmed_terms` 两阶段根治方案设计

## 结论

1. 只对 `record_confirmed_terms` 做两阶段，不把所有工具改成 skill-like 两阶段。
2. 第一阶段不再要求模型直接产出 `ReviewToolDecision.arguments.entries`，而是只做两件事：
   - 判断当前是否真的要走 `record_confirmed_terms`
   - 若要走，抽取明确的 `sourceTerm -> targetTerm` pair 列表
3. 第二阶段不再让模型组 `entries` map；由本地代码把第一阶段产出的 pair DTO 组装成 `ReviewToolDecision(toolName=record_confirmed_terms, arguments.entries=...)`。
4. 普通 next-step 决策仍保留现有单阶段路径；只有当第一阶段选择 `record_confirmed_terms` 时，才进入该工具的专项第二阶段。
5. 主 prompt 可以减载 `record_confirmed_terms.entries` 的格式细节，但不能移除关于“什么时候禁止调用 record_confirmed_terms”的全局边界。

## 为什么选这个方案

### 现状根因

当前失败不是模型完全想不到 pair，而是模型在单次输出里同时承担了三件事：
1. 决定是否调用 `record_confirmed_terms`
2. 解释为什么要调用
3. 把 pair 正确编码成 `object{string:string}` 的 `arguments.entries`

真实失败日志表明：
- reason 里已经能说出稳定 pair
- 但最终 `entries` 仍是 `{}`

这说明真正不稳定的是“pair -> JSON map 参数成形”这一步，而不是“是否存在 pair 认知”。

### 为什么不做全工具两阶段

1. `read_previous_chunks` / `read_next_chunks` / `read_confirmed_terms` 这类读工具参数简单，主要难点不在 arguments 成形。
2. `complete_working_set` 当前没有实际事故，不应借题扩散。
3. 全量两阶段会引入额外调用、状态传递和测试面，超出这轮最小必要改造。

## 目标

### 目标行为

当 review-agent 需要登记稳定术语时：
1. 第一阶段先返回“是否调用 `record_confirmed_terms`”以及明确 pair 列表。
2. 如果 pair 不存在、证据不足、或只基于低优先级信号，则第一阶段不能进入记录分支。
3. 如果第一阶段给出 pair，则第二阶段由本地代码稳定生成：

```json
{
  "toolName": "record_confirmed_terms",
  "arguments": {
    "entries": {
      "Le Bouquet": "布凯咖啡馆"
    }
  },
  "reason": "..."
}
```

4. 不再让模型自己负责把 pair 塞进 `entries` map。

### 非目标

1. 不改所有工具为两阶段。
2. 不引入新工具。
3. 不把运行期临时状态塞回稳定领域契约。
4. 不退回大 orchestrator。
5. 不做面向所有工具的“是否调用”治理扩展。
6. 这轮仅允许为 `record_confirmed_terms` 增加一段进入 proposal 的窄路由；该路由只用于把“是否登记 + pair DTO 抽取”从最终 `arguments.entries` 成形中剥离，不扩展为通用调用治理重构。
7. 进入 proposal 路径必须由明确的本地条件触发；该条件只可由高权重证据命中，不可由低优先级信号单独触发。
8. 未命中上述窄路由条件时，仍走原有普通 `generateNextToolDecision` 单阶段路径。
9. proposal 路径的引入不改变其他工具的调用决策方式，也不引入通用 intent 协议、统一两阶段工具框架或全局 orchestrator。

## 方案对比

### 方案 A：继续单阶段，只强化 prompt/schema

做法：
1. 保持 `generateNextToolDecision -> ReviewToolDecision` 不变。
2. 继续加强 system prompt / investigation prompt / repair prompt。
3. 尽量把 `entries` schema 收紧。

优点：
1. 改动小。
2. 兼容现有链路。

缺点：
1. 根因不变：模型仍要一次同时完成工具选择与 map 成形。
2. 仍可能出现“reason 正确、entries 空”的失配。
3. 无法从结构上消除该问题。

结论：不作为根治方案。

### 方案 B：`record_confirmed_terms` 专项两阶段，第二阶段本地组装 map

做法：
1. 第一阶段专门输出“是否记录 + pair DTO + reason”。
2. 第二阶段由本地代码把 pair DTO 转成最终 `ReviewToolDecision`。
3. 普通工具仍走原单阶段 next-step 决策。

优点：
1. 直接消除 `entries` map 由模型成形的不稳定点。
2. 只改一个高风险工具，边界清晰。
3. 不需要新增工具，不需要改 executor 业务含义。

缺点：
1. 需要在 next-step provider / generation client 增加专项路径。
2. 需要新增 DTO、schema、测试和 prompt。

结论：主推荐方案。

### 方案 C：通用 skill-like 两阶段工具协议

做法：
1. 所有工具第一阶段只看工具作用。
2. 调用后再按工具加载规则并生成参数。

优点：
1. 理论上统一。

缺点：
1. 明显过度设计。
2. 工具决策与参数协议全局重构，成本高。
3. 对无问题的工具没有直接收益。

结论：不采用。

## 主推荐设计

### 总体思路

保留现有 review agent 主闭环：
- `AutonomousProjectReviewAgent`
- `PromptBackedNextStepDecisionProvider`
- `ReviewToolExecutor`

只在 next-step 决策内部，对 `record_confirmed_terms` 增加一条专项分叉：

1. 常规路径：
   - 模型直接生成 `ReviewToolDecision`
   - 适用于除 `record_confirmed_terms` 之外的工具

2. `record_confirmed_terms` 路径：
   - 模型先生成“record-confirmed-terms proposal”
   - proposal 中显式给出 pair DTO
   - 本地代码把 pair DTO 组装成 `ReviewToolDecision`
   - 再进入现有 validator / executor

### 两阶段拆分

#### 阶段一：决定是否进入 `record_confirmed_terms` 专项路径

新增一个专项结构化输出 DTO，例如：
- `RecordConfirmedTermsProposal`

建议字段：
- `action`: `RECORD_CONFIRMED_TERMS` 或 `NOT_APPLICABLE`
- `reason`: 顶层原因说明
- `entries`: `List<RecordConfirmedTermEntry>`

其中 `RecordConfirmedTermEntry`：
- `sourceTerm`
- `targetTerm`

规则：
1. 如果 `action=RECORD_CONFIRMED_TERMS`，则 `entries` 至少一条。
2. 如果证据不足、pair 不明确、或者不该调用，则返回 `NOT_APPLICABLE`。
3. 第一阶段不允许返回最终 `ReviewToolDecision.arguments.entries` map 形状。

#### 阶段二：本地组装最终 tool decision

当阶段一输出：
- `action=RECORD_CONFIRMED_TERMS`
- `entries=[{sourceTerm,targetTerm}, ...]`

则由本地代码生成：
- `toolName = record_confirmed_terms`
- `arguments.entries = LinkedHashMap<sourceTerm, targetTerm>`
- `reason = proposal.reason`

这里由本地代码负责：
1. 去重
2. 空白 trimming
3. 检查 source/target 非空
4. 若 DTO 内部已不合法，直接报本地结构化失败

这样可以从结构上消灭：
- `entries:{}`
- `entries` 误成 pair-object
- `entries` 误成 array
- pair 只在 reason 里，不进 arguments

## 建议落点

### 1. generation port

文件：
- `src/main/java/io/quillloom/application/postdraft/review/port/out/ReviewAgentStructuredGenerationPort.java`

新增一个专项方法，例如：
- `generateRecordConfirmedTermsProposal(String systemPrompt, String userPrompt)`

含义：
- 不修改 evaluation / revision / self-check 契约。
- 只新增 `record_confirmed_terms` 专项生成接口。

### 2. client

文件：
- `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`

新增：
1. `RECORD_CONFIRMED_TERMS_PROPOSAL_SCHEMA`
2. `generateRecordConfirmedTermsProposal(...)`
3. proposal DTO 的解析与结构校验

client 新职责：
- 把 proposal 解析成稳定 DTO
- 不再让模型直接生成最终 `entries` map

### 3. provider

文件：
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`

新增逻辑：
1. 常规 next-step 仍先走现有 `generateNextToolDecision(...)`
2. 但主 prompt/路径需要改成：
   - 模型在 next-step 不直接输出 `record_confirmed_terms` 的最终 arguments map
   - 而是输出一个“需要进入专项记录流程”的信号

这里有两种实现子方案：

#### 子方案 B1：next-step 仍可直接返回 `record_confirmed_terms`，但 provider 遇到该 toolName 时改走专项 proposal 二次生成

优点：
1. 对现有 next-step 决策侵入较小。
2. 只在命中 `record_confirmed_terms` 时加第二跳。

缺点：
1. 第一跳还是会出现 `toolName=record_confirmed_terms`。
2. 仍需要处理第一跳里 arguments 形状问题。

结论：不够干净，不推荐。

#### 子方案 B2：next-step 阶段不再允许直接产出 `record_confirmed_terms` 最终 decision，而是产出一个专项意图信号

做法：
1. 把 next-step 输出从纯 `ReviewToolDecision` 扩成“普通工具 decision + 专项 intent”的并集，或单独在 provider 中先判断。
2. 当命中专项 intent 时，再调用 proposal 生成。

优点：
1. 最干净，彻底把 `record_confirmed_terms` 从单阶段决策里剥离。
2. 根因切得最彻底。

缺点：
1. 会改 next-step 协议形状。

结论：从根治角度更正确，但超出最小协议变更。

#### 子方案 B3：provider 先本地检测“是否需要 `record_confirmed_terms` 专项判定”，再直接调用 proposal 生成，不先让模型输出 `record_confirmed_terms` toolName

做法：
1. 仍使用 investigation prompt 构建同一上下文。
2. 但 provider 增加一个窄分支：当当前 focus 命中“稳定术语登记候选”判定时，不走普通 `generateNextToolDecision`，而改走 `generateRecordConfirmedTermsProposal`。
3. proposal 成功则本地组装最终 decision；proposal 返回 `NOT_APPLICABLE` 则回退普通 next-step 决策。

进入 proposal 路径的判定规则固定为同时满足以下条件：
1. 当前 focus 的 sourceText、workingSet 已读 chunk、或 confirmedTermUpdates 里，已经出现至少一个明确 source term。
2. 当前 evidence summaries / transcript / history 里，已经出现至少一个明确 target term 候选，且该候选与同一 source term 能组成显式 `source -> target` pair。
3. 当前 evidence 不存在“仅由 decisionNotes / translatorCommentary / transitionNote / confirmedTermLookupMiss 支撑”的情形；如果只有低优先级信号，则不得进入 proposal。
4. 当前 focus 尚未存在同 source term 的已确认 project-level 命中结果；如果 evidence 已有 `confirmedTerm=A->B`，则不进入 proposal，而继续普通 next-step 决策。
5. 本地路由只负责判断“是否值得进入 pair 提取专项路径”，不负责判定该工具最终是否应执行成功；proposal 仍需由模型给出 `RECORD_CONFIRMED_TERMS` 或 `NOT_APPLICABLE`。
6. 上述判定规则必须在实施时保持文档化与可测试，至少明确允许信号、禁止信号、以及未命中时继续走普通 next-step 的回退路径。

“高权重证据”在这轮的最小判定标准固定为：
1. 允许作为 source term 命中来源的信号只有：
   - 当前 anchor chunk 的 `sourceText`
   - 当前 workingSet 已读 chunk 的 `sourceText`
   - draft 阶段已经产出的 `confirmedTermUpdates`
2. 允许作为 target term 候选命中来源的信号只有：
   - 当前 anchor chunk 的 `translatedText` / `currentTranslatedText`
   - 当前 workingSet 已读 chunk 的 `translatedText`
   - `confirmedTermUpdates` 中与同一 source term 对齐的 target
3. 明确禁止单独作为 proposal 入口触发依据的信号：
   - `decisionNotes`
   - `translatorCommentary`
   - `transitionNote`
   - `confirmedTermLookupMiss`
   - 任何只出现在 reason / commentary、但没有在 sourceText 或 translatedText 落地的抽象“应该登记”描述
4. “显式 source -> target pair” 的最小成立标准是：
   - source term 能在允许的 source 证据中定位到原文字符串
   - target term 能在允许的 target 证据中定位到译文字符串
   - 两者在当前 focus / workingSet 语境下指向同一命名对象，而不是仅凭模式类推或风格推断
5. 如果 source 只存在于高权重证据、但 target 只存在于低优先级信号，则不得进入 proposal。
6. 如果 target 只是在 translatedText 中出现，但 source 在当前 focus / workingSet 的高权重证据中不可定位，也不得进入 proposal。

明确不进入 proposal 的情形：
1. 当前 evidence 中没有显式 pair，只存在“应该登记”“译名看起来稳定”之类抽象描述。
2. 当前 evidence 只有低优先级信号，或只有 lookup miss，没有 workingSet 内的高权重文本支撑。
3. 当前 focus 主要需求是继续调查、评估、修订或完结，与术语登记无直接关系。

优点：
1. 不改 `ReviewToolDecision` 契约。
2. 不需要把所有 next-step 输出协议重做。
3. 对 `record_confirmed_terms` 的分离足够彻底。

缺点：
1. provider 需要增加一段专项路由判断。
2. 本地路由规则需要保持窄且可审计，避免滑向调用治理重构。

结论：主推荐子方案。

### B3 的边界收口

1. B3 新增的是“进入 proposal 的窄路由”，不是“决定 record_confirmed_terms 是否该被调用”的通用治理器。
2. 该路由只做专项入口判定：
   - 命中则进入 proposal pair 提取
   - 不命中则继续原普通 next-step 决策
3. 真正的调用结论仍由 proposal 的 `action` 决定，executor 仍只消费最终 `ReviewToolDecision`。
4. 因此这轮虽然不讨论全局调用治理，但允许为 `record_confirmed_terms` 新增一段局部、可枚举、可测试的前置入口判定。

### 4. registry / validator

文件：
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java`

处理原则：
1. 保留现有最终 `record_confirmed_terms.entries` 契约不变。
2. validator 继续校验最终 `ReviewToolDecision`。
3. proposal DTO 的合法性由专项 schema + proposal validator 负责。

### 5. executor

文件：
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`

不改职责。

含义：
- executor 仍然只消费最终合法的 `ReviewToolDecision(record_confirmed_terms, arguments.entries=...)`
- 不感知两阶段内部细节

这符合“不把 C0 / 装配层 / D 职责揉成大 orchestrator”的约束。

## Prompt 设计

### 主 prompt 该保留什么

主 prompt 继续保留：
1. `record_confirmed_terms` 的使用边界
2. 低优先级信号不能单独触发它
3. 没有显式 pair 不能调用它

原因：
- 这些是全局路由护栏，不能因为两阶段就移除。

### 主 prompt 该减少什么

可以减少：
1. `entries` map 的详细格式教学
2. 各种反例形状细节
3. “candidate pairs must be in arguments.entries” 这种面向最终 JSON 组装的细规则

因为这些应下沉到 proposal prompt 或本地组装逻辑。

### proposal prompt 应承担什么

proposal prompt 专门要求模型回答：
1. 当前是否满足 `record_confirmed_terms` 的调用条件
2. 若满足，明确列出 `sourceTerm` / `targetTerm` 列表
3. 若不满足，返回 `NOT_APPLICABLE`

proposal prompt 不要求模型输出最终 tool arguments map。

## 数据结构建议

### 新增 DTO

建议新增：
- `RecordConfirmedTermsProposal`
- `RecordConfirmedTermEntry`

示例：

```json
{
  "action": "RECORD_CONFIRMED_TERMS",
  "reason": "当前 workingSet 已建立 Le Bouquet -> 布凯咖啡馆 的稳定对",
  "entries": [
    {
      "sourceTerm": "Le Bouquet",
      "targetTerm": "布凯咖啡馆"
    }
  ]
}
```

非适用示例：

```json
{
  "action": "NOT_APPLICABLE",
  "reason": "当前没有足够高权重证据支持登记稳定术语",
  "entries": []
}
```

## 失败处理

### proposal 阶段失败

如果 proposal 阶段：
1. JSON 不合法
2. pair DTO 不合法
3. action 与 entries 自相矛盾

则：
- provider 不回退普通 next-step 决策，直接将该次 proposal 视为当前 focus 的专项结构化失败
- 保留 prompt dump 和 rawOutput
- 不吞错
- 后续由 runtime containment 把该失败收束为“当前 focus 局部失败并继续项目”，而不是重新尝试把同一证据集送回普通 next-step

### 本地组装失败

如果 proposal 给出：
- 空字符串 source/target
- 重复 source 且 target 冲突

则：
- 直接本地失败
- 不进入 executor
- 诊断应明确为 `proposal -> decision assembly failure`
- provider 不回退普通 next-step 决策；该失败同样按当前 focus 的专项失败处理

### 为什么 proposal / assembly 失败不回退普通 next-step

1. 一旦 B3 已命中，说明当前 evidence 已被本地路由识别为“术语登记候选”。
2. proposal 或 assembly 失败时再回退普通 next-step，会把同一证据集重新交给单阶段决策，重新引入这轮要切掉的分叉漂移。
3. 因此这里必须定死为：
   - `NOT_APPLICABLE` 是唯一允许回退普通 next-step 的正常出口
   - proposal 结构化失败与 assembly 失败都不是回退出口，而是当前 focus 的受控失败出口

## 对现有 runtime 兜底方案的关系

两阶段是根治 `entries` 参数成形问题，不替代 runtime 收口。

原因：
1. 两阶段会显著降低 `entries:{}` 类问题，但不会让 LLM 结构化失败从世界上消失。
2. runtime 对单 focus 结构化失败的局部收口，仍然是必要安全阀。

因此这里把交付前提定死为硬约束：
1. `record_confirmed_terms` 两阶段方案不得独立上线。
2. 本方案必须与 runtime containment 同轮落地，才满足“`entries:{}` 不再炸穿项目”的目标。
3. 如果 runtime containment 未落地，则 proposal 失败 / assembly 失败 / 新型结构化失败仍可能被升级为项目级 `LLM_CALL_FAILED`。

实施顺序建议仍然是：
1. 先补 runtime 不炸穿兜底
2. 再落 `record_confirmed_terms` 两阶段根治

## 测试策略

### 必补 1：proposal client 测试

文件：
- `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java`

新增覆盖：
1. 合法 proposal 可解析
2. `RECORD_CONFIRMED_TERMS + empty entries` 被拒绝
3. `NOT_APPLICABLE + non-empty entries` 被拒绝
4. 缺少 `sourceTerm` / `targetTerm` 被拒绝

### 必补 2：provider 两阶段装配测试

文件：
- `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`

新增覆盖：
1. proposal 返回单 pair -> provider 组装出最终 `ReviewToolDecision.arguments.entries`
2. proposal 返回多个 pair -> provider 组装保持顺序
3. proposal 返回 `NOT_APPLICABLE` -> provider 回退普通 next-step 决策
4. proposal 结构化失败 -> 不回退普通 next-step，而进入当前 focus 失败出口
5. 本地 assembly failure -> 不回退普通 next-step，而进入当前 focus 失败出口

### 必补 3：回归测试

1. 保留现有 `entries:{}` client/validator 测试，确保最终 decision 契约不变。
2. 新增专项回归：同样 evidence 下，不再需要模型直接输出 `entries` map，也不会出现 `entries:{}`。

### 必补 4：prompt 测试

文件：
- `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

新增覆盖：
1. system/investigation prompt 仍保留“什么时候禁止调用 `record_confirmed_terms`”
2. 主 prompt 减少最终 map 细节依赖
3. 新的 proposal prompt 明确要求输出 pair DTO 而非 final arguments map

## 文件影响面建议

### 需要修改
- `src/main/java/io/quillloom/application/postdraft/review/port/out/ReviewAgentStructuredGenerationPort.java`
- `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`
- `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`

### 需要新增
- `src/main/java/io/quillloom/application/postdraft/review/model/RecordConfirmedTermsProposal.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/RecordConfirmedTermEntry.java`
- 视实现而定：专项 proposal prompt builder / validator

### 保持不动或尽量不动
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- 其余工具的 executor / evaluator / revision 链路

## 推荐实施顺序

1. 先定义 proposal DTO 和 schema。
2. 在 client 中增加 proposal 结构化生成能力。
3. 在 provider 中接入 `record_confirmed_terms` 专项两阶段分支。
4. 调整主 prompt，只保留全局边界，减少最终 map 细节教学。
5. 增加 proposal prompt。
6. 补 provider/client/prompt 测试。
7. 与 runtime 安全阀方案一起验证，不允许两阶段失败重新炸穿全项目。

## 关键取舍

1. 这不是“工具全量 skill 化”，只是 `record_confirmed_terms` 的专项参数成形拆分。
2. 这不是大 orchestrator，因为 executor 不感知两阶段，runtime 主循环不改成统一多协议调度器。
3. 这不是吞错，因为 proposal 失败和 assembly 失败都保留诊断，并且固定走当前 focus 的受控失败出口。
4. 这也不是 prompt-only 修补，因为真正的 map 成形被下放给本地代码，根因被结构化切掉。

## 建议

审核重点建议看这四点：
1. 只改 `record_confirmed_terms`，是否满足“只改同意范围”
2. provider 里的专项分支是否还能保持高内聚，不演变为通用 orchestrator
3. proposal 返回 `NOT_APPLICABLE` 时，回退普通 next-step 的路径是否清晰
4. runtime 安全阀是否需要与此方案绑在同一实现轮次
