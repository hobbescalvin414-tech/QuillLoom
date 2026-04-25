# Review Agent `record_confirmed_terms.entries={}` 执行闭环兜底设计

## 结论

1. 当前真正导致整项目炸穿的层是 runtime 层，不是 executor 层。
2. `entries={}` 最早在 client 层被识别为 `invalid_argument:entries`，然后被包装为 `LlmStructuredOutputException`。
3. provider 层会做 structured-output repair，但 repair 用尽后仍继续上抛该异常。
4. `AutonomousProjectReviewAgent` 当前把这类异常直接升级为项目级 `LLM_CALL_FAILED`，因此单个 chunk 的结构化失败会终止整个项目。
5. 这轮最小兜底的主推荐落点是 runtime 层：把“next-step structured output exhausted after repair”降级为当前 focus 的可诊断局部失败，并继续项目后续 chunk，而不是终止整个项目。
6. validator 层只需要补更明确的错误分类辅助诊断；不建议把主兜底放在 client 或 validator。

## 证据

### 1. LLM 原始输出层
- 真实 prompt dump 已记录最终 rawOutput 仍是：
  - `{"toolName":"record_confirmed_terms","arguments":{"entries":{}},...}`
- 文件：`logs/review-agent-prompts/20260421-091945-313-313582800-book-draft-20260419151435-chunk-7-structured_output_repair-attempt-5-LlmStructuredOutputException.log`
- 含义：模型在 reason 里解释得很长，但最终 JSON 仍把 `arguments.entries` 留空对象，说明失败发生在“最终可执行输出”上，而不是 reasoning 不存在。

### 2. client 结构化解析 / 校验层
- [`src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:101`](src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:101)
  - `generateNextToolDecision(...)` 在 `invoke(...)` 之后再次调用 `contractValidator.validate(...)`。
- [`src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:103`](src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:103)
- [`src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:105`](src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:105)
  - 只要 validator 返回错误，就直接抛 `LlmStructuredOutputException("Review agent invalid structured tool decision: ...")`。
- [`src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java:34`](src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java:34)
- [`src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java:39`](src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java:39)
- [`src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java:40`](src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java:40)
  - `entries={}` 会被统一归类为 `invalid_argument:entries`。
- [`src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java:552`](src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java:552)
- [`src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java:569`](src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java:569)
  - 现有测试已经锁定：client 收到 `entries:{}` 会抛 `invalid_argument:entries`，并带 rawOutput。
- 含义：client 当前把“工具参数非法”视为“结构化输出失败”，这会强制把错误走异常链路而不是普通本地拒绝链路。

### 3. provider repair 层
- [`src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:81`](src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:81)
- [`src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:85`](src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:85)
- [`src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:86`](src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:86)
  - provider 捕获 `LlmStructuredOutputException` 后，会先尝试 repair。
- [`src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:102`](src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:102)
- [`src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:174`](src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:174)
- [`src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:179`](src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:179)
  - `invalid structured tool decision` 被视为可 repair。
- [`src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:263`](src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:263)
- [`src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:270`](src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:270)
- [`src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:299`](src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:299)
  - 现在已经有 `invalid_argument:entries` 专项 repair 指令。
- [`src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java:180`](src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java:180)
- [`src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java:201`](src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java:201)
  - 现有测试只证明 repair prompt 已增强，不证明“repair 用尽后不会炸穿项目”。
- 含义：provider 已经在尽力修，但它的最终行为仍是“失败就继续抛异常”，没有闭环兜底出口。

### 4. agent runtime stop / fail 层
- [`src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java:173`](src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java:173)
- [`src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java:175`](src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java:175)
- [`src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java:177`](src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java:177)
- [`src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java:180`](src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java:180)
  - 只要 `nextStepDecisionProvider.decide(...)` 抛 `LlmStructuredOutputException`，runtime 就立刻 `failLlmCall(...)`，并返回项目失败。
- [`src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java:312`](src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java:312)
- [`src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java:351`](src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java:351)
  - 当前测试明确把这种行为当作预期：structured output invalid after repair -> `LLM_CALL_FAILED`。
- 含义：真正的“炸穿点”在 runtime 终止策略，而不是前面哪一层先报错。

