# [OUTDATED - 已被 2026-04-15-project-level-unified-review-agent-loop-plan.md 取代] 项目级自治审校 Agent 第二阶段实施计划

## 目标

在现有第一阶段“最小单轮闭环”基础上，把 review agent 升级成“受控的多步自治 loop”。

这一阶段只解决三件事：

1. 让 agent 能在同一轮任务中多步取证，而不是一次读取后立即收口。
2. 让 session 真正承载工作记忆，支撑动作选择、收敛和恢复。
3. 让 `RETRANSLATE` 从“受控转人工路径”升级成真实执行路径。

## 明确边界

这一阶段不做：

1. 不引入 subagent 依赖。
2. 不默认接入 web search。
3. 不建设独立长期记忆平台。
4. 不回退成大 orchestrator。
5. 不把运行期状态塞回 `TranslationTaskInput`、`PostDraftReviewPackage` 或其他稳定领域对象。
6. 不把 review agent 代码混回原初稿流水线包。

## 设计口径

本阶段实现遵循以下设计口径：

1. 采用“弱状态机 + 强动作自治”。
2. 状态只约束阶段边界，不把每一步预编排死。
3. 动作选择采用混合式：
   - 代码生成允许动作集合
   - 模型在集合内推荐下一步动作
   - 代码校验并执行
4. prompt 分三类：
   - 取证 prompt
   - 评估 prompt
   - 修订 prompt
5. 记忆机制先服务 loop：
   - 先补最小工作记忆
   - 后补压缩记忆

## 涉及文件

### 主要新增文件

1. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentState.java`
   - 最小显式状态枚举。
2. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentActionType.java`
   - 状态内可选动作枚举。
3. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentAction.java`
   - 单个动作对象，承载动作类型、参数和理由。
4. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentActionProposal.java`
   - 取证 prompt 的结构化输出。
5. `src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentEvaluation.java`
   - 评估 prompt 的结构化输出。
6. `src/main/java/io/quillloom/application/postdraft/review/model/RevisionDraft.java`
   - 修订 prompt 的结构化输出。
7. `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAllowedActionPlanner.java`
   - 根据状态和 session 生成允许动作集合。
8. `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewActionExecutor.java`
   - 执行动作并回写 session。
9. `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewLoopRunner.java`
   - 多步 loop 主编排器。
10. `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftRetranslationService.java`
    - `RETRANSLATE` 的真实执行路径。
11. `src/main/java/io/quillloom/application/postdraft/review/service/prompt/InvestigationPromptBuilder.java`
12. `src/main/java/io/quillloom/application/postdraft/review/service/prompt/EvaluationPromptBuilder.java`
13. `src/main/java/io/quillloom/application/postdraft/review/service/prompt/RevisionPromptBuilder.java`

### 主要修改文件

1. `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewSession.java`
   - 扩展为真正的工作记忆容器。
2. `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`
   - 从单轮收口器升级为 loop 入口。
3. `src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentReader.java`
   - 增补多步取证需要的读取动作。
4. `src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java`
   - 实现新的读取能力。
5. `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewProcessSummaryAssembler.java`
   - 适配多步 loop 的过程说明。

### 测试文件

1. `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAllowedActionPlannerTest.java`
2. `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewActionExecutorTest.java`
3. `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewLoopRunnerTest.java`
4. `src/test/java/io/quillloom/application/postdraft/review/PostDraftRetranslationServiceTest.java`
5. `src/test/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReaderTest.java`
   - 增补多步读取场景。

## 分任务实施

### Task 1：引入最小显式状态机和动作模型

目标：

1. 把当前隐式流程升级成显式状态推进。
2. 建立“状态边界”和“动作集合”的基础模型。

改动：

1. 新增 `ReviewAgentState`
   - `INITIALIZING`
   - `INVESTIGATING`
   - `EVALUATING`
   - `REVISING`
   - `WAITING_HUMAN`
   - `COMPLETED`
   - `FAILED`
