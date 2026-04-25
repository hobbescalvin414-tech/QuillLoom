# [历史归档] 2026-04-17 Review Agent Terms And Bridging

> 本文档为 2026-04-17 阶段性交接记录，当前状态请以 handoff.md 和方向锚定文档为准。

## 本轮新增
- review agent 新增 `read_confirmed_terms`
- review agent 新增 `record_confirmed_terms`
- 两个工具都只作用于 `post-draft review agent`

## 术语工具语义
- `read_confirmed_terms` 必须按 `sourceTerms` 查询项目内已确认译名
- 不允许把整张 confirmed terms 表直接回灌给模型
- `record_confirmed_terms` 只允许把本轮新确认的稳定译名写回项目资产
- 若同一 `sourceTerm` 已存在不同 confirmed term，显式拒绝并返回 `confirmed_term_conflict`

## 复用的持久化资产
- 复用 `PostDraftReviewPackage.termState.effectiveConfirmedTerms`
- 写回后同步刷新 `glossarySnapshot`
- 不新建独立术语表
- 不改前置受控流水线

## prompt 行为收紧
- `EvaluationPromptBuilder` 明确要求检查衔接、逻辑、自相矛盾、专名一致性
- `InvestigationPromptBuilder` 明确要求：
  - 短句/承接句优先读相邻 chunk
  - 出现专名时优先 `read_confirmed_terms`
  - 证据足够且项目未命中既有译名时，可 `record_confirmed_terms`
  - 未完成必要衔接/一致性检查前，不应过早 `complete_working_set`

## 已验证
- `mvn -q "-Dtest=OpenAiCompatibleReviewAgentStructuredGenerationClientTest,PromptBackedNextStepDecisionProviderTest,ReviewPromptBuilderTest,ReviewToolExecutorGuardrailTest,AutonomousProjectReviewAgentTest,PostDraftReviewAgentServiceTest,RepositoryBackedPostDraftReviewAgentReaderTest,RepositoryBackedPostDraftReviewAgentTermWriterTest,WorkingSetCompletionHandlerTest" test`

## 下一步建议
- 重新跑 `book-smoke-1776178359703`
- 重点观察：
  - 是否开始在涉及专名时调用 `read_confirmed_terms`
  - 是否在必要时调用 `record_confirmed_terms`
  - 是否不再一路 `KEEP + complete_working_set`
