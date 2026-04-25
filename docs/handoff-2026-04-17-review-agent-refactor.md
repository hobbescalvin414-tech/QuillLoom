# [历史归档] 2026-04-17 Review Agent 重构补充交接

> 本文档为 2026-04-17 阶段性交接记录，当前状态请以 handoff.md 和方向锚定文档为准。

## 范围
- 仅处理 `post-draft review agent`
- 未修改前置受控流水线

## 本轮关键进展
1. `PostDraftReviewSession` 已改成以事实、证据、诊断为核心，`FocusReviewDiagnostics` 成为显式诊断面。
2. `ProjectReviewRuntimeSession` 已收敛到项目级状态：
   - `ACTIVE`
   - `WAITING_HUMAN`
   - `COMPLETED`
   - `FAILED`
3. runtime 明确恢复 `selectedFocusChunkId`，不再靠阶段状态偷表示“已选焦点”。
4. `complete_working_set` 不再静默补 `chunkIds`，重复同类 rejection 会显式停在 `NO_PROGRESS`。
5. `complete_project` 已保留为显式完成声明工具，最后一跳不再由 runtime 静默收口。

## 本轮新增收敛
1. `complete_working_set` 的语义已明确成：
   - 提交当前 anchor 轮次下确认完成的 `chunkIds`
   - `chunkIds` 必须包含当前 `anchorChunkId`
   - `chunkIds` 只能来自当前 `workingSet`
2. `WorkingSetCompletionHandler` 已把上面三条规则做成硬校验。
3. `ReviewToolExecutor` 会把违反提交范围的调用显式拒绝成可诊断错误，而不是吞掉。
4. `InvestigationPromptBuilder` 与 `PromptBackedNextStepDecisionProvider` 已把 anchor / workingSet / confirmed chunkIds 的关系直接写给模型。
5. `OpenAiCompatibleReviewAgentStructuredGenerationClient` 已从宽松 investigation schema 改成显式声明：
   - `arguments.count`
   - `arguments.chunkIds`
   - `arguments.reason`
   - `arguments.finalTranslations`
6. client 现在会在 structured result 返回后立刻做契约校验：
   - 如果选择 `complete_working_set` 但缺 `chunkIds`
   - 或选择 `read_previous_chunks/read_next_chunks` 但缺 `count`
   - 会直接抛出 `invalid structured tool decision`

## 已确认的真实 blocker 演化
1. 早期 blocker：
   - soft rejection 连发后转 `WAITING_HUMAN`
2. 中期 blocker：
   - `complete_working_set` 持续缺 `chunkIds`
   - 最终 `FAILED + NO_PROGRESS`
3. 最新收敛：
   - 模型在自然语言 `reason` 里已经知道要提交 `chunk-1`
   - 但结构化 JSON 的 `arguments.chunkIds` 仍可能丢失
   - 这说明问题已从“语义不清”收敛到“structured output 通道太松”

## 已验证
- `mvn -q "-Dtest=WorkingSetCompletionHandlerTest,ReviewToolExecutorGuardrailTest,PromptBackedNextStepDecisionProviderTest,ReviewPromptBuilderTest" test`
- `mvn -q "-Dtest=AutonomousProjectReviewAgentTest,PostDraftReviewAgentServiceTest,PostDraftProjectRuntimeSessionModelTest,PostDraftReviewProcessSummaryAssemblerTest,PostDraftReviewSessionModelTest,PostDraftReviewSessionFactoryTest" test`
- `mvn -q "-Dtest=OpenAiCompatibleReviewAgentStructuredGenerationClientTest" test`
- `mvn -q "-Dtest=OpenAiCompatibleReviewAgentStructuredGenerationClientTest,PromptBackedNextStepDecisionProviderTest,ReviewPromptBuilderTest,ReviewToolExecutorGuardrailTest,AutonomousProjectReviewAgentTest,PostDraftReviewAgentServiceTest" test`

## 仍待验证
- 尚未用新 client/schema 再跑真实项目 smoke
- 目标项目：`book-smoke-1776178359703`
- 重点确认：
  - 是否不再出现 `complete_working_set -> missing_argument:chunkIds`
  - 是否越过 `chunk-1`
  - 是否在最后一个 pending chunk 完成后显式调用 `complete_project`

## 文档阻塞
- `docs/handoff.md` 当前文件含无效 UTF-8 字节，`apply_patch` 无法安全更新
- 这轮继续先写补充交接文档，后续若要并回主 handoff，需先清编码
