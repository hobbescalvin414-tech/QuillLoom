# 当前架构

本文档只描述当前真实代码状态，不记录历史方案。

## 主链
1. Agent A 负责全书级分析与 coarse block 规划。
2. Agent B 在 coarse block 内做最终 chunk 切分与结构化标注。
3. Agent C0 基于 chunk 标注做知识增强，并沉淀项目级知识库。
4. 装配层为当前 chunk 选择首批知识卡，并组装 D 的稳定执行输入。
5. Agent D 执行 chunk 翻译；若知识不足，只允许做本地知识库补卡，不承担主检索职责。
6. 当前 workflow 仍会输出 trace 和草稿产物。
7. 当前已新增初稿后正式启动包：
   - `PostDraftReviewPackage`
8. 当前可按 `projectId` 联动加载：
   - `PostDraftReviewPackage`
   - `ProjectKnowledgeBase`
   以从初稿完成点继续后续 agent 开发与测试。

## Review Agent 架构

### 核心模型
1. 单 agent 内核 + 动态工具注册表 + guardrail 校验。
2. 外部入口：`PostDraftReviewAgentService.reviewProject(...)` 以 `projectId` 为锚点。
3. 项目级运行时状态只有四种：`ACTIVE / WAITING_HUMAN / COMPLETED / FAILED`。
4. Focus session 只承载事实、证据、轨迹与诊断，不承载阶段状态机。

### 记忆体系
1. **TranscriptStore**：保存 LLM 可见的对话上下文，参与 prompt 构建，可压缩。
2. **HistoryLog**：结构化事件日志，只做审计，不进 prompt，不压缩。
3. **ReviewEvidenceBundle**：结构化证据包（keyEvidenceSummaries、conflictingEvidenceSummaries、evidenceGaps、supportingEvidenceSummaries、contradictingEvidenceSummaries），可压缩。
4. **ReviewVisitedObjects**：已访问对象记录。
5. **ReviewToolTrace**：工具调用轨迹。
6. **FocusReviewDiagnostics**：循环/错误诊断（localRejectionReasons 等）。

### 工具体系
1. 13 个工具注册在 `ReviewToolRegistry`：
   - `read_previous_chunks` / `read_next_chunks` / `expand_block_context`
   - `read_decision_notes` / `read_transition_note`
   - `lookup_knowledge_cards`（含向量检索）
   - `read_confirmed_terms` / `record_confirmed_terms`
   - `evaluate_focus` / `draft_revision`
   - `request_human_review`
   - `complete_working_set` / `complete_project`
2. 每个工具有 `ToolArgumentSchema` 描述参数类型和含义。
3. `ReviewToolGuardrail` 做边界校验（工具名注册、必填参数校验）。连续同类拒绝检测由 `ReviewToolExecutor`（`NO_PROGRESS_REJECTION_THRESHOLD = 3`）+ `FocusHumanStopPolicy` 实现。
4. `ReviewToolExecutor` 执行工具并返回结果（当前是 653 行 switch 表达式，待解耦为 ReviewTool 接口）。

### 决策与输出
1. `PromptBackedNextStepDecisionProvider`：LLM 自主决定下一步工具调用。
2. `PromptBackedStrategyEvaluationService`：LLM 评估策略（KEEP/LIGHT_EDIT/HEAVY_EDIT/RETRANSLATE/REQUIRE_HUMAN_REVIEW）。
3. `PromptBackedRevisionDraftProvider`：LLM 生成修订稿。
4. `LlmBackedRevisionSelfCheckService`：LLM 自检修订稿。
5. 结构化输出 + repair retry + LLM 格式容错（三层防御）。

### Focus Anchor + Working Set
1. Agent 从 focus anchor 出发，可扩展到多个 chunk（working set）。
2. `complete_working_set` 允许一次提交多个 chunk 的完成确认。
3. 正式结果仍逐 chunk 产出 `ProjectChunkReviewOutcome`。

### HITL（当前为排障式，待改为求助式）
1. 当前：agent 卡死（NO_PROGRESS）才停机，需要外部 resume。
2. 目标：agent 主动问人，人回答后自动继续（Codex 风格）。
3. NO_PROGRESS 应走 request_human_review 路径而非直接 FAILED。

## 稳定边界
1. 不回退 A / B，不退回大 orchestrator。
2. C0 负责主检索和项目级知识沉淀。
3. 装配层只负责筛卡和组装输入，不负责联网检索。
4. D 不联网。Review Agent 联网搜索通过受控工具接口接入（待实现），不允许 LLM 自由访问网络。
5. `TranslationTaskInput` 仍是稳定执行输入契约，不承载巨型运行态。
6. 运行期临时状态不应塞回稳定领域对象。

## 名称一致性链路
1. D 前已新增"全局命名阶段"。
2. 当前会产出两张执行表：
   - `DraftStageGlobalGlossary`
   - `GlobalAliasConsistencyTable`
3. D 初稿按以下顺序执行：
   - 先 `hardEntries`
   - 再 `softEntries`
   - 再参考 alias 表
   - 仅对表外项写 `confirmedTermUpdates / candidateUpdates`
4. alias 当前只读消费，不允许由 D 回写。

## D 当前链路
1. D 仍是两轮执行：
   - 第 1 轮生成初稿和结构化结果
   - 第 2 轮按 issue 清单做 revision
2. 第 1 轮后的校验当前已覆盖：
   - `target-language-purity`
   - `text-boundary-warning`
   - `name-residue-warning`
   - `glossary-entry-not-applied`
   - `first-name-confirmation-missing`
3. 对尚未进入当前生效译名表的高频核心人名，D 现在要求：
   - 无论翻成中文还是保留原文
   - 都必须写入 `confirmedTermUpdates`

## 持久化现状
1. 已正式持久化：
   - `ProjectKnowledgeBase`
   - 其中包括 `KnowledgeCard` 和 `CandidateTerm`
   - 当前已有 `postgres` 仓储实现
2. 已新增正式初稿后启动包：
   - `PostDraftReviewPackage`
   - 当前已有独立 repository 接口
   - 当前已有 `memory` 与 `postgres` 仓储实现
   - `runDraftWorkflow(...)` 完成后会自动保存该启动包
3. 当前恢复方式：
   - 后续 agent 以 `projectId` 为锚点
   - 先加载 `PostDraftReviewPackage`
   - 再联动加载 `ProjectKnowledgeBase`
4. Review Agent session 持久化：
   - `FileReviewSessionStore`（JSON 文件）
   - `StoredReviewSession` 是精简快照，丢失关键信息（待修复）
5. 已落盘但不属于正式可恢复状态：
   - `run-output/workflow-trace`
   - `run-output/book-sample`
6. 尚未正式持久化：
   - A / B / C0 的完整历史快照
   - D revision 前后的完整中间态
   - 通用全阶段 stage persistence

## 当前缺口
1. Review Agent 修订译文不落库（PassThroughPostDraftReviewAgentWriter）。
2. HITL 是排障式而非求助式。
3. Session 持久化丢失关键信息。
4. LLM 调用无重试/退避。
5. 工具系统耦合（ReviewToolExecutor switch-case）。
6. 压缩摘要质量差（硬拼 4 个字段）。
7. 还没有正式的 stage persistence。
8. 若要把 `PostDraftReviewPackage` 真正落到数据库，需显式使用：
   - `quillloom.post-draft-review-package.storage=postgres`
