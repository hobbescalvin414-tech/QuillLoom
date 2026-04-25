# 2026-04-17 Review Agent Smoke Blockers

## 背景

在真实项目 `projectId=book-smoke-1776178359703` 上运行 `PostDraftProjectReviewAgentSmokeTest` 时，
自主 agent 已不再因为 soft rejection 自动进入 `WAITING_HUMAN`，但仍存在新的本地死循环。

当前最新卡点不是 `draft_revision`，而是 `complete_working_set`。

## 现象

终端可视化显示：

1. agent 在 `chunk-1` 上直接判断 `strategy=KEEP`，并尝试 `complete_working_set`
2. 但它没有提供 `chunkIds`
3. guardrail 连续返回 `missing_argument:chunkIds`
4. agent 虽然读到了 rejection，但依旧持续调用无参的 `complete_working_set`
5. 由于已取消 soft rejection 自动转人工，这次表现为“本地无限兜圈”

典型日志片段：

```text
[review-agent] event=tool_called ... tool=complete_working_set ...
[review-agent] event=tool_completed ... status=rejected summary=missing_argument:chunkIds ...
```

## 已确认结论

1. 这不是人工边界问题
   - 不应转 `WAITING_HUMAN`
   - 应视为 agent 的本地规划/参数成形失败

2. 当前 prompt 级提示“`complete_working_set` 必须提供 `chunkIds`”还不够强
   - 模型已经知道缺参
   - 但仍未稳定产出 `arguments.chunkIds`

3. 当前 structured output 契约过于宽松
   - `ReviewToolDecision` 只要求 `toolName / arguments / reason`
   - 没有对不同工具施加更强的参数成形约束

4. 当前最优先问题已经从“是否转人工”切换为“如何让 agent 正确成形完成类工具参数”

## 下个会话建议先修的方向

### 方向 1：为 `complete_working_set` 增加执行层参数默认补全

建议：

1. 当 tool 为 `complete_working_set`
2. 且 `arguments.chunkIds` 缺失
3. 但当前 `workingSet` 非空
4. 则由执行层显式补成当前 `workingSet.chunkIds()`

理由：

- 这是确定性信息，不需要模型发明
- 能显著降低“完成动作缺少显然参数”的脆弱性
- 不属于 silent fallback，而是对完成工具进行受控参数归一化

边界：

- 只建议对 `complete_working_set` 做
- 不要推广到所有工具
- `read_previous_chunks/read_next_chunks` 的 `count` 仍不宜自动猜

### 方向 2：把 `complete_working_set` 做成更强的 prompt 契约

建议：

1. 在 Investigation prompt 中增加明确规则：
   - 当当前 working set 为 `[chunk-1, chunk-2]` 时
   - 若选择 `complete_working_set`
   - 默认应输出 `"chunkIds": ["chunk-1", "chunk-2"]`

2. 给一条明确 few-shot 风格示例：

```json
{
  "toolName": "complete_working_set",
  "arguments": {
    "chunkIds": ["chunk-1"]
  },
  "reason": "当前 working set 已可收口"
}
```

### 方向 3：把“工具参数契约”从文本提示升级到更强的结构约束

这是中期方向，不一定要在下一会话一次做完：

1. 为完成类工具单独建立 typed decision schema
2. 或在 generation client 侧根据 tool definition 做二次参数校验/修复式重试

## 下个会话最小修复目标

先做到以下两点即可：

1. `complete_working_set` 在真实 smoke 中不再因为缺少 `chunkIds` 本地死循环
2. agent 至少能正式完成 `chunk-1`，而不是一直停在第一块

## 建议的下一步验证

修完后优先重跑：

```powershell
mvn -q "-Dmaven.multiModuleProjectDirectory=E:\projects\QuillLoom\.worktrees\direction-c-autonomous-refactor" `
  -s "E:\projects\QuillLoom\.worktrees\direction-c-autonomous-refactor\.mvn\settings.xml" `
  "-Dtest=PostDraftProjectReviewAgentSmokeTest" `
  "-Dquillloom.test.post-draft-project-review-smoke.enabled=true" `
  "-Dquillloom.test.post-draft-project-review-smoke.project-id=book-smoke-1776178359703" `
  "-Dquillloom.postdraft.review.llm.enabled=true" `
  "-Dquillloom.postdraft.review.llm.base-url=<baseUrl>" `
  "-Dquillloom.postdraft.review.llm.api-key=<apiKey>" `
  "-Dquillloom.postdraft.review.llm.model-name=<modelName>" `
  test
```

优先观察：

1. `tool=complete_working_set` 是否带上 `chunkIds`
2. 是否出现第一条 `chunk_completed`
3. 是否越过 `chunk-1`
