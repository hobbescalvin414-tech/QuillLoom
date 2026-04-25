# Review Agent Context And Memory Two-Phase Implementation Plan

> 实施前提：本计划以 `application/postdraft/review` 与 `infrastructure/postdraft/review` 为主改动面，必要时允许触达与 review-agent 人工请求透传、状态展示、DTO 输出、writer/gateway 边界适配、smoke/debug 验证直接相关的少量外层文件。不在本计划内重做大 orchestrator，不回退到主检索 agent，也不改外部工具集合。

## 目标

在不改变 agent 总输入/总输出协议、不新增工具、不重做工具系统的前提下，分两步修复 Review Agent 的上下文与 prompt 问题：

1. Phase B：修复当前 focus 内 workingSet 正文上下文与 prompt 注入失真。
2. Phase C：补上跨 focus 的审校摘要继承，但只保留可压缩、可继承、不会污染稳定领域契约的摘要记忆。

最终目标是让 Review Agent 稳定完成三类工作：

1. 文学翻译审校：对照原文与译文，发现错译、漏译、保留原文未译、明显不合逻辑等问题。
2. 全文命名一致性维护：对人名、地名、代号、术语做 project-level 核对与登记。
3. 篇章衔接与 workingSet 提交：阅读相邻 chunk 判断衔接，必要时修订，确认无误后提交包含 focusChunk 的 workingSet。

## 架构边界

### 固定边界

1. 不改 `ReviewAgentStructuredGenerationPort` 的对外方法签名。
2. 不改 `ReviewToolDecision` / `ReviewToolExecutionResult` 的外部协议。
3. 不新增工具，不删除工具，不改 tool registry 的工具集合。
4. 不把 loop 临时状态塞回 `TranslationTaskInput`、`PostDraftChunkRecord` 等稳定领域契约。
5. 当前系统仍是受控流水线内的 review agent，不是自治 agent 社会。
6. 不回退到大 orchestrator。
7. `questionForHuman` 只允许作为 additive、backward-compatible 的人工协作/诊断字段扩展；它不改变工具调用协议、不新增必填入参、不改变 `ReviewAgentStructuredGenerationPort` 的外部契约。若外部消费者要求严格 schema 不变，则实施时必须同步补兼容说明或版本策略。

### 允许改动范围

1. `application/postdraft/review/model` 下的 session 内部上下文模型。
2. `application/postdraft/review/service` 下工具执行后的 session/runtime 落位逻辑。
3. `application/postdraft/review/prompt` 下各阶段 prompt 的上下文注入结构。
4. `infrastructure/postdraft/review` 下的 session 持久化兼容处理。
5. `application/postdraft/review/port/out` 下与人工请求透传直接相关的 writer/gateway 边界接口。
6. `interfaces/api/dto` 下与 review-agent 状态输出直接相关的 DTO。
7. `src/test/java/io/quillloom/support` 下为 waiting-human / smoke / debug 输出服务的测试支持文件。
8. 测试与文档。

## 产品定位

### Canonical Product Positioning

这个 agent 是文学翻译审校专家。它处理的是前面流水线已经产出的小说翻译初稿，以及与该初稿相关的辅助数据。它不是重新跑翻译流水线的 agent，也不是泛调查 agent。后续 prompt 重排、上下文建模、人工升级规则都必须围绕这个定位展开。

### Canonical Responsibilities

1. 审核译文质量。
2. 维护全文命名一致性。
3. 审核篇章衔接并提交 workingSet。

更具体地说：

1. 审核译文质量时，要对照原文与译文，并结合流水线产出的相关数据，识别错译、漏译、保留原文未译、明显不合逻辑的问题。
2. 命名一致性核查时，要对全文需要一致的人名、地名、代号、术语做 project-level lookup；已存在的要核对一致性，不存在且当前 workingSet 已形成稳定 `source->target` 对时，才调用登记工具。
3. 篇章衔接核查时，要通过读取相邻或同 block chunk 来确认 continuity；若衔接不好则走 evaluation/revision 路径，若衔接与本地问题都已核实无误，则提交 chunk。`complete_working_set.chunkIds` 必须包含当前 `focusChunk`。

## 与源码一致的事实基线

以下判断已对照当前代码确认：

1. `read_previous_chunks` / `read_next_chunks` 当前仍围绕 `focusChunk` 读取，而不是围绕 `workingSet` 边界。
2. chunk 的 canonical 顺序来源已经存在于 review package / reader 侧，排序基准是：
   - `PostDraftChunkRecord.sequence` first
   - `chunkId` second
3. `ReviewEvidenceBundle` 当前同时承载了摘要记忆和高保真 chunk 正文字符串，正文上下文与摘要记忆没有拆层。
4. `InvestigationPromptBuilder`、`EvaluationPromptBuilder`、`RevisionPromptBuilder`、`RevisionSelfCheckPromptBuilder` 当前都没有稳定的 workingSet 正文上下文区块。
5. `WorkingSetCompletionHandler.validateConfirmedChunkIds(...)` 当前只校验：
   - 包含 `focusChunk`
   - 在当前 `workingSet` 内
   - 仍然 pending
   并不校验“本 focus 读过/核查过”。
