# 2026-04-18 Review Agent 真实冒烟前置设计稿

## 1. 目标

## 1.1 实现状态

截至 2026-04-18，本设计稿对应的两项前置能力已经落地并通过定向验证：

1. Task 5：LLM 客户端层重试/退避已接入
   - 新增 `LlmTransientException` 与 `LlmStructuredOutputException`
   - `PromptBackedNextStepDecisionProvider` 只对结构化输出异常做 repair
   - `RetryingReviewAgentStructuredGenerationPort` 只对 transient 异常做重试
   - `ReviewProjectStopReason` 已补 `LLM_CALL_FAILED`
2. Task 6：scripted e2e 已落地
   - 已覆盖 `WAITING_HUMAN -> resume -> complete`
   - 已覆盖 `NO_PROGRESS -> FAILED` 且不落 session

本稿后续保留为“实现前设计 + 实现后验收基线”。

本设计稿只解决一个问题：

> 在不先做异步 REST API、不先做完整产品化入口的前提下，Review Agent 什么时候可以第一次跑“从 D 的初稿开始，agent 精修完整项目”的真实冒烟？

结论先行：

1. 当前还不能直接跑真实完整项目冒烟。
2. 真实冒烟前必须先补两块能力：
   - LLM 客户端层的受控重试/退避
   - 一条可重复、可控的 scripted e2e 路径
3. 这两块完成后，就可以用现有 `reviewProject(projectId)` + `CommandLineRunner` 跑第一次真实项目 smoke。

本稿只讨论：

1. Task 5：LLM 重试/退避
2. Task 6：scripted e2e
3. 真实项目 smoke 的准入条件

本稿不讨论：

1. 异步 REST API
2. 状态查询产品化
3. D-12 checkpoint 崩溃恢复
4. D-08 / D-09 / D-13 / D-14

---

## 2. 当前阻塞真实冒烟的两个问题

### 2.1 LLM 瞬时失败会直接中断长跑

当前 Review Agent 的结构化生成调用集中在：

- `generateNextToolDecision(...)`
- `generateEvaluationDecision(...)`
- `generateRevisionDraft(...)`
- `generateRevisionSelfCheck(...)`

这些调用最终都收敛到：

- [OpenAiCompatibleReviewAgentStructuredGenerationClient.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java)

目前没有显式的 429 / 503 / timeout 重试与诊断日志。对 130+ chunk 的完整项目来说，这不是“可能问题”，而是高概率问题。

### 2.2 还没有一条“稳定复现 WAITING_HUMAN -> resume -> complete”的项目级验证链

虽然目前已经有：

1. `WAITING_HUMAN` 完整 session 持久化
2. `resumeProject(projectId, humanReviewNote)` 恢复入口
3. Java / Spring / CLI 最小运行链

但还没有一条可重复的、与真实 LLM 解耦的 scripted e2e 去证明：

1. agent 可以在项目级运行时中途主动求助
2. session JSON 会正确落盘
3. 恢复后会继续推进而不是回退或丢状态
4. 最终完成并写回数据库

没有这条 e2e，直接跑真实项目 smoke，出了问题时很难判断是：

1. LLM 波动
2. 提示词问题
3. session 恢复 bug
4. 写回链问题

---

## 3. Task 5：LLM 重试/退避设计

## 3.1 设计目标

在不污染 agent loop、不把基础设施问题伪装成 agent 决策问题的前提下，为 Review Agent 的结构化生成调用补齐：

1. 可重试错误的自动重试
2. 可诊断的重试日志
3. 最终失败时可被状态层识别为“基础设施可重试失败”

## 3.2 边界约束

重试逻辑必须位于 LLM 客户端层，而不是 agent loop 层。

允许重试：

1. 429
2. 503
3. 网络超时

禁止重试：

1. 400
2. 401
3. 结构化输出 JSON 解析失败
4. repair retry 后仍无法修复的结构化输出
5. `NO_PROGRESS`
6. guardrail 拒绝

原因：

1. 前三类是基础设施瞬时失败，重试有意义。
2. 后三类是契约/逻辑问题，重试无意义，只会放大噪音。

## 3.3 异常分层

当前 `ReviewAgentStructuredGenerationPort` 的 4 个方法都不抛受检异常，调用方现在感知到的只有运行时异常。

这是个关键点：如果继续混用 `IllegalStateException`，重试层无法稳定区分：

1. 可重试的瞬时失败
2. 不可重试的结构化输出失败

尤其当前
[OpenAiCompatibleReviewAgentStructuredGenerationClient.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java)
里至少有两类语义完全不同的 `IllegalStateException`：

