# Review Agent 运行时可观测性与失败块再消费最小修复设计

> 范围声明：本文档是最小修复设计文档，只覆盖 review-agent 当前运行时异常行为与可观测性缺口，不改产品定位，不改工具集合，不改外部协议，不改主 review loop 骨架，不把系统改造成新的 orchestrator。

## 1. 目标

本轮设计只解决 4 类当前问题：

1. `read_previous_chunks / read_next_chunks` 重复成功调用未被拦截，导致 focus round 空转。
2. 控制台无法区分以下 review-agent 运行时路径：
   - ordinary next-step round
   - structured-output repair
   - decision repair
   - `record_confirmed_terms` proposal special path
   - proposal `NOT_APPLICABLE` 后 local replan
   - containable failure 后切换到下一个 focus
   真正 transport retry 当前仍主要依赖基础设施日志观察，不作为本轮 `ReviewRuntimeVisualizer` 的交付范围。
3. containable failure 当前只做“隔离失败块”，没有“尾部再消费”，最终可能因 backlog 未清而无法 `complete_project`。
4. 当前 prompt / repair / proposal 文案几乎全英文，导致 `reason` 等面向人的摘要字段主要为英文，不符合当前项目中文优先偏好。

## 2. 不可破坏的边界

1. 不引入新的全局 router / planner / orchestrator。
2. 不改变 `ReviewToolDecision`、tool schema、tool 名称、外部协议。
3. 不改变 review-agent 主循环的“select focus -> decide -> execute -> persist”骨架。
4. 不破坏现有 retry / repair / proposal / persistence / resume。
5. 不用静默 fallback 掩盖失败。
6. 所有修复优先放在当前职责边界内完成。

## 3. 根因分析

### 3.1 行为 bug：相邻读取无增量时仍被记为成功

当前 `read_previous_chunks / read_next_chunks` 已经基于 `boundaryWindow` 左右边界扩展，而不是围绕 focus 固定重读；方向本身是对的。

需要先明确一个语义边界：

1. `PostDraftReviewAgentReader.readAdjacentChunks(...)` 的语义是“围绕给定边界 chunk 返回一个相邻窗口”。
2. 它不是“只返回新增 chunk”的接口。
3. 因此，当右边界从 `chunk-3` 开始连续调用 `read_next_chunks count=1` 时，reader 会先后返回：
   - `[chunk-3, chunk-4]`
   - `[chunk-4, chunk-5]`
   executor 去重后，净新增分别是 `chunk-4`、`chunk-5`；这正是预期行为。
4. 所以“相邻读取重复调用”并不表示 reader 基础语义错误。

但 `ReviewToolExecutor.executeReadAdjacent(...)` 与 `applyReadChunks(...)` 没有判断“这次读取是否真的引入了新 chunk”：

1. 如果 reader 返回空列表，仍会走 success。
2. 如果 reader 返回的是“只包含当前边界自身”的裁剪窗口，且净新增为 0，仍会走 success。
3. 如果 reader 返回的 chunk 已经全部存在于当前 `workingSet / boundaryWindow`，仍会走 success。
4. success 后会直接把 `currentFocusRound + 1`，制造新的 focus round。

这会导致：

1. 已到边界时，reader 的“边界窗口裁剪”行为会被 executor 误判为一次成功取证。
2. LLM 误以为“刚刚获得了新证据”。
3. 控制台出现多次相同 `read_previous_chunks` / `read_next_chunks` success。
4. round 数上升但 working set 无实质扩展。

代码依据：

1. `ReviewToolExecutor.executeReadAdjacent(...)`
2. `ReviewToolExecutor.applyReadChunks(...)`
3. `RepositoryBackedPostDraftReviewAgentReader.sliceWindow(...)` 的窗口语义与边界裁剪语义
4. `ReviewToolExecutor` 当前只对 `read_confirmed_terms` 做重复成功拦截，没有对相邻读取做同类保护。

### 3.2 行为 / 表示不一致：workingSet 顺序不是规范 chunk 顺序

当前 `ReviewBoundaryWindow` 与 `ReviewWorkingSetContext` 都会按 `sequence, chunkId` 规范排序。

但 `ReviewWorkingSet` 的构造逻辑是：

