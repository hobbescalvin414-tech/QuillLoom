# Review Agent Autonomy Stability Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` task-by-task. Do not expand beyond the `post-draft review agent` chain.

**Goal:** 把 `post-draft review agent` 从“动态工具外壳 + 残留阶段状态机 + executor 过度接管”的混合实现，重构成更接近真实自主 agent 的控制模型，使其主要由 `evidence / tool result / stop reason` 驱动，并消除当前 `complete_working_set` 参数错误导致的死循环。

**Design Principles**
- 错误必须显式暴露给 agent。不能用静默补参、静默 fallback 掩盖参数错误。
- `complete_project` 保留为显式完成声明工具。LLM 必须能清楚表达“项目已完成”。
- 循环检测必须保留，但只能作为诊断与停机依据，不能再演化成阶段状态机。
- `INVESTIGATING / EVALUATING / REVISING / WAITING_HUMAN` 中，只有 `WAITING_HUMAN` 可以保留硬边界语义；其余阶段状态最多保留为观察标签。
- 项目主控制流只由：新证据、tool result、working set 变化、显式 stop reason 驱动。

**Architecture**
- 保留 `PostDraftReviewAgentService.reviewProject(...)` 作为唯一正式入口。
- 项目级运行时收缩为 `ACTIVE / WAITING_HUMAN / COMPLETED / FAILED`。
- focus session 只承载事实、证据、轨迹与诊断，不再承载阶段机。
- `ReviewToolExecutor` 回归“参数校验 + guardrail + 执行 + effect”，不再偷接后续控制流。
- `complete_project` 由 agent 显式调用，runtime 只负责边界校验与收口。
- `FocusReviewDiagnostics` 负责记录循环/错误诊断，但不负责阶段推进。

## File Map

### Create
- `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewStatus.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/FocusReviewDiagnostics.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewProjectStopReason.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolCallNormalizer.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewToolCallNormalizerTest.java`

### Modify
- `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewSession.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolDefinition.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewToolExecutionResult.java`
- `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- `src/main/java/io/quillloom/application/postdraft/review/prompt/EvaluationPromptBuilder.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewProcessSummaryAssembler.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewSessionFactory.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolGuardrail.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/SequenceProjectFocusSelector.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/WorkingSetCompletionHandler.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ProjectReviewOutputAssembler.java`
- `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftProjectRuntimeSessionModelTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewProcessSummaryAssemblerTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewSessionFactoryTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewSessionModelTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewStructuredResultModelTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/WorkingSetCompletionHandlerTest.java`
- `src/test/java/io/quillloom/PostDraftProjectReviewAgentSmokeTest.java`
- `docs/handoff.md`

### Delete
- `src/main/java/io/quillloom/application/postdraft/review/model/ProjectRuntimeStopReason.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentStopReason.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentAction.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ProjectIssueBacklog.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/DeferredReviewIssue.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentConfig.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/UsageBudget.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/UsageSummary.java`
- `src/test/java/io/quillloom/application/postdraft/review/FocusAutonomyStateModelTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ProjectIssueBacklogModelTest.java`

## Task 1: 用失败测试锁定新的控制语义

**Files**
- `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewStatus.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/FocusReviewDiagnostics.java`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewProjectStopReason.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftProjectRuntimeSessionModelTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewSessionModelTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewSessionFactoryTest.java`

- [ ] 先写失败测试，明确 project runtime 与 focus session 的新边界。
- [ ] 跑模型测试，确认旧设计与新预期冲突。
- [ ] 重写 runtime/session 模型，移除重复表达同一事实的字段。
- [ ] 重跑模型测试并转绿。

Run: `mvn -q "-Dtest=PostDraftProjectRuntimeSessionModelTest,PostDraftReviewSessionModelTest,PostDraftReviewSessionFactoryTest" test`

## Task 2: 重写工具执行链，保留显式错误与显式完成

**Files**
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolCallNormalizer.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewToolCallNormalizerTest.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolGuardrail.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/WorkingSetCompletionHandler.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/WorkingSetCompletionHandlerTest.java`

- [ ] 先写失败测试，锁定“缺参必须显式报错”和“重复错误必须停在 `NO_PROGRESS`”。
- [ ] 跑定向测试，确认旧执行链仍暴露当前 blocker。
- [ ] 重写工具注册/guardrail/executor 的 effect 边界。
- [ ] 保留 `complete_project`，让完成语义由 agent 显式表达。
- [ ] 重跑工具链测试并转绿。

Run: `mvn -q "-Dtest=ReviewToolCallNormalizerTest,ReviewToolExecutorGuardrailTest,WorkingSetCompletionHandlerTest" test`

## Task 3: 重写 agent 主循环，去掉阶段态控制流

**Files**
- `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/SequenceProjectFocusSelector.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`
- `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`

- [ ] 先写失败测试，锁定 loop 由 `status / stopReason / tool effect` 驱动，而不是由阶段 state 驱动。
- [ ] 重写 focus 选择与项目收口逻辑，清掉 `FINALIZING / SELECTING_FOCUS` 的主控角色。
- [ ] 引入显式 `NO_PROGRESS` 停机，不回退到“soft rejection 连发 -> 转人工”。
- [ ] 重跑 agent 定向测试并转绿。

Run: `mvn -q "-Dtest=AutonomousProjectReviewAgentTest,PostDraftReviewAgentServiceTest" test`

## Task 4: 提升 prompt/schema 与观察输出

**Files**
- `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- `src/main/java/io/quillloom/application/postdraft/review/prompt/EvaluationPromptBuilder.java`
- `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewProcessSummaryAssembler.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ProjectReviewOutputAssembler.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewStructuredResultModelTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewProcessSummaryAssemblerTest.java`

- [ ] 让 prompt 明确暴露最近错误、循环诊断、当前 working set、显式完成工具。
- [ ] 让 structured schema 更贴近 tool contract，而不是宽松 object。
- [ ] 摘要与输出改成反映真实自主控制语义，而不是阶段机术语。

Run: `mvn -q "-Dtest=ReviewPromptBuilderTest,ReviewStructuredResultModelTest,PostDraftReviewProcessSummaryAssemblerTest" test`

## Task 5: 回归验证与文档同步

- [ ] 运行 review agent 定向测试。
- [ ] 运行 smoke，确认 `book-smoke-1776178359703` 同类路径不再卡在 `chunk-1` 的 `missing_argument:chunkIds` 循环。
- [ ] 同步 `docs/handoff.md`，写明 blocker、根因、重构结果、剩余风险。

Run:
- `mvn -q "-Dtest=ReviewToolExecutorGuardrailTest,AutonomousProjectReviewAgentTest,PostDraftProjectReviewAgentSmokeTest" test`

