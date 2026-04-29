# review-agent `record_confirmed_terms` 两阶段失效根因诊断与修改方案

## 1. 范围限定

本次任务只修复：

- `record_confirmed_terms` 的两阶段切换顺序
- next-step / proposal / validator / repair 的职责边界
- `invalid_argument:entries` 导致的 next-step 卡死

本次任务**不改变**以下既有业务语义：

1. 如果当前 evidence 不足，agent 就不应该调用 `record_confirmed_terms`
2. `record_confirmed_terms` 的业务 basis、guardrail 语义、是否允许记录，维持现有口径
3. 本次不扩张“什么 evidence 算足够”

换句话说，这次不是改“何时应该调用这个工具”，而是改“只要 agent 已经决定调用这个工具，系统就必须顺利落到正确的两阶段执行链路，而不是死在 first stage 的 `entries` 校验上”。

---

## 2. 背景

当前 `review-agent` 在 `record_confirmed_terms` 路径上出现严重卡死：

- next-step 阶段反复尝试调用 `record_confirmed_terms`
- 常见失败为 `invalid_argument:entries`
- 日志反复出现新的 `generateNextToolDecision attempt=1`
- 中间伴随 `repair_triggered ... structured_output_repair ... invalid_argument:entries`

这说明系统没有稳定落到“可执行工具调用”，而是在 next-step 的 structured decision repair loop 中反复兜圈。

本文档只讨论：

- 当前真实执行顺序
- 两阶段设计是否真正成立
- 主根因是什么
- 修改后应该采用什么阶段边界
- 需要补哪些回归测试

本文档不包含补丁实现。

---

## 3. 结论先行

结论非常明确：

1. 当前实现里的 `record_confirmed_terms` “两阶段”并不是真的两阶段。
2. 程序仍然要求 next-step 先产出一个已经满足最终 contract 的 `record_confirmed_terms(arguments.entries=...)`。
3. proposal path 挂载过晚，只有 next-step 已经生成并通过 validator 的 `record_confirmed_terms` 决策之后，proposal 才会触发。
4. 因此，`entries` 责任被错误地放在了第一阶段，而不是 proposal 阶段。
5. `invalid_argument:entries` 循环的主根因不是“模型不会填 entries”，而是“阶段边界放错了”。

---

## 4. 当前真实控制流

以下是代码真实执行顺序，不按设计意图脑补。

### 4.1 Agent 主循环

入口在：

- `AutonomousProjectReviewAgent.run(...)`

当前 focus round 中的关键路径是：

1. `nextStepDecisionProvider.decide(current, focusSession)`
2. provider 内部完成 next-step / repair / proposal / proposal repair
3. 只有 provider 返回最终 `ReviewToolDecision` 后
4. agent 才调用 `toolExecutor.execute(current, decision)`

关键代码位置：

- `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java:195-230`

### 4.2 Next-step 阶段

入口在：

- `PromptBackedNextStepDecisionProvider.executeNextStepStage(...)`

真实顺序：

1. 调用 `generationPort.generateNextToolDecision(systemPrompt, userPrompt)`
2. 若 structured output 出错，进入 next-step `structured_output_repair`
3. 若返回了 `ReviewToolDecision`，再跑 `contractValidator.validate(decision, toolRegistry)`
4. 若 validator 失败，进入 next-step `decision_repair`
5. 只有 validator 通过后，才看 `toolName` 是否等于 `record_confirmed_terms`
6. 若是该工具，再去收集 `stablePairSignals`
7. 若 `stablePairSignals` 非空，才切入 proposal path

关键代码位置：

- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:133-249`

### 4.3 Structured generation client 的提前校验

`generateNextToolDecision(...)` 内部并不是“只负责生成 JSON”。

它还会：

1. 用 `investigationSchema()` 约束 next-step 输出结构
2. 反序列化成 `ReviewToolDecision`
3. 立刻调用 `contractValidator.validate(decision, toolRegistry)`
4. 若失败，直接抛 `LlmStructuredOutputException("invalid structured tool decision: ...")`

关键代码位置：

- `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:122-129`

这意味着：

- `contract validation` 实际上在 provider 内外各做了一次
- 对 `record_confirmed_terms.entries` 的要求，在 proposal 之前已经生效

### 4.4 Proposal 阶段

proposal path 的触发条件不是“模型决定要走 `record_confirmed_terms` 路由”。

真实触发条件是：

1. next-step 已经返回 `toolName=record_confirmed_terms`
2. 该 decision 已经通过 next-step contract validator
3. provider 本地又从 session 中收集出 `stablePairSignals`

满足后才进入：

- `generateRecordConfirmedTermsProposal(systemPrompt, userPrompt)`

proposal 返回 DTO：

- `action`
- `reason`
- `entries: [{"sourceTerm":"...","targetTerm":"..."}]`

随后 provider 才把 proposal 组装回最终工具决策：

- `ReviewToolDecision("record_confirmed_terms", {"entries": {...}}, reason)`

关键代码位置：

- proposal 触发：`PromptBackedNextStepDecisionProvider.java:224-249`
- proposal 执行：`PromptBackedNextStepDecisionProvider.java:253-303`
- proposal 组装：`PromptBackedNextStepDecisionProvider.java:619-645`

### 4.5 Executor 阶段

executor 不负责 next-step 决策修复。

它只接受一个已经成型的最终 `ReviewToolDecision`，然后：

1. guardrail 校验
2. 针对 `record_confirmed_terms` 校验 runtime basis
3. 调用 `termWriter.recordConfirmedTerms(...)`

关键代码位置：

- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java:331-364`

因此，当前卡死发生在 executor 之前。

---

## 5. 为什么说现在的“两阶段”是假的

### 5.1 next-step 仍然被要求先产出最终 arguments

`record_confirmed_terms` 在 registry 中被定义为：

- `requiredArguments = entries`
- `entries` 类型为非空 `object{string:string}`

关键代码位置：

- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java:89-101`

所以只要 next-step 选择了该工具，validator 就会立即要求：

- `entries` 必须存在
- `entries` 必须非空
- `entries` 形状必须是最终 map 形状

这已经不是“两阶段路由”。

### 5.2 validator 发生在 proposal 之前

provider 中的先后顺序是：

1. `generateNextToolDecision(...)`
2. `contractValidator.validate(...)`
3. `if (!record_confirmed_terms) return`
4. `collectStablePairSignals(...)`
5. `toProposalPrompt(...)`

所以：

- validator 在 proposal 前
- `stablePairSignals` 也在 validator 后

这意味着 proposal 没机会承担 first-class 的 `entries` 生成责任。

### 5.3 stablePairSignals 不是 proposal 的入口前提，而是合法 next-step 之后的附加条件

当前并不是：

- “next-step 只判断要不要走 `record_confirmed_terms`，再用 `stablePairSignals` 驱动 proposal”

而是：

- “next-step 先给出一个合法完整的 `record_confirmed_terms(entries=...)`，之后系统才允许再跑 proposal”

这使 proposal 退化为：

- 后置精修器
- 或后置覆盖器

而不是两阶段工具的第二阶段主入口。

### 5.4 repair 逻辑也证明第一阶段仍在承担 `entries`

当 next-step 因 `invalid_argument:entries` 失败时，系统不会跳 proposal repair。

它会继续在 next-step repair 中要求模型：

- Option A: 保持 `record_confirmed_terms` 并修好 `arguments.entries`
- Option B: 放弃该工具，换别的 next-step

关键代码位置：

- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:759-792`

这进一步说明：

- next-step repair 仍在强迫修最终 `entries`
- proposal 并不是 `entries` 的主责任阶段

---

## 6. 责任边界拆分

为了避免后续修改再次把问题混回去，必须明确几层责任。

### 6.1 next-step decision responsibility

应该负责：

- 当前 round 下一步走哪个工具
- 若是普通工具，给出该工具可执行 arguments
- 若是两阶段工具，只负责声明路由意图