2. 新增 `ReviewAgentActionType`
   - `READ_PREVIOUS_CHUNKS`
   - `READ_NEXT_CHUNKS`
   - `EXPAND_BLOCK`
   - `READ_DECISION_NOTES`
   - `READ_TRANSITION_NOTE`
   - `LOOKUP_KNOWLEDGE_CARDS`
   - `SEARCH_PROJECT_SNIPPETS`
   - `REVISIT_EVIDENCE`
   - `ENTER_EVALUATION`
   - `ENTER_REVISION`
   - `REQUEST_HUMAN_REVIEW`
3. 扩展 `PostDraftReviewSession`
   - 增加当前状态
   - 增加已执行动作轨迹
   - 增加已访问对象集合
   - 增加关键证据摘要、冲突证据摘要、证据缺口

验证：

1. 新增状态和动作模型测试通过。
2. 现有 session 测试同步更新通过。

### Task 2：实现允许动作规划器

目标：

1. 让代码根据当前状态和 session 生成“允许动作集合”。
2. 不把下一步硬编码死。

改动：

1. 新增 `PostDraftReviewAllowedActionPlanner`
2. 输入：
   - 当前状态
   - 当前问题模型
   - 已执行动作
   - 当前证据缺口
   - 当前预算信息
3. 输出：
   - 当前允许动作集合
4. 第一阶段规则要点：
   - `INVESTIGATING` 才允许扩展阅读
   - `EVALUATING` 只允许继续取证、进入修订或转人工
   - `WAITING_HUMAN` 不允许继续自主推进

验证：

1. 新增 `PostDraftReviewAllowedActionPlannerTest`
2. 覆盖至少以下场景：
   - 衔接问题优先开放前后文动作
   - 已有未决 decision note 时开放 note 相关动作
   - 达到预算上限时不再开放无限扩展动作

### Task 3：扩展 reader 和动作执行器

目标：

1. 让 agent 真正执行多步取证动作。
2. 每轮动作执行后能把结果写回 session。

改动：

1. 扩展 `PostDraftReviewAgentReader`
   - 支持前后多个 chunk 连续读取
   - 支持按 block 扩展
   - 支持读取 notes
   - 支持知识卡读取
2. 新增 `PostDraftReviewActionExecutor`
   - 执行单个动作
   - 归纳动作结果为 session 摘要
   - 去重已访问对象
3. 在这一层加入最小预算控制：
   - 最大循环轮次
   - 最大前后文窗口
   - 最大重复动作次数

验证：

1. `RepositoryBackedPostDraftReviewAgentReaderTest` 增补多步场景。
2. 新增 `PostDraftReviewActionExecutorTest`
3. 覆盖至少以下场景：
   - 连续前读和后读
   - block 扩展
   - 重复访问去重
   - notes 和知识卡读取

### Task 4：实现三类 prompt 的最小结构化契约

目标：

1. 让模型输出可被 loop 稳定消费。
2. 不依赖代码去猜模型自然语言意图。

改动：

1. 新增三类 builder：
   - `InvestigationPromptBuilder`
   - `EvaluationPromptBuilder`
   - `RevisionPromptBuilder`
2. 新增三类结构化结果对象：
   - `ReviewAgentActionProposal`
   - `ReviewAgentEvaluation`
   - `RevisionDraft`
3. 第一阶段只要求关键控制字段结构化：
   - 取证 prompt：推荐动作、动作理由、证据缺口、是否进入评估
   - 评估 prompt：推荐策略、策略理由、证据充分性、是否继续取证
   - 修订 prompt：正式译文、修订模式、关键依据、剩余风险

验证：

1. 为 builder 增加定向测试，确认输入字段完整。
2. 为结构化结果解析增加定向测试。

### Task 5：实现真正的多步 loop runner

目标：

1. 把当前“单轮读一次就收口”升级成“多轮取证-评估-修订”。
2. 让状态迁移由证据和收敛驱动。