1. 先固定 anchor 在第一位。
2. 再按传入顺序去重追加其他 chunk。

这会导致 focus 为 `chunk-7` 时：

1. 连续读上文可能得到 `[chunk-7, chunk-6, chunk-5]`
2. 连续读下文可能得到 `[chunk-7, chunk-8, chunk-9]`
3. 左右都读后会出现“anchor-first + 其他块追加”的混合顺序

于是控制台里的 `workingSet=` 看起来像乱序，而 `boundaryWindow / workingSetContext` 又是有序的，形成运行时表示不一致。

代码依据：

1. `ReviewWorkingSet.normalizeChunkIds(...)`
2. `ReviewBoundaryWindow.normalize(...)`
3. `ReviewWorkingSetContext.normalize(...)`

### 3.3 可观测性缺失：repair / replan / containable failure 没有进入 visualizer

当前控制台之所以看起来像“神秘重试”，核心不是 transport retry 语义不清，而是高层 visualizer 还停留在旧接口：

1. `projectStarted`
2. `focusSelected`
3. `toolCalled`
4. `toolCompleted`
5. `projectFinished`

而真正的重要运行时路径发生在：

1. `PromptBackedNextStepDecisionProvider` 内部 repair/replan/proposal 分支
2. `AutonomousProjectReviewAgent` 的 containable failure 捕获与 focus 切换

这些路径目前只存在于：

1. prompt dump
2. exception type
3. `processTrail`

但没有被显式转换成控制台 trace 事件。

结果就是：

1. 真 retry 与 repair 再调用无法区分
2. proposal special path 不可见
3. containable failure 看起来像“静默跳过当前 chunk”

代码依据：

1. `ReviewRuntimeVisualizer`
2. `ConsoleReviewRuntimeVisualizer`
3. `PromptBackedNextStepDecisionProvider`
4. `AutonomousProjectReviewAgent`

补充边界说明：

1. 当前代码里，真 transport retry 的稳定观测来源只存在于基础设施层日志：
   - `RetryingReviewAgentStructuredGenerationPort` 的 `review_agent_llm_retry`
   - `review_agent_llm_retry_exhausted`
2. 本轮设计不引入新的 transport retry -> visualizer 事件链路。
3. 因此本轮“可观测性修复”的硬范围应收窄为：
   - ordinary next-step round
   - structured-output repair
   - decision repair
   - proposal special path
   - local replan
   - containable failure
4. 真 transport retry 继续依赖基础设施日志，不承诺在本轮进入 `ReviewRuntimeVisualizer`。

### 3.4 设计未落地：console visualization refactor plan 仍未实施

`2026-04-25-review-agent-console-visualization-refactor-plan.md` 的方向是对的，且符合当前边界：

1. presentation-only
2. 明确 round / action / result / repair / containable failure
3. repair / proposal / local replan 仍附着在当前 round 下
4. `OFF / COMPACT / TRACE` 三档输出

但当前代码仍处于旧实现：

1. 没有 round 事件
2. 没有 repair 事件
3. 没有 containable failure 事件
4. 没有输出模式配置
5. 测试仍锁定旧单行风格

因此本问题属于“方案未实施”，不是“方案不合理”。

### 3.5 设计不完整：containable failure 只有隔离，没有再消费

当前 containable failure 路径会：

1. 把失败 focus 从 `pendingChunkIds` 移除
2. 写入 `issueBacklog`
3. 写入 `processTrail`
4. 继续跑剩余 pending chunk

这是正确的 containment。

但当前没有第二阶段：

1. 普通 pending 消费完后，再处理一轮 deferred failed chunks

因此只要 backlog 仍有 open issue，项目就不能 `complete_project`，最后只能因 `pendingChunkCount=0 but blocking backlog remains` 进入 `NO_PROGRESS`。

这不是“containable failure 设计错误”，而是“当前 endgame 缺一个最小再消费闭环”。

代码依据：

1. `ProjectReviewRuntimeSession.deferCurrentFocusFailure(...)`
2. `AutonomousProjectReviewAgent.run(...)`
3. `ProjectReviewRuntimeSession.canAutoCompletePendingEmptyProject()`

补充硬边界：