6. 当前 waiting-human / resume 持久化链路使用的是“完整 runtime JSON 落盘”，而不是轻量 checkpoint。
7. `self_check_budget_exhausted` 当前是人工升级出口之一。
8. `HumanReviewRequest` 当前没有显式 `questionForHuman` 字段。

## Phase B：focus 内上下文分层与 prompt 重构

### B1. 拆开 workingSet 正文上下文与摘要记忆 `[代码已完成]`

**目标**

把 focus 内“高保真正文上下文”和“摘要型状态记忆”拆开，避免正文被压缩进 evidence summary 通道。

**设计**

1. `ReviewEvidenceBundle` 继续保留，但职责收缩为摘要型状态记忆。
2. 引入 `ReviewWorkingSetContext`，专门承载 focus 内高保真 chunk 正文快照。
3. 引入 `ReviewContextChunkSnapshot`，至少包含：
   - `chunkId`
   - `sequence`
   - `sourceText`
   - `translatedText`
   - `translatorCommentary`
   - `decisionNotes`
   - `confirmedTermUpdates`
   - `transitionNote`
   - `anchor` 标记
4. `PostDraftReviewSession` 增加 `workingSetContext` 字段，并保持旧 JSON 缺字段时可安全默认。

**文件**

- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewEvidenceBundle.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewSession.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewWorkingSetContext.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewContextChunkSnapshot.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewSessionModelTest.java`

### B2. 修正 workingSet 边界读取语义 `[代码已完成]`

**目标**

让 `read_previous_chunks` / `read_next_chunks` 以当前 workingSet 的左右边界为基准，而不是以 `focusChunk` 为基准。

**设计**

1. canonical 边界顺序固定为：
   - `PostDraftChunkRecord.sequence` first
   - `chunkId` second
2. 不允许从 `ReviewWorkingSet.chunkIds()` 的顺序推导左右边界。
3. 本计划显式区分两层上下文：
   - `boundaryWindow`：只服务 `read_previous_chunks` / `read_next_chunks` 的连续边界扩张
   - `workingSetContext`：服务 prompt 注入与正文保真
4. 具体实现路径固定为：
   - `boundaryWindow` 中的 snapshots 按 canonical order 保存
   - `ReviewToolExecutor` 只从 `boundaryWindow` 的首尾 snapshot 推导左右边界 chunkId
   - 再复用现有 `readAdjacentChunks(projectId, chunkId, before, after)`
5. `expand_block_context` 继续按 block 读取，但它扩展的是 prompt/context 可见范围，不自动改变下一次邻接扩张的边界基准。
6. `workingSetContext` 可以包含 anchor、邻接读取结果、block 扩张结果；`boundaryWindow` 只记录连续边界窗口。
7. prompt 动作树必须区分：
   - 边界扩张
   - block 扩张
8. `ReviewWorkingSet.chunkIds()` 不得再用于任何顺序敏感逻辑；顺序敏感 prompt/日志若需显示 chunk 列表，必须使用 canonicalized 顺序。
9. `boundaryWindow` 不是临时概念，必须在模型层有稳定落点。
10. 本计划默认将 `boundaryWindow` 挂入 `PostDraftReviewSession`，并与 `workingSetContext` 分离保存。
11. 若实现阶段要改成“只存在于 runtime、不进入持久化 JSON”的方案，必须先补充显式设计说明，解释为何不会破坏 resume/status 语义；否则不得擅自改成临时变量。

**文件**

- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewSession.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewBoundaryWindow.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewChunkSnapshotFormatter.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`

### B2A. 加入 per-focus completion markers `[代码已完成]`

**目标**

为 `complete_working_set` 的执行层校验提供最小可用的 focus 生命周期标记。

**设计**

1. `PostDraftReviewSession` 增加：
   - `readInFocusChunkIds`
   - `verifiedInFocusChunkIds`
2. 生命周期固定为当前 focus session 生命周期，而不是单次 tool round。
3. `readInFocusChunkIds` 的写入规则：
   - 本 focus 内成功通过 `read_previous_chunks` / `read_next_chunks` / `expand_block_context` 读入的 chunk 加入集合。
4. `verifiedInFocusChunkIds` 的写入规则：
   - 仅当执行层能可靠判定某个 pending chunk 在当前 focus 内已完成核查且无需进一步修改时，才写入集合。
5. 新建 focus session 时初始化为空；focus 完成或切换时重置。
6. 这些 markers 只允许存在于 review session / runtime scope，不得进入稳定领域契约。

**文件**

- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewSession.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewSessionModelTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`

### B2B. `complete_working_set` 第一版保守硬约束 `[代码已完成]`

**目标**

先把“未在本 focus 读过的其他 pending chunk 不能一并提交”落实成执行层硬约束，并避免把仅作 block/context 证据的 chunk 提前提交。

**设计**

1. 最终目标仍是：非 `focusChunk` 提交必须满足“已读且已核查”。
2. 第一版为避免误提交仅作上下文证据的 chunk，执行层硬约束固定为：
   - `complete_working_set` 只允许提交 `focusChunk`
3. 在执行层具备可靠 `verifiedInFocusChunkIds` 判定机制之前，任何非 `focusChunk` 都不得仅凭“已读”获得提交资格。
4. `expand_block_context` 读入的非 `focusChunk` 默认只获得上下文可见性，不获得提交资格。
5. 只有当执行层能可靠判定 `verifiedInFocusChunkIds` 后，才开放额外 non-focus chunk 的一并提交。
6. 校验点固定在 `WorkingSetCompletionHandler.validateConfirmedChunkIds(...)`。

**文件**

- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/WorkingSetCompletionHandler.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/WorkingSetCompletionHandlerTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`

### B2C. 持久化与人工恢复兼容 `[代码已完成]`

**目标**

保护 file-backed session store、resume、status load、脚本恢复链路不被上下文重构破坏。

**设计**

1. 所有新增 persisted fields 必须使用 additive evolution with safe defaults。
2. 旧 waiting-human session JSON 必须继续可读。
3. `resumeProject(...)` 必须能从旧 session 恢复。
4. `PostDraftReviewAgentStatusService` 在 persisted-load 场景下必须继续可用。
5. `scripts/review-resume.ps1` 背后的恢复链路必须保持可用。
6. `questionForHuman` 新字段加入后，旧 JSON 缺该字段时必须安全默认。
7. resume 后不做无条件 reset。
8. 采用条件化 reset / post-human local retry budget 规则：
   - 若人工输入明确改变了判断前提、命名结论、译法选择或语义解释，则允许重置 `revisionAttempts` / `selfCheckFailures`
   - 若人工输入未改变问题前提，则不得无条件清空累计计数；应只给予有限的 post-human local retry budget
9. 允许保留诊断信息，例如：
   - `localRejectionReasons`
   - `processTrail`
   - `issueBacklog`

**文件**

- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewSession.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewBoundaryWindow.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/HumanReviewRequest.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentStatusService.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewProjectStatusView.java`
- Modify: `src/main/java/io/quillloom/interfaces/api/dto/PostDraftReviewProjectStatusResponse.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/FileReviewSessionStore.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/FileReviewSessionStoreTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentStatusServiceTest.java`
- Test: `src/test/java/io/quillloom/interfaces/runner/PostDraftReviewAgentCommandLineRunnerTest.java`

### B3. 把 anchor 和已读 chunk 正文写入 workingSetContext `[代码已完成]`

**目标**

让 anchor 与相邻读取结果进入同一个高保真 workingSet 正文通道，不再只作为 evidence summary 字符串存在。

**设计**

1. seed anchor 直接写入 `workingSetContext`。
2. `read_previous_chunks` / `read_next_chunks` / `expand_block_context` 读入的 chunk 都写入 `workingSetContext`。
3. `ReviewEvidenceBundle` 中可保留简短兼容提示，但不再承担全文上下文职责。
4. 同一 chunk 重复读入时，workingSetContext 去重，并以后写覆盖前写。

### B4. 阻止 compact 误伤正文上下文 `[代码已完成]`

**目标**

只 compact transcript 与 evidence summary，不 compact `workingSetContext`。

**设计**

1. `TranscriptStore` 允许 compact。
2. `ReviewEvidenceBundle` 允许 compact。
3. `ReviewWorkingSetContext` 不参与 compact。
4. compact summary 不得伪装成正文上下文替代品。

### B5. 重写 system + investigation prompt `[代码已完成]`

**目标**

让 next-step 决策围绕产品定位、动作树、workingSet 边界语义组织，而不是围绕限制清单组织。

**设计**

1. system prompt 固定产品定位：文学翻译审校专家。
2. investigation prompt 固定注入顺序：
   - `[Current Facts]`
   - `[Product Role And Core Responsibilities]`
   - `[Action Tree]`
   - `[Working Set Text Context]`
   - `[State Memory]`
3. 动作树必须明确：
   - 什么时候优先读邻接 chunk
   - 邻接读取扩的是 `workingSet` 边界，不是 `focusChunk`
   - 什么时候先查译名表，再决定是否登记
   - 什么时候证据足够可以提交 `focusChunk`
4. 普通 investigation 困难、repair/retry 噪音、参数错误不能直接转人工。
5. repair prompt 兼容性必须作为明确回归项保留。
6. prompt不能乱码。

**文件**

- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`

### B5A. 转人工请求硬化与 `questionForHuman` `[代码已完成]`

**目标**

提高普通困难场景下的转人工门槛，同时保留 `self_check_budget_exhausted -> request_human_review` 作为明确出口，并要求每次人工请求都带可见的问题文本。

**设计**