1. 空白输出
   - 倾向视为瞬时服务异常，可重试
2. JSON 解析失败 / 契约校验失败
   - 属于结构化输出失败，不可重试，应交给既有 repair 逻辑

因此建议先做异常分层，再做 retry：

### 建议新增异常类型

1. `LlmTransientException`
   - 表示可重试的瞬时失败
   - 例如：空白输出、429、503、timeout、连接失败
2. `LlmStructuredOutputException`
   - 表示不可重试的结构化输出失败
   - 例如：JSON 解析失败、根节点结构错误、tool decision 契约校验失败

推荐改法：

1. `OpenAiCompatibleReviewAgentStructuredGenerationClient.invoke()` 不再笼统抛 `IllegalStateException`
2. 空白输出改抛 `LlmTransientException`
3. JSON 解析失败、根节点不合法、arguments 非 object、contract validator 失败，统一抛 `LlmStructuredOutputException`

这样可以保证：

1. retry 只吃 `LlmTransientException`
2. repair 只吃 `LlmStructuredOutputException`
3. 二者不会互相误判

## 3.4 配置归属

重试参数属于 LLM 客户端配置，不属于 runtime 入口配置。

因此应放在：

- [ReviewAgentLlmProperties.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/ReviewAgentLlmProperties.java)

而不是：

- [ReviewAgentRuntimeProperties.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/ReviewAgentRuntimeProperties.java)

建议新增字段：

```java
private int maxRetries = 3;
private Duration retryBackoff = Duration.ofSeconds(1);
private Duration retryMaxBackoff = Duration.ofSeconds(8);
private boolean retryJitterEnabled = true;
```

## 3.5 LangChain4j 异常确认

在真正落实现之前，需要先补一个很小的定向测试或最小探针，确认 LangChain4j 在这些场景下实际抛出的异常层次：

1. 429
2. 503
3. timeout
4. 连接失败

原因：

1. 当前不能假设它直接抛 `SocketTimeoutException`
2. 也不能假设 HTTP 失败就是一个统一的 Java 标准异常
3. 如果 `instanceof` 判断基于错误假设，retry 会直接失效

因此实现顺序必须是：

1. 先确认 LangChain4j 异常层次
2. 再实现 retryable predicate

## 3.6 实现位置

首选顺序：

1. 先确认 LangChain4j 的 `OpenAiChatModel.builder().maxRetries(...)` 是否满足需求
2. 如果不能精确限定“只重试 429/503/timeout”，则在
   [OpenAiCompatibleReviewAgentStructuredGenerationClient.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java)
   外侧包一层自定义 retry

不建议把 retry 写进：

1. `PromptBackedNextStepDecisionProvider`
2. `PromptBackedStrategyEvaluationService`
3. `PromptBackedRevisionDraftProvider`
4. `LlmBackedRevisionSelfCheckService`
5. `AutonomousProjectReviewAgent`

因为这些层属于 agent 行为层，不应该感知基础设施重试。

## 3.7 重试与 repair 的边界

当前 `PromptBackedNextStepDecisionProvider.decide()` 已有自己的 repair 循环。

这是第二个关键点：

1. 结构化输出失败应该先进 repair
2. 瞬时基础设施失败才应该进 retry

因此不允许出现这种错位：

1. retry 耗尽后抛一个普通 `IllegalStateException`
2. 上层把它误识别成“结构化输出可修复”
3. 进而触发错误的 repair

推荐约束：

1. retry 耗尽后继续抛 `LlmTransientException`
2. `isRepairableStructuredOutputError(...)` 一类逻辑只匹配 `LlmStructuredOutputException`
3. 不再使用宽泛的 `IllegalStateException` 作为 repair 判定依据

这样：

1. repair 不会吞掉基础设施失败
2. retry 也不会吞掉结构化输出 bug

## 3.8 推荐实现结构

如果 LangChain4j 内置能力不够，推荐新增一个装饰器：

- `RetryingReviewAgentStructuredGenerationPort`

职责：

1. 包装 `ReviewAgentStructuredGenerationPort`
2. 统一处理 4 个结构化生成方法的 retry
3. 记录重试日志
4. 在最终失败时抛出明确的基础设施异常

结构如下：

```java
public final class RetryingReviewAgentStructuredGenerationPort implements ReviewAgentStructuredGenerationPort {

    private final ReviewAgentStructuredGenerationPort delegate;
    private final ReviewAgentLlmRetryPolicy retryPolicy;

    @Override
    public ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
        return executeWithRetry("generateNextToolDecision", () -> delegate.generateNextToolDecision(systemPrompt, userPrompt));
    }
}
```