1. deferred tail pass 只能是普通 pending 清空后的一次有限尾扫。
2. 不允许在 tail pass 中再生成新的 tail 分层。
3. 不允许在 tail pass 中引入新的通用优先级、回插、再延期机制。
4. 它只是在现有主循环上的窄 endgame 补丁，不是第二套调度系统。

### 3.6 prompt 语言策略问题：面向人的摘要字段缺少目标语言约束

当前以下文案层几乎全英文：

1. `ReviewAgentSystemPromptBuilder`
2. `InvestigationPromptBuilder`
3. `PromptBackedNextStepDecisionProvider` 中 proposal / repair / replan prompt
4. schema description

而 `reason`、`strategyReason`、`questionForHuman` 这类字段没有任何“使用当前译文目标语言”的硬约束，所以模型自然倾向输出英文说明。

本问题不是 executor 或 transport 问题，而是 prompt 规范缺失。

补充范围说明：

1. 若本轮继续把 `strategyReason` 纳入语言策略范围，则实现范围必须包含 evaluation prompt。
2. 若实现范围保持在 system / investigation / provider special path，则语言策略目标必须相应收窄，不得声称已覆盖 `strategyReason`。

## 4. 设计结论

### 4.1 关于“哪些是真 retry”

定义锁定如下：

1. 真 retry：
   - 只指 `RetryingReviewAgentStructuredGenerationPort` 的 transport / transient retry
   - 必须能看到 retry reason、exception type、attempt 递增
2. 非 retry 的再次 LLM 调用：
   - ordinary next-step 新一轮决策
   - structured-output repair
   - decision repair
   - proposal special path
   - proposal repair
   - proposal `NOT_APPLICABLE` 后 local replan

本轮设计收窄如下：

1. 控制台必须显式区分：
   - ordinary next-step round
   - structured-output repair
   - decision repair
   - proposal special path
   - proposal repair
   - proposal `NOT_APPLICABLE` 后 local replan
   - containable failure
2. 真 transport retry 继续通过基础设施日志区分，不作为本轮 `ReviewRuntimeVisualizer` 的必交付项。

### 4.2 关于相邻读取重复调用

最小修复点锁定在 executor，不在全局 guardrail，也不在工具协议：

1. 保持当前 reader 的“边界窗口”语义不变，不改成“只返回新增 chunk”接口。
2. executor 必须显式判断本次相邻读取是否引入了净新增 chunk。
3. 若相邻读取没有引入任何新 chunk，则必须 rejected，不得 success。
4. rejection reason 必须可诊断，明确说明：
   - 已到边界，无更多 chunk
   - 或本次读取与当前边界/workingSet完全重复
5. rejection 必须带 `local_replan_hint`，提示模型：
   - 不要重复相同读取
   - 需要新方向时改读另一侧
   - 若证据已足够则转 `evaluate_focus` / `complete_working_set`

### 4.3 关于 workingSet 顺序

最小修复锁定为“显示层与 prompt 注入层使用规范 chunk 顺序”，而不是直接修改 `ReviewWorkingSet` 全局语义：

1. 保留 `ReviewWorkingSet` 当前 runtime 语义不变。
2. 新增 canonical render/view，用于 console visualizer 与 prompt 上下文注入。
3. 继续保留 `anchorChunkId` 单独字段，不靠列表位置表达锚点。
4. console visualizer 只展示 canonical view，不再直接展示 anchor-first 的混合顺序。

### 4.4 关于失败 chunk 再消费

不引入新的 orchestrator。

最小方案是：

1. containable failure 后，不把 chunk 直接永久移出消费域。
2. 失败 chunk 进入“deferred pending tail”。
3. 普通 pending 清空后，只允许进入一次有限 deferred tail pass。
4. 每个 deferred chunk 需要有有限次数上限；超过上限仍保留 issue/backlog，并阻止 `complete_project`。
5. tail pass 不得衍生第二套通用调度规则。

该方案的意图是：

1. 保留 containment
2. 避免失败块彻底失联
3. 不把重试机制扩展成新调度系统

兼容性边界锁定如下：

1. deferred tail 所需 carrier 优先定义为 agent-private runtime-only state。
2. 本轮设计不默认把 deferred tail carrier 扩展进 persistence / resume。
3. 只有在实现阶段确认 runtime-only 无法满足主循环闭环时，才允许单独补 persistence / resume 兼容设计与测试。