1. `request_human_review` 不改工具参数，外部 tool protocol 保持不变。
2. `HumanReviewRequest` 增加 `questionForHuman`。
3. `ReviewToolExecutor.buildHumanRequest(...)` 使用模板生成问题。
4. 模板优先覆盖：
   - 二选一语义判断
   - 命名 / 术语确认
   - 衔接 / 指代关系确认
   - 翻译取舍是否允许
5. 如果无法生成足够具体的问题，不允许阻止转人工；必须退化为通用但可用的兜底问题，例如：
   - “当前 chunk 存在无法自动解决的语义判断，请审阅原文、当前译文与相关上下文，并给出应采用的译法或判断依据。”
6. 禁止空问题，禁止“请帮忙看看”式无信息问题。
7. `self_check_budget_exhausted` 继续保留为人工升级出口，但不允许把普通 repair/参数问题伪装成预算耗尽。
8. `HumanReviewRequest` 字段职责固定为：
   - `requestReason`：机器停机原因，面向系统和诊断
   - `requestNote`：诊断上下文摘要，面向开发与排障
   - `questionForHuman`：真正给人工看的问题文本，面向操作员
   - `resumeHint`：恢复操作提示，不是问题本体
9. waiting-human 状态暴露面必须同步扩展：
   - status 默认展示 `questionForHuman`
   - console 至少打印 `questionForHuman + requestReason + resumeHint`
   - persisted runtime JSON 中保留 `requestReason` / `requestNote` / `questionForHuman` / `resumeHint`
10. `questionForHuman` 必须沿以下链路完整透传：
   - `HumanReviewRequest`
   - `ProjectReviewRuntimeSession`
   - `ProjectReviewOutputAssembler`
   - `PostDraftReviewAgentResult`
11. status 外层 DTO 也必须透传该字段，不能只停留在内部 view/service。

**文件**

- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/HumanReviewRequest.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/FocusHumanStopPolicy.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ConsoleReviewRuntimeVisualizer.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentStatusService.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewProjectStatusView.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ProjectReviewOutputAssembler.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewAgentResult.java`
- Modify: `src/main/java/io/quillloom/interfaces/api/dto/PostDraftReviewProjectStatusResponse.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentStatusServiceTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ConsoleReviewRuntimeVisualizerTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`
- Test: `src/test/java/io/quillloom/interfaces/runner/PostDraftReviewAgentCommandLineRunnerTest.java`

### B6. 重写 evaluation prompt `[代码已完成]`

**目标**

让 continuity / reference / naming 判断优先消费裁剪后的正文上下文，而不是只靠 key/conflict/gap summaries。

**设计**

1. `Working Set Text Context` 成为主要判断输入。
2. `keyEvidence` / `conflictingEvidence` / `evidenceGaps` 降级为状态记忆补充区。
3. 注入裁剪规则固定为：
   - anchor 必注入
   - 与 continuity / reference / naming 判断直接相关的邻接或同 block snapshots 注入
   - 不做无脑全量塞入

**文件**

- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/EvaluationPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedStrategyEvaluationService.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

### B7. 重写 revision + self-check prompt `[代码已完成]`

**目标**

让修订与自检在需要时看到最小必要邻接上下文，而不是只看当前 chunk。

**设计**

1. `revision` 注入当前 chunk + 被 strategy rationale 明确依赖的邻接快照。
2. `self-check` 注入当前 chunk + continuity / confirmed-term consistency 所需的最小邻接快照。
3. 不允许直接把整个项目上下文带入。

**文件**

- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftRevisionService.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftRevisionServiceTest.java`

### B8. Phase B 总回归 `[代码已完成，但行为优化未结束]`

**必须覆盖**

1. workingSet 边界读取是否改为基于 `workingSet` 边界。
2. `workingSetContext` 是否与 `ReviewEvidenceBundle` 正确拆层。
3. `complete_working_set` 是否至少实现了“未读过的非 focus chunk 不能一起提交”。
4. persisted waiting-human session 是否继续可读、可 resume、可 status load。
5. resume 后 revision/self-check 计数器是否按条件化 reset / post-human local retry budget 规则工作。
6. `questionForHuman` 是否被持久化、暴露、打印。
7. prompt 重排后 repair prompt / dump 是否仍可诊断。
8. 外层 DTO / 最终输出链路是否继续透传 `questionForHuman`。
9. review 包内直接构造 session/request 的关键测试是否通过。

## 2026-04-22 Phase B 实施状态

1. 截至当前会话，B1-B8 对应的主要代码改动已经落地。
2. 上述“代码已完成”表示：
   - 对应模型、执行层、持久化兼容、prompt 重排、人工请求链路、prompt dump 诊断链路已经进入代码库
   - 不表示文学审校行为已经达到最终满意状态
3. 当前剩余主问题不是 `boundaryWindow`、marker、focus-only submit、持久化兼容这类结构项未落地，而是：
   - agent 在 investigation 阶段仍然不够主动地读取相邻上下文
   - 在 continuity / reference / handoff 依赖邻接文本的场景下，仍可能过早走向 `evaluate_focus` 或 `complete_working_set`