这样不会污染 OpenAI 具体实现本身，也方便将来替换底层模型。

## 3.9 退避参数

退避策略不要只停留在“有 backoff”。

建议在设计上固定 5 个参数：

1. `maxAttempts`
2. `initialBackoffMs`
3. `maxBackoffMs`
4. `backoffMultiplier`
5. `jitterFactor`

推荐初值：

1. `maxAttempts = 3`
2. `initialBackoffMs = 1000`
3. `maxBackoffMs = 8000`
4. `backoffMultiplier = 2.0`
5. `jitterFactor = 0.2`

## 3.10 日志要求

每次 retry 必须带结构化诊断字段，至少包括：

1. `retry_attempt`
2. `retry_reason`
3. `backoff_ms`
4. `operation`
   可选值：
   - `generateNextToolDecision`
   - `generateEvaluationDecision`
   - `generateRevisionDraft`
   - `generateRevisionSelfCheck`
5. `projectId`
   如果当前层拿不到，可先不打，后续通过调用链补

目标是让日志能清楚区分：

1. “同一次生成调用被重试了 3 次”
2. “agent 自己做了 3 轮决策”

## 3.11 失败分类前置

如果 retry 最终仍失败，后续状态投影层必须能把它映射为：

- `FAILED_INFRA_RETRYABLE`

因此领域停止原因需要补一个基础设施失败项，例如：

- `LLM_CALL_FAILED`

这个 stop reason 不是现在就要产品化暴露，但必须先在领域层建出来，不然后面无法区分：

1. agent bug
2. LLM 基础设施失败

---

## 4. Task 6：scripted e2e 设计

## 4.1 设计目标

在不依赖真实 LLM 波动的前提下，构造一条稳定、可重复的项目级 e2e，覆盖：

1. `reviewProject(projectId)` 启动
2. agent 中途 `request_human_review`
3. session JSON 落盘
4. `resumeProject(projectId, humanReviewNote)` 恢复
5. agent 继续推进到项目完成
6. 修订译文写回 review package

## 4.2 为什么不能直接用真实 LLM 做 e2e

因为真实 LLM 的行为不稳定，无法保证测试一定走到：

- `WAITING_HUMAN`

而我们现在最想验证的，恰恰是：

1. 中途暂停
2. 恢复
3. 继续跑完

所以 e2e 必须用 scripted generation port 控制行为。

## 4.3 复用还是新建测试桩

当前已有多个测试内部自带 `SequenceGenerationPort`。

问题是它们大多只精确控制：

- `generateNextToolDecision(...)`

而项目级 e2e 需要更稳定地覆盖 4 类结构化生成：

1. next step decision
2. evaluation
3. revision draft
4. revision self-check

因此建议：

1. 先评估是否能抽出现有 `SequenceGenerationPort`
2. 如果抽出来后仍不足以表达完整脚本，则新增专用测试桩：
   - `src/test/java/io/quillloom/application/postdraft/review/support/ScriptedReviewAgentGenerationPort.java`

推荐直接新增，避免把现有零散测试桩继续堆大。

## 4.4 推荐脚本能力

`ScriptedReviewAgentGenerationPort` 应支持按顺序配置：

1. `ReviewToolDecision`
2. `ReviewAgentEvaluation`
3. `RevisionDraft`
4. `RevisionSelfCheckResult`

并在脚本耗尽时直接失败，而不是兜底返回默认值。

但不能只按“全局第 N 次调用”匹配。

原因：

1. Review Agent 有 4 类不同的结构化生成方法
2. 如果后续实现调整了调用顺序，仅靠全局序号会直接错位
3. 错位后的测试失败不一定说明业务错了，也可能只是脚本匹配策略脆弱

因此推荐按“方法类型 + 对应队列”匹配：

1. `toolDecisionQueue`
2. `evaluationQueue`
3. `revisionDraftQueue`
4. `selfCheckQueue`

示意：

```java
public final class ScriptedReviewAgentGenerationPort implements ReviewAgentStructuredGenerationPort {

    private final ArrayDeque<ReviewToolDecision> toolDecisionQueue;
    private final ArrayDeque<ReviewAgentEvaluation> evaluationQueue;
    private final ArrayDeque<RevisionDraft> revisionDraftQueue;
    private final ArrayDeque<RevisionSelfCheckResult> selfCheckQueue;

    @Override
    public ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
        return nextToolDecision();
    }
}
```

禁止做法：

1. 缺值时返回默认 `complete_project`
2. 缺值时默认 `passed=true`
3. 静默跳过某一类调用