不应该负责：

- 为 `record_confirmed_terms` 直接产出最终 `entries`

### 6.2 proposal responsibility

应该负责：

- 在 next-step 已决定走 `record_confirmed_terms` 后
- 专门生成 term pair proposal
- 输出 `entries[]`
- 允许 `NOT_APPLICABLE`

### 6.3 contract validation responsibility

应该负责：

- 对当前阶段自己的 contract 做校验

不应该：

- 在 next-step 阶段校验 proposal 阶段才应负责的数据形状

### 6.4 repair responsibility

应该负责：

- 修复当前阶段的失败

不应该：

- 在 next-step repair 里强迫修 proposal 阶段的 `entries`

### 6.5 executor responsibility

应该负责：

- 对最终 assembled decision 做运行期 guardrail
- 继续沿用现有“evidence 不足就不允许写入”的业务语义

不应该负责：

- 决定何时进入 proposal
- 兜底补两阶段边界错误

### 6.6 visualizer / 日志 responsibility

应该负责：

- 记录 provider 抛出的失败与 repair 事件

不应该被误读为：

- 已经进入过 proposal
- 或已经落到 executor

---

## 7. 主根因判断

主根因判断如下：

### 7.1 不是主要因为模型不会填 `entries`

模型填不稳 `entries` 确实是触发条件之一，但不是主因。

如果阶段切分正确：

- next-step 根本不应承担最终 `entries` 产出
- 模型在第一阶段填不稳 `entries` 不应该导致 next-step 卡死

### 7.2 主因是阶段边界放错

真正的主根因是：

- `record_confirmed_terms` 的最终 `entries` contract 被错误地提前到了 next-step 阶段
- proposal path 挂得太晚
- validator 和 repair 都在错误阶段拦截并修补 `entries`

### 7.3 本次修复不处理“是否该调用工具”的业务口径

本次不改：

- `stablePairSignals` 的业务语义
- `evidence sufficient / insufficient` 的业务判断口径
- executor 当前的业务 guardrail 范围

本次只修：

- 当 agent 已经决定调用 `record_confirmed_terms` 时，系统不该再因为 first-stage 的 `entries` contract 而卡死

---

## 8. 修改目标

修改后必须满足以下目标：

1. next-step 对 `record_confirmed_terms` 只承担“是否走该工具”的责任。
2. proposal 成为 `entries` 的第一责任阶段。
3. next-step 不再因缺失或非法 `entries` 卡死。
4. 只要 agent 已经决定调用 `record_confirmed_terms`，系统就应顺利进入 proposal / assemble / execute 链路。
5. 如果 evidence 不足，仍按现有业务语义处理为“不该调用这个工具”；本次不改这一层。

---

## 9. 推荐修改方案

推荐采用“真两阶段路由”方案。

### 9.1 核心策略

把 `record_confirmed_terms` 分成两个不同阶段的 contract：

#### 第一阶段：next-step route contract

当 next-step 选择 `record_confirmed_terms` 时：

- 不要求最终 `arguments.entries`
- 只要求能明确表达“进入 `record_confirmed_terms` 专用 proposal path”

可选落法有两种：

1. `toolName=record_confirmed_terms` 且 next-step 允许 `arguments={}`
2. 引入专用 route token / sentinel argument，但不建议额外加概念

推荐第 1 种：

- 语义最直接
- 改动面最小
- 不引入新的工具名或中间 DTO

#### 第二阶段：proposal contract

proposal 负责生成：

- `action`
- `reason`
- `entries[]`

随后 provider 把 proposal assemble 成最终 executor 使用的：

- `ReviewToolDecision("record_confirmed_terms", {"entries": {...}}, reason)`

### 9.2 provider 顺序调整

`PromptBackedNextStepDecisionProvider` 的顺序应改为：