4. 因此，下一步优先级不是立即进入 Phase C，而是先补一轮 prompt 层行为补强并继续做定向验证。

## Phase B Follow-up：Prompt 层上下文阅读补强

### B9. investigation / system prompt 的邻接阅读优先级补强 `[待执行]`

**目标**

在不增加工具层强制拦截的前提下，仅通过 prompt 层补强，让 agent 在明显依赖上下文的文学审校场景中更主动调用 `read_previous_chunks` / `read_next_chunks`，减少过早 `evaluate_focus` / `complete_working_set`。

**设计**

1. 不在工具执行层新增“必须先读上下文”的硬拦截；本轮优先解决决策偏置，而不是用执行层兜底。
2. 在 `InvestigationPromptBuilder` 中把下列场景从“prefer read adjacent”提升为更强的工作规则：
   - 短 chunk / reply-like chunk
   - 承接句、动作延续句、转场句
   - 指代、说话人、动作归属依赖相邻文本
   - continuity / handoff / time-space shift 无法仅凭当前 anchor 判定
3. 明确告诉模型：
   - 文学翻译审校里，邻接阅读是正常工作流，不是证据不足时的异常补救
   - 未读取必要邻接文本时，不应直接判断 continuity 已经成立
4. 在 `ReviewAgentSystemPromptBuilder` 中补强反向约束：
   - 若 continuity / reference / handoff 明显依赖相邻 chunk，而当前 `workingSetContext` 尚未覆盖必要邻接文本，不要直接 `evaluate_focus` 或 `complete_working_set`
5. 明确区分：
   - `read_previous_chunks` / `read_next_chunks`：连续邻接验证主动作
   - `expand_block_context`：同 block 语义可见性补充，不替代邻接 continuity 验证
6. 在 investigation prompt 中显式暴露最小必要的客观边界状态，至少包括：
   - 当前 `boundaryWindow` 左/右边界 chunkId
   - `anchorOnlyView=true/false`
   - `hasPreviousRead=true/false`
   - `hasNextRead=true/false`
   - `adjacentReadCount`
   不输出“是否已经足以支持 continuity 判断”这类结论性布尔值；该判断仍由模型基于客观状态自行做出。
7. 只微调 `ReviewToolRegistry` 的 `nextStepGuidance` 文案，不改变工具集合或参数契约。
8. 本轮同时修复 `PromptBackedStrategyEvaluationService` 中 evaluation system prompt 的乱码问题；乱码 system prompt 会直接污染 `KEEP / LIGHT_EDIT / DEEP_EDIT / RETRANSLATE / REQUIRE_HUMAN_REVIEW` 的策略判断，因此必须与 B9 一并修复，不得后置。
9. `ReviewAgentSystemPromptBuilder` 与 `InvestigationPromptBuilder` 必须把下面这条写成硬规则，而不是模糊偏好：
   - 若当前 strategy 已经是 `LIGHT_EDIT` / `DEEP_EDIT` / `RETRANSLATE`，且当前 focus 尚未成功完成 `draft_revision` 并通过 self-check，则不得调用 `complete_working_set`
10. prompt 侧允许 `complete_working_set` 的唯一正信号必须写死为：
   - `selfCheckPassed=true`
   - 或等价的 `revision_ready_for_completion`
   仅有 `strategy=LIGHT_EDIT / DEEP_EDIT / RETRANSLATE` 本身绝不构成可提交依据。
11. 上述规则的目标不是在工具层强行拦截，而是防止模型出现“reason 承认需要修订、行动却直接 complete”的自相矛盾决策。
12. B9 还必须补齐 review-agent LLM transport failure containment 的统一收口规则：
   - transient 分类优先放在 transport / client 层完成
   - 非 transient 或漏识别的异常，统一在 runtime orchestrator 层收口为 `LLM_CALL_FAILED`
   - 该规则覆盖 next-step、evaluation、revision draft、revision self-check 全部 LLM 调用路径
13. B9 还必须补齐 review-agent LLM transport failure containment：
   - `GOAWAY received`、HTTP/2 连接关闭、同类 `IOException` 传输故障应被识别为 transient transport failure，并进入受控 retry
   - 即使某个 transport exception 未被正确识别为 transient，也不得直接把 Spring CLI 进程打挂；必须优先收口为 review runtime 的 `LLM_CALL_FAILED` / 可诊断停止状态
14. 上述 transport containment 的目标不是隐藏故障，而是把故障变成：
   - 可重试的受控瞬时失败，或
   - 可持久化、可恢复、可诊断的 runtime stopReason
   而不是未收口的顶层 `RuntimeException`

**文件**

- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedStrategyEvaluationService.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/RetryingReviewAgentStructuredGenerationPort.java`
- Modify: `src/main/java/io/quillloom/interfaces/runner/PostDraftReviewAgentCommandLineRunner.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedStrategyEvaluationServiceTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`
- Test: `src/test/java/io/quillloom/interfaces/runner/PostDraftReviewAgentCommandLineRunnerTest.java`
- Optional observation: 使用真实项目样本做 prompt dump spot-check，重点观察是否更倾向调用 `read_previous_chunks` / `read_next_chunks`

### B10. project completion state 暴露与 pending-empty 自动收尾 `[待执行]`

**目标**

解决 review-agent 在项目尾部的收尾问题：让 agent 明确知道还有多少 chunk 尚未处理；当 `pendingChunkIds` 已清空时，不再围绕旧 focus 或旧 completion 动作空转，而是进入项目结束路径。

**设计**

1. prompt 侧必须显式暴露最小 project completion state，至少包括：
   - `pendingChunkCount`
   - `completedChunkCount`
   - `currentFocusChunkStillPending=true/false`
2. 当 `pendingChunkCount=0` 时，system / investigation prompt 必须把下一步默认导向 `complete_project`，而不是继续围绕旧 focus 调 `complete_working_set`。
3. 若当前 focus 已不再属于 pending，它只能作为诊断上下文存在，不再构成新的 `complete_working_set` 提交目标。
4. 程序侧允许加入一个非常窄的 pending-empty 自动收尾自检：
   - `pendingChunkIds.isEmpty()` 只是自动收尾的必要条件，不是唯一条件
   - 自动收尾仅适用于 `ACTIVE` runtime 的 pending-empty 项目尾部
   - 若存在 unresolved blocking backlog，或 runtime 已处于 `WAITING_HUMAN / FAILED / NO_PROGRESS / LLM_CALL_FAILED / WALL_CLOCK_TIMEOUT`，则不得直接进入 completed
   - 只有在上述阻断条件不存在时，程序才可停止继续选 focus，也不再继续围绕旧 focus 执行 review loop，并直接进入 `completeProject()` 或等价的 project completion 路径
5. 该自动收尾只针对真正的 pending-empty 项目尾部，不得把普通 investigation / evaluation / revision 失败自动转成 completed。
6. 本轮不再引入额外复杂的 finalization 状态布尔值；程序本来就掌握 `pendingChunkIds / completedChunkOutcomes`。本轮重点是把已有 project-level completion state 稳定暴露给 prompt，并让 pending-empty 自动结束真正生效。
**文件**

- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ProjectReviewRuntimeSessionTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

## Phase C：跨 focus 审校摘要继承

### C1. 引入 `ProjectReviewContextMemory`

**目标**

为后续 focus 提供可继承的审校摘要，而不是继承旧全文快照。

**设计**

1. `ProjectReviewContextMemory` 只保留摘要，不保留旧 chunk 正文。
2. 可保留内容限定为：
   - established naming findings
   - established continuity findings
   - unresolved project risks
3. 不保存 transcript、局部 repair 噪音、旧 workingSet 正文快照。
4. 不新增专门的 LLM 记忆提炼调用。
5. 提炼固定为模板化提炼。
6. 提炼失败不阻塞 completion；可跳过本次记忆写入。

**文件**

- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewContextMemory.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/WorkingSetCompletionHandler.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewProcessSummaryAssembler.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewSessionModelTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

**落点约束**

1. `ProjectReviewContextMemory` 放在 review runtime model 内，不进入稳定领域对象。
2. 提炼时机固定为：focus 完成并提交后，从当前 focus session 模板化提炼进入 runtime memory。
3. 注入时机固定为：新 focus 的 investigation prompt 构建时读取 runtime memory。
4. 是否进入 persisted runtime JSON：默认进入，并遵守 additive evolution with safe defaults。

### C2. 最小 compact 规则

1. 每类 summary 列表默认只保留最近 `N` 条，推荐 `N=12`。
2. 超出部分按 FIFO 丢弃。
3. prompt 注入时允许在此基础上再做二次裁剪。
4. 不引入复杂评分系统。

### C3. 注入策略

1. 新 focus 的 investigation prompt 可注入 `[Project Review Memory]` 区块。
2. 该区块只承载摘要型结论，不直接污染当前 `workingSetContext`。

## 兼容性与不回归要求

### 脚本与运行入口

1. 以下脚本的调用方式必须保持可用：
   - `scripts/review-start.ps1`
   - `scripts/review-resume.ps1`
   - `scripts/review-create-baseline.ps1`
   - `scripts/review-reset-from-baseline.ps1`
2. 不允许因为内部重构而改变 CLI action 名称、CLI 参数名或恢复入口契约。

### Retry / Repair 机制

1. 本计划允许 prompt 内容改变，但不允许移除或绕过现有 retry / repair framework。
2. 不改 `ReviewAgentStructuredGenerationPort` 外部契约。
3. 不改变 `RetryingReviewAgentStructuredGenerationPort` 的挂载位置。
4. 不改变 `PromptBackedNextStepDecisionProvider` 的 bounded repair-loop 主结构。

### 日志、prompt dump、visualizer

1. 保留以下运行事件输出：
   - `projectStarted`
   - `focusSelected`
   - `toolCalled`
   - `toolCompleted`
   - `projectFinished`
2. 保留 dev prompt dump 能力。
3. 新增的 `questionForHuman` 必须进入 console 输出与 persisted status 可见面。
4. repair prompt dump 必须保持足够原始上下文，便于定位 prompt/context regressions。
5. working-set 正文上下文上线后，prompt dump 至少要能诊断：
   - 当前 workingSet chunkIds
   - 实际注入的 snapshot chunkIds
   - 注入来源类型（anchor / boundary expansion / block expansion）
   - 被裁剪掉的 snapshot 数量

## 执行顺序

1. B1：focus 内正文上下文与摘要记忆拆层。
2. B2：修正 workingSet 边界读取语义与 canonical order 落地。
3. B2A：加入 per-focus completion markers。
4. B2B：先落“已读硬约束”的多 chunk completion 执行层校验。
5. B2C：补 persistence/resume backward compatibility 与 resume counter reset。
6. B3：anchor 与已读 chunk 写入 workingSet text context。
7. B4：排除 text context 的 compact 误伤。
8. B5：system + investigation prompt 围绕产品定位与 workingSet 边界重排。
9. B5A：人工升级门槛、`questionForHuman`、状态暴露与日志打印硬化。
10. B6：evaluation prompt 重排。
11. B7：revision + self-check prompt 重排。
12. B8：Phase B 总回归。
13. B9：prompt 层上下文阅读补强与定向行为验证。
14. B10：project completion state 暴露与 pending-empty 自动收尾。
15. 只有在 B9 / B10 验证通过后，才进入 C1-C3：跨 focus 审校摘要继承与 compact 规则。

## 验证矩阵

### Phase B 必跑

1. `mvn -q "-Dtest=PostDraftReviewSessionModelTest,ReviewToolExecutorGuardrailTest,WorkingSetCompletionHandlerTest" test`
2. `mvn -q "-Dtest=FileReviewSessionStoreTest,PostDraftReviewAgentServiceTest,PostDraftReviewAgentStatusServiceTest" test`
3. `mvn -q "-Dtest=ReviewPromptBuilderTest,PromptBackedNextStepDecisionProviderTest,ConsoleReviewRuntimeVisualizerTest,PostDraftRevisionServiceTest" test`
4. 必做 prompt dump spot-check：
   - investigation prompt
   - repair prompt
   - final failure prompt
5. 必测人工恢复语义：
   - 人工输入改变问题前提时的条件化 reset
   - 人工输入未改变问题前提时的有限 post-human local retry budget
6. 必测 persisted runtime 兼容范围：
   - 旧 waiting-human JSON 可读
   - 旧 failed / no-progress / llm-call-failed / wall-clock-timeout JSON 可读
   - `PostDraftReviewAgentStatusService.loadStatus(...)` 对以上 persisted runtime status 仍可正常工作
7. 必测外层透传：
   - `questionForHuman` 经 `ProjectReviewOutputAssembler` 与状态 DTO 继续可见
8. 至少运行 review 包关键测试或等价全量编译级回归，覆盖直接构造 session/request 的变更面
9. B9 必测行为：
   - continuity / reply-like / transition 场景下，next-step 决策更倾向先读邻接 chunk
   - 在仅有 anchor 视野时，不再轻易直接 `evaluate_focus` / `complete_working_set`
   - `expand_block_context` 不会被 prompt 误当成邻接 continuity 验证的充分替代
   - evaluation system prompt 不再出现乱码
   - 当当前 strategy 已是 `LIGHT_EDIT` / `DEEP_EDIT` / `RETRANSLATE` 且尚无 `selfCheckPassed=true` / `revision_ready_for_completion` 时，next-step prompt 不再鼓励 `complete_working_set`
   - investigation prompt 输出的是客观邻接状态字段，而不是“是否足以判断 continuity”的代码端结论
   - `GOAWAY received` / 同类 HTTP2 `IOException` 被识别为 transient transport failure 或至少被收口为 `LLM_CALL_FAILED`
   - review-agent LLM transport failure 不再直接导致 Spring CLI 进程以未收口 `RuntimeException` 退出
10. B10 必测行为：
   - pendingChunkCount=0 时，prompt 明确进入 pending-empty project completion 语义
   - 当前 focus 已不再属于 pending 时，不再继续推动 complete_working_set
   - `pendingChunkIds.isEmpty()` 且 runtime 为 `ACTIVE`、且无 blocking backlog 时，程序可直接进入 `completeProject()` 或等价的 project completion 路径
   - pendingChunkCount=0 但存在 blocking backlog 或非 `ACTIVE` stop context 时，不得自动 complete_project
   - investigation / evaluation / revision 失败不得被误吞成 completed

## 风险

1. `ReviewEvidenceBundle` 语义收缩后，旧测试可能默认把它当全文上下文容器使用，需要逐个校正。
2. prompt 注入 workingSet 正文后，token 压力会上升，必须遵守裁剪规则。
3. file-backed session store 直接持久化完整 runtime，任何新增字段若缺少默认值，都会破坏人工恢复。
4. 第一版 `complete_working_set` 为避免误提交，仅允许提交 `focusChunk`；若后续要开放 non-focus chunk 一并提交，必须先补上可靠执行层 `verifiedInFocusChunkIds` 判定机制。
5. `boundaryWindow` 与 `workingSetContext` 必须严格分层；若实现时再次混用，会重新引入边界扩张与 block 扩张语义污染。

## 文档与交接

1. 重要设计结论继续同步到 `docs/handoff.md`。
2. 本计划修复为 UTF-8 干净文本后，后续只允许在此基础上增量维护，不再接受混入错误编码文本。

## Final Closure Notes

若本节与前文任何较早表述冲突，以本节为准。

### 1. `questionForHuman` 还必须跨越 writer / human gateway 边界

1. `questionForHuman` 不仅要在内部 model、status、console、DTO、final result 中保留。
2. 它还必须在以下边界中保持不丢失：
   - `PostDraftReviewAgentWriter.writeHumanRequired(...)`
   - `HumanInTheLoopGateway.submit(...)`
3. Phase B 文件范围补充为还需覆盖：
   - `src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentWriter.java`
   - `src/main/java/io/quillloom/application/postdraft/review/port/out/HumanInTheLoopGateway.java`
   - `src/main/java/io/quillloom/infrastructure/postdraft/review/PassThroughPostDraftReviewAgentWriter.java`
   - `src/main/java/io/quillloom/infrastructure/postdraft/review/InMemoryHumanInTheLoopGateway.java`
4. 对应验证要求补充为：
   - `questionForHuman` 经 `writeHumanRequired(...)` 后仍不丢失
   - `questionForHuman` 经 `HumanInTheLoopGateway.submit(...)` 后仍不丢失

### 2. `currentFocusRound` 必须纳入规则与回归

1. `currentFocusRound` 不是实现细节，必须保持稳定语义。
2. 默认规则锁定为：
   - 当前 focus 内，成功的调查工具调用可递增该值
   - focus 完成、focus 失败、focus 切换时归零
   - waiting-human -> resume 后默认保持原值
   - 只有在实现显式重建当前 focus 轮次语义时，才允许与条件化 reset 一起重置
3. Phase B 回归必须补充检查：
   - 相邻读取 / block 扩张后 `currentFocusRound` 的变化
   - focus 完成 / 失败 / 切换后的归零
   - waiting-human / resume 后的保持或条件化重建

### 3. smoke/debug 输出辅助纳入验证面

1. 除 status / DTO / final result 外，smoke/debug 输出中的人工请求文本也必须继续清晰可读。
2. 至少要保证 waiting-human 相关文本在以下辅助输出中不退化为旧语义：
   - smoke support 文本输出
   - debug / status 辅助渲染输出
3. Phase B 验证矩阵补充：
   - waiting-human 文本在 smoke/debug 输出中仍清晰可读

### 4. 实施时应视为附加测试要求

1. review 包关键测试或等价全量编译级回归仍必须执行，以覆盖直接构造 session/request 的变更面。
2. 若 `questionForHuman` 已进入状态和结果链路，则 writer/gateway 相关测试也必须进入回归集合。
3. 若 `currentFocusRound` 在实现中被触及，则必须补对应路径测试，不能只靠人工阅读代码确认。

## Final Tail Fixes

若本节与前文较早的文件清单或验证矩阵存在遗漏，以本节补充为准。

### 1. smoke/debug 文本输出辅助也纳入验证面

1. `questionForHuman` 不仅要出现在 status、console、result，也必须进入 smoke/debug 文本输出辅助。
2. 补充纳入验证范围的文件：
   - `src/test/java/io/quillloom/support/PostDraftReviewSmokeSupport.java`
3. 补充验证要求：
   - waiting-human 场景下，smoke/debug 文本输出必须显示 `questionForHuman`
   - 不能只保留旧的 `requestNote` / `resumeHint` 语义

### 2. `HumanInTheLoopGateway.submit(...)` 回写兼容点显式点名

1. `HumanInTheLoopGateway.submit(...)` 返回的新 `HumanReviewRequest` 也必须保留 `questionForHuman`。
2. 该兼容点必须在以下测试中显式覆盖：
   - `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`
3. 不允许测试替身在回写请求时静默丢掉新字段。

### 3. prompt dump 记录模型纳入修改面

1. 既然 prompt dump 目标已扩展到 working-set / snapshot / 裁剪诊断维度，则 dump 记录模型本身必须进入修改清单。
2. 补充纳入修改范围的文件：
   - `src/main/java/io/quillloom/application/postdraft/review/service/ReviewAgentPromptDumpWriter.java`
3. 补充纳入测试范围的文件：
   - `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
4. 新增最小断言要求：
   - dump 中可见实际注入的 snapshot chunkIds
   - dump 中可见注入来源类型
   - dump 中可见裁剪计数