否则会掩盖测试脚本不完整的问题。

## 4.5 e2e 路径脚本

scripted e2e 不应只覆盖一条路径，至少要覆盖 4 条：

### 路径 A：happy path

1. `investigate`
2. `evaluate(KEEP/LIGHT_EDIT 可直接完成)`
3. `complete_working_set`
4. `complete_project`

验证：

1. 不产生 `WAITING_HUMAN`
2. 不产生 session 文件
3. 最终写回 merged draft

### 路径 B：revision path

1. `investigate`
2. `evaluate(LIGHT_EDIT 或 DEEP_EDIT)`
3. `draft_revision`
4. `self_check(passed=true)`
5. `complete_working_set`
6. `complete_project`

验证：

1. 修订后的 chunk 先增量写回
2. 最终 merged draft 写回

### 路径 C：HITL path

第一阶段：启动到暂停

1. `evaluate_focus`
2. `draft_revision`
3. `complete_working_set(chunk-1)`
4. `request_human_review`

验证：

1. 返回 `humanReviewRequest`
2. 生成本地 session JSON
3. 数据库中 `chunk-1` 已被增量写回

第二阶段：恢复到完成

人工输入：

- 一段自由文本，例如“Louki 统一译为露姬”

恢复后脚本：

1. `complete_working_set(chunk-2)`
2. `complete_project`

验证：

1. session 文件在结束后被清理
2. `mergedDraftText` 已写回数据库
3. `completedChunkOutcomes` 与数据库 `chunks_json` 一致

### 路径 D：NO_PROGRESS path

这条路径必须补，因为它是 R-10 的核心验证。

目标：

1. 连续 3 次 guardrail 拒绝后，runtime 进入 `FAILED`
2. 不转 `WAITING_HUMAN`
3. 不生成 session 文件

验证：

1. `stopReason == NO_PROGRESS`
2. `humanReviewRequest` 为空
3. `reviewSessionStore.load(projectId)` 为空

这里不需要依赖真实 LLM 脚本推动具体内容，核心是：

1. 让 scripted generation port 连续产出会被 guardrail 拒绝的 tool call
2. 证明 agent 不会把它伪装成 HITL

## 4.6 e2e 基础设施

建议复用现有真实边界：

1. `FileReviewSessionStore`
2. `PostgresPostDraftReviewAgentWriter` 的 in-memory repository 测试替身
3. `InMemoryHumanInTheLoopGateway`
4. `PostDraftReviewAgentService`

不建议 mock 掉：

1. service
2. session store
3. writer

否则 e2e 就会退化成单元测试。

---

## 5. 真实项目 smoke 的准入条件

只有同时满足下面条件，才建议第一次跑真实完整项目冒烟：

1. Spring Bean 链已通过
2. CLI `start` / `resume` 已通过
3. `WAITING_HUMAN` session 持久化 / 恢复已通过
4. LLM retry / backoff 已通过定向测试
5. scripted e2e 已通过
6. 项目级 writer 写回已通过

也就是说，真实 smoke 的前置不需要：

1. 异步 REST API
2. `GET /status` 产品化
3. 外部通知机制

第一次真实 smoke 推荐直接使用：

1. `CommandLineRunner start`
2. `CommandLineRunner resume`

目标是验证真实 `projectId` 的运行链，不是验证产品入口形态。

但真实 smoke 不建议一上来就跑全量长项目，建议分层推进：

### Phase 3A：1 chunk smoke

目标：

1. 先验证真实模型、真实 projectId、真实数据库写回链能闭合

验证：

1. `writeCompletedChunks` 确实发生
2. 若完成项目，则 `writeMergedDraftText` 确实发生
3. 若进入 `WAITING_HUMAN`，session 文件确实生成

### Phase 3B：5-10 chunk smoke

目标：

1. 验证连续 chunk 精修不会立即暴露 prompt / session / writeback 级问题

### Phase 3C：完整项目 smoke

目标：

1. 验证长跑稳定性
2. 验证中途 HITL 恢复后仍能跑完

此外，真实 smoke 必须有 wall clock 保护，避免跑挂后无限等待。

建议加：

1. `maxWallClockMinutes`
2. 超时后显式中止，并记录当前完成 chunk 数 / 最后状态

---

## 6. 推荐顺序

### 第一阶段：补 LLM 稳态能力

1. 给 `ReviewAgentLlmProperties` 加 retry 配置
2. 评估 LangChain4j 内置 retry 是否够用
3. 不够用则补 `RetryingReviewAgentStructuredGenerationPort`
4. 补 stop reason：`LLM_CALL_FAILED`
5. 跑定向测试