1. `generateNextToolDecision(...)`
2. 对 next-step 运行“阶段感知”的 validator
3. 若 tool 不是 `record_confirmed_terms`，按普通路径返回
4. 若 tool 是 `record_confirmed_terms`：
   - 按现有业务语义判断当前路径是否允许进入 proposal
   - 若允许，则进入 proposal
   - 若不允许，则视为当前 next-step route 不成立，并通过现有 next-step repair / 重决策链路回到普通决策
5. proposal 返回后 assemble 最终 decision
6. 对 assembled final decision 做 final validation 后再返回 executor

这里故意只保留一种行为：

- evidence 不足时，不进入 `record_confirmed_terms` proposal

本次不在 provider 额外增加新的本地改路语义。

关键点：

- `entries` 的 final-shape 校验必须后移到 proposal assemble 之后
- proposal 前的 next-step validator 不能再拦 `entries`
- provider 不应因为 first-stage 缺少 `entries` 就阻断 `record_confirmed_terms`

### 9.3 validator 拆分

当前 `ReviewToolDecisionContractValidator` 是统一 validator，不区分阶段。

建议改为“阶段感知”：

- `validateNextStepDecision(...)`
- `validateExecutableToolDecision(...)`

或者保留一个类，但增加 mode：

- `ValidationMode.NEXT_STEP`
- `ValidationMode.EXECUTABLE`

其中：

- `NEXT_STEP` 模式下，`record_confirmed_terms` 不要求 final `entries`
- `EXECUTABLE` 模式下，仍要求非空 `object{string:string}`

### 9.4 generation client 不应在 next-step 阶段提前执行 final validator

`OpenAiCompatibleReviewAgentStructuredGenerationClient.generateNextToolDecision(...)`
当前会直接跑统一 validator。

这需要改成：

- next-step 只做 next-step contract 校验

否则 provider 即便改顺序，client 仍会提前抛 `invalid_argument:entries`。

这是本次修复的必改点，不可只改 provider。

### 9.5 next-step repair 提示词要去掉“修 `entries`”责任

当前 `[entries repair]` 文本要求：

- 继续使用 `record_confirmed_terms` 时必须补齐 `arguments.entries`

这与真两阶段直接冲突。

修改后应改成：

- 若当前 next-step 仍选择 `record_confirmed_terms`，只需保证 route contract 合法
- 不能再要求在 next-step repair 中补 proposal 阶段 `entries`

proposal repair 才负责：

- `entries[]`
- pair extraction
- `NOT_APPLICABLE`

### 9.6 schema / description 必须同步

必须一起改，否则会继续互相打架：

- investigation schema description
- tool registry 中 `record_confirmed_terms` 的 next-step 可用说明
- repair guidance
- proposal prompt wording

尤其要避免再出现这种表述：

- “When toolName=record_confirmed_terms, candidate pairs must be written in arguments.entries”

这句话只应属于 executable / proposal completion 阶段，不应属于 next-step route 阶段。

同时必须保持原有业务语义不变：

- evidence 不足时，agent 不该调用 `record_confirmed_terms`
- 本次不借机扩张或放松 record basis

---

## 10. 最小改动边界

本次建议只改阶段边界，不顺手扩大重构。

最小改动范围应控制在：

- `PromptBackedNextStepDecisionProvider`
- `OpenAiCompatibleReviewAgentStructuredGenerationClient`
- `ReviewToolDecisionContractValidator`
- 必要时补一个 next-step 专用 validator mode
- 对应测试

原则：

- 不改 executor 总体职责
- 不改 `record_confirmed_terms` runtime guardrail 业务语义
- 不把 C0 / 装配层 / D 职责重新揉大

---

## 11. 修改后预期行为

修复后，理想路径应是：

1. next-step 发现当前 focus 需要走 `record_confirmed_terms`
2. next-step 只输出合法路由决策
3. provider 若判断当前语义允许该工具，则立即进入 proposal path
4. proposal 专门生成 `entries[]`
5. provider assemble 成最终 `arguments.entries` map
6. final executable decision 再交给 executor

若 proposal 失败：

- 进入 proposal repair
- 或 proposal not applicable replan