### 5. executor 层为何不是炸点
- [`src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java:302`](src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java:302)
- [`src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java:304`](src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java:304)
- [`src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java:323`](src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java:323)
  - executor 只有在拿到一个已经成形的 `ReviewToolCall` 后才执行，并且本身会把业务拒绝收束为 `ReviewToolExecutionResult.rejected(...)`。
- 含义：`entries={}` 还没进 executor 就已经在 client/provider 阶段被异常打断，所以 executor 不是这轮闭环兜底的主落点。

## 方案选项

### 方案 A：client 层把 `invalid_argument:entries` 改造成“本地可恢复失败对象”

做法：
1. client 不再把 validator 失败统一抛 `LlmStructuredOutputException`。
2. 改为返回一个特殊 decision / 结果包装，交给 provider 或 runtime 继续决策。

优点：
1. 最早收口。
2. 能把“JSON 解析失败”和“工具契约失败”彻底分层。

缺点：
1. 需要改 `ReviewAgentStructuredGenerationPort` 契约或引入新包装类型。
2. 明显超出这轮“不改协议”的边界。
3. 影响面比 runtime 层大。

结论：不推荐作为这轮最小方案。

### 方案 B：provider 层把 repeated `invalid_argument:entries` 转成非致命分支

做法：
1. provider 在 repair 用尽后，不再抛 `LlmStructuredOutputException`。
2. 对 `invalid_argument:entries` 返回一个受控 decision，例如 `request_human_review` 或某个固定 fallback decision。

优点：
1. 变更范围较小。
2. 离 repair 逻辑最近。

缺点：
1. 容易变成“偷偷替模型改决策”。
2. provider 只负责拿 decision，不应该伪造业务决策。
3. 如果强转成 `request_human_review`，会把本地参数错误包装成业务语义/人工语义，诊断语义变脏。
4. 会掩盖“模型连续 6 次输出非法工具参数”的真实故障形态。

结论：次优，可做备选，但不建议主用。

### 方案 C：runtime 层把单 focus 的 repeated structured-output failure 降级为局部失败并继续项目

做法：
1. 保持 client 和 provider 的现有职责不变。
2. 让 `AutonomousProjectReviewAgent` 在捕获 `LlmStructuredOutputException` 时，不再直接 `failLlmCall(...)` 全项目失败。
3. 仅对“next-step decision repair 用尽后的结构化失败”走受控降级：
   - 结束当前 focus
   - 记录 chunk 级失败诊断
   - 将该 chunk 产出为失败 outcome / backlog / process trail
   - 继续选择下一个 pending chunk
4. 保留原始异常消息、rawOutput、chunkId、attempt 信息，确保可诊断。

优点：
1. 完全命中这轮目标：“即使 entries:{}，也不能炸穿整条链路”。
2. 不改协议，不新增工具，不碰 executor 主职责。
3. 不吞错，原始错误仍完整保留。
4. 局部失败语义与“单 chunk 出错”一致，最符合执行闭环兜底。

缺点：
1. 需要设计一个 chunk 级失败状态落点。
2. 需要修改当前把任何 `LlmStructuredOutputException` 都视为项目级 fatal 的测试和 runtime 行为。

结论：主推荐方案。

### 方案 D：validator 层只增加更具体的错误分类

做法：
1. 在 `ReviewToolDecisionContractValidator` 中把 `invalid_argument:entries` 细分为如 `invalid_argument:entries_empty_map`。
2. 供 provider/runtime 定向处理。

优点：
1. 诊断更清楚。
2. 变更很小。

缺点：
1. 单独做不能阻止炸穿。
2. 只是辅助，不是兜底本身。

结论：可作为方案 C 的配套增强，不应单独承担目标。

## 主推荐方案

### 推荐落点
- 主补点：`AutonomousProjectReviewAgent`
- 辅助补点：`ReviewToolDecisionContractValidator`

### 定义“不会炸穿链路”