### 4.5 关于控制台输出

采用已有 2026-04-25 文档方向，不另起方案。

本轮最小落地优先级：

1. `focusRoundStarted`
2. `decisionProduced`
3. `repairTriggered`
4. `localReplanTriggered`
5. `containableFailureCaptured`
6. `focusRoundFinished`

其中：

1. 高层事件仍只由 `AutonomousProjectReviewAgent` 发出
2. 下层 service 不直接依赖 visualizer
3. 需要的 repair / proposal / rejection 信息优先通过既有 carrier 回传

carrier 顺序在本轮设计中固定如下：

1. `ReviewToolExecutionResult`
2. `ProjectReviewRuntimeSession.processTrail`
3. classified exception types
4. 只有以上三者都无法稳定表达时，才允许新增 provider 私有只读 diagnostics DTO

额外限制：

1. provider 私有 DTO 不得进入 persistence / resume / external protocol
2. provider 私有 DTO 不得扩散成新的跨 service 协作协议

### 4.6 关于语言策略

本轮不追求“模型内部思考语言”控制，目标只锁定到“面向人类可见的摘要字段”。

规则锁定如下：

1. `reason`
2. `strategyReason`
3. `questionForHuman`
4. repair / replan / proposal justification

默认应与当前译文目标语言一致。

对当前项目，中文优先。

例外：

1. `sourceText` 原文引用
2. 术语原文
3. tool 名称
4. JSON 键名

这些保持原样。

本轮设计对覆盖范围再收一层：

1. `reason`
2. `questionForHuman`
3. repair / replan / proposal justification

这三类属于本轮必覆盖。

`strategyReason`：

1. 若后续计划不纳入 evaluation prompt，则不得在实现验收中声称已完成覆盖。
2. 若要纳入本轮覆盖，必须把 evaluation prompt 与相关测试显式纳入实施范围。

## 5. 最小修复范围

### 5.1 必改文件

1. `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
2. `src/main/java/io/quillloom/application/postdraft/review/service/ReviewRuntimeVisualizer.java`
3. `src/main/java/io/quillloom/application/postdraft/review/service/ConsoleReviewRuntimeVisualizer.java`
4. `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
5. `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`
6. `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
7. `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
8. `src/main/java/io/quillloom/infrastructure/postdraft/review/ReviewAgentRuntimeProperties.java`
9. `src/main/java/io/quillloom/infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfiguration.java`

### 5.2 必改测试

1. `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`
2. `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`
3. `src/test/java/io/quillloom/application/postdraft/review/ConsoleReviewRuntimeVisualizerTest.java`
4. `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
5. `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

### 5.3 本轮不改

1. `ReviewToolDecision` 外部协议
2. tool registry 工具集合
3. persistence / resume schema
4. `RetryingReviewAgentStructuredGenerationPort` 的 retry 语义
5. `OpenAiCompatibleReviewAgentStructuredGenerationClient` 的 JSON shape
6. `ReviewWorkingSet` 的全局 runtime 语义

## 6. 验证要求

1. 必须能从控制台直接区分：
   - repair
   - local replan
   - proposal special path
   - containable failure
   - 普通 next-step round
2. 必须覆盖：
   - 相邻读取无增量 -> rejected
   - 相邻读取扩展成功 -> canonical render/view 顺序正确
   - containable failure -> 失败块进入 deferred tail，而不是永久失联
   - pending 正常清空后 deferred tail 继续消费
   - 中文语言策略至少覆盖 `reason` / `questionForHuman`
   - visualizer `OFF / COMPACT / TRACE` 三档行为清楚
3. 不允许通过静默吞错来“看起来更稳定”。

## 7. 结论

本轮问题不是一个点状 bug，而是四类问题叠加：

1. 行为 bug：相邻读取无增量仍 success
2. 可观测性缺失：repair / replan / containable failure 不可见
3. 设计未落地：console visualization plan 尚未实施
4. prompt 语言策略缺失：人类可见摘要字段没有目标语言约束

最小修复应保持窄范围：

1. executor 拦截无增量读取
2. canonical workingSet 顺序
3. visualizer 落地 round/repair/failure
4. containable failure 增加 deferred tail pass
5. prompt 增加中文优先摘要规则