而不是重新回到 next-step 去修 `entries`

若 evidence 不足：

- 仍按现有业务语义处理为“不该调用这个工具”
- 本次不改变这一点

---

## 12. 必补回归测试

### 12.1 provider 级

必须新增或改造以下测试：

1. 当 next-step 返回 `toolName=record_confirmed_terms` 且 `arguments={}` 时，应直接进入 proposal path，而不是 `invalid_argument:entries`
2. 当当前语义允许 `record_confirmed_terms` 时，应在 proposal 前触发，不应先因缺少 `entries` 被 validator 拦住
3. next-step repair 不应再注入 `[entries repair]` 这类要求补最终 `arguments.entries` 的文本
4. proposal repair 失败后应继续停留在 proposal chain，而不是回 next-step 修 `entries`
5. proposal `NOT_APPLICABLE` 后，应回 ordinary next-step replan，而不是再要求 first-stage 产出 `entries`
6. 当 evidence 不足时，provider 不应进入 `record_confirmed_terms` proposal；这里保持现有语义，不新增本地改路规则

### 12.2 validator 级

必须区分测试：

1. next-step mode 下，`record_confirmed_terms(arguments={})` 合法
2. executable mode 下，`record_confirmed_terms(arguments={})` 非法
3. executable mode 下，空 `entries`、pair object、array object、string array 仍非法

### 12.3 generation client 级

必须验证：

1. `generateNextToolDecision(...)` 不再因 route-stage 的 `record_confirmed_terms` 缺少 `entries` 而抛 `invalid_argument:entries`
2. proposal 生成接口仍严格校验 proposal DTO
3. assembled final decision 进入 executable validator 后，对错误 `entries` 仍能拒绝

### 12.4 agent 集成级

必须补一个直接复现当前事故的集成测试：

1. next-step 多次倾向 `record_confirmed_terms`
2. 当前语义允许该工具
3. 修复前会落入 `invalid_argument:entries` + next-step repair loop
4. 修复后应进入 proposal，并最终落到 proposal success / proposal repair / proposal not applicable replan 之一
5. 不能再出现“仅因 next-step 缺少 final `entries` 而卡死”的路径

---

## 13. 风险与注意事项

### 13.1 风险一：放松太多，导致普通非法 decision 混进 executor

应对方式：

- 只对 `record_confirmed_terms` 做阶段性例外
- executable final validation 仍然保留

### 13.2 风险二：在 provider 里增加过多本地改路

应对方式：

- evidence 不足时只保留一种行为：不进入 `record_confirmed_terms` proposal
- 不额外扩张 provider 本地 rule-based replan 语义

### 13.3 风险三：测试仍沿用旧语义

应对方式：

- 删除或改写那些把 `invalid_argument:entries` next-step repair 视为正确行为的测试

### 13.4 风险四：表面修了两阶段，实际 client 仍提前拦截

应对方式：

- 必须同时修改 provider 和 generation client 的 next-step validator 行为

---

## 14. 最终判断

这次问题不应再被表述成“模型老是不会填 `entries`”。

更准确的表述是：

- 当前程序没有真正实现 `record_confirmed_terms` 的两阶段工具边界
- proposal path 被挂在了 next-step final-contract 校验之后
- validator 和 repair 在错误阶段承担了 `entries` 约束
- 因此系统把本该由 proposal 承担的责任错误压回了第一阶段

本次修复目标是：

1. 不改“什么时候应该调用这个工具”的业务语义
2. 只改“只要 agent 已决定调用，就必须顺利进入正确两阶段链路”的程序语义

换句话说：

- 错在阶段边界
- 不是先错在模型
- 也不是这次要改业务 basis

---

## 15. 建议的后续动作

下一步建议先写实现计划，再按计划定向修改，不直接散改。

推荐实施顺序：

1. 先收敛 contract 设计：next-step mode vs executable mode
2. 再改 provider 顺序
3. 再改 generation client 提前校验
4. 再改 repair guidance
5. 最后补测试并跑定向回归