改动：

1. 新增 `PostDraftReviewLoopRunner`
2. 基本循环：
   - 初始化 session
   - 进入 `INVESTIGATING`
   - 规划允许动作集合
   - 调用取证 prompt 选择动作
   - 执行动作并更新 session
   - 进入 `EVALUATING`
   - 调用评估 prompt 决定继续、修订或转人工
3. 第一阶段状态迁移规则：
   - `INITIALIZING -> INVESTIGATING`
   - `INVESTIGATING -> EVALUATING`
   - `EVALUATING -> INVESTIGATING`
   - `EVALUATING -> REVISING`
   - `EVALUATING -> WAITING_HUMAN`
   - `REVISING -> COMPLETED`
   - `WAITING_HUMAN -> INVESTIGATING/REVISING`
4. 显式停止条件：
   - 达到最大循环轮次
   - 证据已经足够收敛
   - 命中人工介入规则

验证：

1. 新增 `PostDraftReviewLoopRunnerTest`
2. 覆盖至少以下场景：
   - 先取证再评估再轻修完成
   - 多轮取证后进入重译
   - 证据冲突后转人工
   - 达到预算上限后停止继续探索

### Task 6：实现 `RETRANSLATE` 的真实执行路径

目标：

1. 不再把 `RETRANSLATE` 仅仅当作转人工分支。
2. 真正执行重译并产出正式译文。

改动：

1. 新增 `PostDraftRetranslationService`
2. 在 `REVISING` 状态下分流：
   - `LIGHT_EDIT`
   - `RETRANSLATE`
3. `RETRANSLATE` 执行后必须：
   - 产出正式译文
   - 回填过程说明
   - 若结果仍不稳定，再受控转人工

验证：

1. 新增 `PostDraftRetranslationServiceTest`
2. 更新 `PostDraftReviewAgentServiceTest`
3. 覆盖以下场景：
   - `RETRANSLATE` 直接成功落笔
   - `RETRANSLATE` 后自检失败再转人工

### Task 7：收敛过程说明与 human-in-the-loop 恢复

目标：

1. 让过程说明与多步 loop 保持一致。
2. 让人审等待和恢复不打断 session 语义。

改动：

1. 扩展 `PostDraftReviewProcessSummaryAssembler`
   - 汇总多轮取证轨迹
   - 汇总关键支持证据与冲突证据
   - 汇总停止原因
2. 扩展人审恢复信息：
   - 请求原因
   - 当前等待点
   - 恢复提示

验证：

1. 更新 `PostDraftReviewAgentServiceTest`
2. 覆盖：
   - 人审请求形成完整说明
   - 人审恢复后继续推进 loop

## 验证计划

本阶段完成前，至少运行以下测试：

```bash
mvn -q "-Dtest=PostDraftReviewSessionFactoryTest,PostDraftReviewAllowedActionPlannerTest,PostDraftReviewActionExecutorTest,PostDraftReviewLoopRunnerTest,PostDraftRetranslationServiceTest,RepositoryBackedPostDraftReviewAgentReaderTest,PostDraftReviewAgentServiceTest" test
```

如涉及现有第一阶段测试更新，也必须保证原有以下测试继续通过：

```bash
mvn -q "-Dtest=PostDraftReviewSessionFactoryTest,RepositoryBackedPostDraftReviewAgentReaderTest,PostDraftReviewStrategyResolverTest,PostDraftReviewAgentServiceTest" test
```

## 完成定义

当且仅当满足以下条件，第二阶段才算完成：

1. review agent 已具备真正的多步自治 loop，而不是单轮收口。
2. `session` 已能承载动作轨迹、关键证据和策略收敛信息。
3. `RETRANSLATE` 已具备真实执行路径。
4. 人审等待和恢复已能挂接在 loop 上。
5. 代码仍然保持在独立的 `postdraft.review` 包内。
6. 所有新增和回归测试实际跑过并通过。