### 第二阶段：补 scripted e2e

1. 新增 `ScriptedReviewAgentGenerationPort`
2. 写项目级 start -> WAITING_HUMAN -> resume -> complete 的 e2e
3. 跑 e2e

### 第三阶段：第一次真实 smoke

1. 先跑 1 chunk smoke
2. 再跑 5-10 chunk smoke
3. 最后跑完整项目 smoke
4. 若进入 `WAITING_HUMAN`，人工输入后用 `resumeProject(projectId, humanReviewNote)` 继续
5. 观察 `writeCompletedChunks` / `writeMergedDraftText` / `sessionStore.delete` 是否都符合预期

---

## 7. 红线自检

### R-06

不把运行期临时状态写回 `PostDraftReviewPackage` 或 `ProjectKnowledgeBase`。本稿中的写回仍然只允许：

1. chunk `translatedText`
2. `mergedDraftText`

### R-09

HITL 仍然只能由 agent 主动调用 `request_human_review` 触发。retry 不会把基础设施失败伪装成人工求助。

### R-10

`NO_PROGRESS` 仍然是 bug 路径，不会因为 retry 或 scripted e2e 被包装成可恢复暂停。

### R-11

本稿不新增 review tool，不往 `ReviewToolExecutor` 继续堆 switch。

### R-14

人工输入仍然是证据，不会被做成 `resume` 命令参数以外的稳定运行态污染物；不会塞回 `TranslationTaskInput`。

---

## 8. 审核点

你审核这份稿时，建议重点看 4 件事：

1. 重试是否明确限定在客户端层，而不是 agent loop 层
2. 异常分层是否足够清楚，避免 retry 与 repair 互相误伤
3. scripted e2e 的路径是否足以证明“可以暂停、恢复并继续完成”，以及 `NO_PROGRESS` 不会被伪装成 HITL
4. “真实 smoke 的准入条件”是否足够保守
---

## 9. 2026-04-18 实现后发现的问题与修正

### 9.1 对应 §3.5：第一次实现没有先确认 LangChain4j 异常层次

第一次实现时，`OpenAiCompatibleReviewAgentStructuredGenerationClient` 直接用异常消息字符串匹配 `429` / `503` / `timeout` / `connect` 来判断 transient 失败。这个写法过于宽松，容易误匹配无关文本，也没有兑现“先确认 LangChain4j 异常层次再落 predicate”的设计要求。

现已改为基于 LangChain4j 1.12.2 的实际异常类型判断：

1. `RateLimitException`
2. `TimeoutException`
3. `HttpException` 且 `statusCode == 429 || statusCode == 503`
4. `SocketTimeoutException` / `HttpConnectTimeoutException` / `ConnectException`

不再遍历整条 cause chain 拼接消息做宽松关键字匹配。

### 9.2 对应 §3.9：`jitterFactor` 第一次实现只声明未生效

第一次实现时，`ReviewAgentLlmRetryPolicy` 虽然声明了 `jitterFactor`，但 `backoffForAttempt(...)` 没有真正应用抖动，等于把它做成了假配置。

现已修正为：

1. 基础退避仍为指数退避
2. 在 `[1 - jitterFactor, 1 + jitterFactor]` 范围内施加对称抖动
3. 抖动后仍受 `maxBackoff` 上限约束

### 9.3 对应 §4.5 路径 D：`NO_PROGRESS` e2e 的脚本条数需要额外说明

第一次落地路径 D 时，测试里用了 6 个连续非法决策，而不是直觉上的 3 个。这不是 `NO_PROGRESS` 的领域阈值变了，而是 `PromptBackedNextStepDecisionProvider` 每轮会先做 1 次 repair 尝试：

1. 第 1 个 scripted 决策：原始非法输出
2. 第 2 个 scripted 决策：repair 后仍非法
3. 这 2 个 scripted 决策最终只形成 1 次真正进入 `ReviewToolExecutor` 的 guardrail 拒绝

因此，要稳定累计到 `NO_PROGRESS_REJECTION_THRESHOLD = 3`，脚本里需要准备 6 个连续非法决策。测试代码已补注释说明这一点，避免把“脚本条数”误当成“领域阈值”。

另外，当前 scripted e2e 先优先覆盖了最关键的两条路径：

1. `WAITING_HUMAN -> resume -> complete`
2. `NO_PROGRESS -> FAILED`

happy path 与 revision path 仍建议后续补齐，但不是第一次真实 smoke 的前置阻塞。