`record_confirmed_terms + entries:{}` 连续出现并耗尽 repair 后：
1. 不再把整个项目状态置为 `FAILED / LLM_CALL_FAILED`。
2. 当前 focus chunk 被标记为“局部失败且可诊断”。
3. 失败信息至少保留：`chunkId`、异常类型、validator 错误码、rawOutput、发生阶段（next-step decision / structured_output_repair exhausted）。
4. 项目 runtime 继续推进到下一个 pending chunk。
5. 最终项目状态应为：
   - 若其余 chunk 均成功，则项目为 `COMPLETED_WITH_FAILURES` 风格的受控完成，或沿用现有完成态但附带失败 backlog。
   - 若当前架构没有该完成态，则最小实现先保证“不立即 FAILED”，并把失败 chunk 挂入 backlog / process summary / trail，最后由现有汇总逻辑显式暴露。
6. 不自动转成 `request_human_review`，因为这是本地执行闭环错误，不是必须人工判断的语义歧义。

### 为什么这不算吞错 / 静默 fallback
1. 原始 `LlmStructuredOutputException` 不消失，只改变其 stop scope：从“全项目 fatal”收缩为“单 focus fatal”。
2. rawOutput 和错误码继续入诊断。
3. 当前 chunk 不会被当作已完成，也不会被悄悄放过。
4. runtime process trail / summary 仍会明确告诉操作者：某 chunk 因 next-step structured output exhausted 而失败。

### 最小实现切面

1. 在 [`src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`](src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java) 为 `LlmStructuredOutputException` 增加“单 focus 降级”分支，而不是直接 `failLlmCall(...)`。
2. 复用现有 chunk outcome / backlog / process trail 承载失败，而不是引入新工具或新协议。
3. 在 [`src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java`](src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java) 可选增加更细错误码，例如把空对象场景从 `invalid_argument:entries` 细化为 `invalid_argument:entries_empty_map`，但对外仍保持 `invalid_argument:entries` 前缀兼容 repair 文案匹配。
4. 不改 [`src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`](src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java) 的 port 契约，不把 provider 变成业务决策伪造器。

## 测试补点

### 必补 1：runtime 不再整项目 `LLM_CALL_FAILED`
- 修改或新增 [`src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`](src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java)
- 场景：某个 chunk 的 next-step decision 连续抛 `invalid_argument:entries`，后续仍有其他 pending chunk。
- 断言：
  1. 项目不再 `stopReason=LLM_CALL_FAILED`
  2. 当前 chunk 被记录为失败 / 未完成
  3. 后续 chunk 仍继续执行

### 必补 2：诊断信息不能丢
- 断言 process trail / backlog / chunk outcome 中仍含：
  1. `invalid_argument:entries`
  2. `record_confirmed_terms`
  3. `rawOutput={"toolName":"record_confirmed_terms","arguments":{"entries":{}}...`
  4. `chunk-7` 或对应 anchorChunkId

### 必补 3：provider 现有 repair 行为继续保留
- 保留 [`src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`](src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java) 中 entries repair 提示增强测试。
- 新增一条：repair 用尽后，provider 仍抛异常，但 runtime 已改为局部失败，不再项目失败。

### 必补 4：client 诊断原样保留
- 在 [`src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java`](src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java) 继续锁住：`entries:{}` 仍产生 `invalid_argument:entries + rawOutput`。
- 含义：runtime 降级不等于 client 放松校验。

### 可选补 5：validator 更细分错误码
- 在 [`src/test/java/io/quillloom/application/postdraft/review/ReviewToolDecisionContractValidatorTest.java`](src/test/java/io/quillloom/application/postdraft/review/ReviewToolDecisionContractValidatorTest.java) 新增空对象专门断言。
- 若暂不细分错误码，可跳过此项。

## 实施顺序建议

1. 先在 runtime 测试中把当前“整项目失败”预期改成“单 chunk 失败但项目继续”。
2. 再实现 runtime 局部失败收口。
3. 最后决定是否加 validator 细分错误码增强诊断。

## 结论摘要

1. 炸穿点在 runtime：`AutonomousProjectReviewAgent` 把 provider 抛出的 `LlmStructuredOutputException` 直接升级为全项目 `LLM_CALL_FAILED`。
2. 最小兜底应补在 runtime，而不是 client/protocol。
3. “不会炸穿链路”的定义应是：当前 focus 失败、信息保留、项目继续，不是自动人工审核，也不是静默跳过。
4. validator 细分类可加，但只能做辅助诊断，不能替代 runtime 收口。
