# Codex 工作启动 Prompt

---

## 你的任务

继续推进 QuillLoom 的 post-draft review agent 开发。agent 已完成自主架构升级，当前需要补齐运行环境、解耦工具系统、实现求助式 HITL。

---

## 执行步骤

### 第一步：读取核心文档

立即读取以下文件并完整理解：

```
docs/superpowers/plans/2026-04-18-review-agent-direction-anchor.md
docs/superpowers/plans/2026-04-18-review-agent-product-definition.md
docs/superpowers/plans/CODEx_HANDOFF_RULES.md
```

用自己的话在回复中复述：
1. Review Agent 的定位是什么（审校+精修+重译+收尾+衔接+逻辑检查）
2. 哪些决策已完成落地（D-01~D-06），哪些待实现（D-07~D-14）
3. 哪些红线仍然有效（R-06, R-09~R-14）

**复述通过后，才开始工作。未通过前不动代码。**

---

## 当前状态

### 已完成

1. **自主 agent 内核**：`AutonomousProjectReviewAgent` 的 `while(true)` 循环，LLM 自主决定下一步动作
2. **ReviewToolRegistry**：注册了 13 个 `ReviewToolDefinition`（含描述、必填参数、参数 Schema）
3. **ReviewToolGuardrail**：校验工具名是否在注册表中、必填参数是否存在
4. **旧架构已消除**：`allowedActions`、`legacyFallback`、`ReviewAgentActionType`、`maxLoopRounds` 已全部消除
5. **HistoryLog 只做审计**：不进 prompt，只追加不压缩
6. **Focus anchor + working set**：可扩展到多 chunk
7. **结构化输出三层防御**：JSON Schema + repair retry + 运行时容错
8. **System Prompt 分离**：角色/规则在 system prompt，动态事实在 user prompt
9. **Per-tool 参数 Schema**：`ToolArgumentSchema` + system prompt/repair prompt 动态渲染
10. **TranscriptStore + EvidenceBundle 自动压缩**
11. **术语读取/写回**：`read_confirmed_terms` + `record_confirmed_terms`
12. **知识库向量检索**：`lookup_knowledge_cards`
13. **Console 可视化**：`ConsoleReviewRuntimeVisualizer`

### 未完成（按优先级）

| 优先级 | 编号 | 内容 | 说明 |
|--------|------|------|------|
| P0 | D-10 | 修订译文写回数据库 | `PassThroughPostDraftReviewAgentWriter` 只透传不写库，跑完也白跑 |
| P0 | D-11 | Session 持久化可恢复 | `StoredReviewSession` 丢失 `currentFocusSession` 等关键信息 |
| P0 | D-07 | 求助式 HITL | 当前 NO_PROGRESS 直接 FAILED，agent 不主动问人 |
| P1 | D-08 | 工具系统解耦 | `ReviewToolExecutor` 653 行 switch 表达式，新增工具需改 2-3 个文件 |
| P1 | D-09 | 结构化压缩摘要 | `buildCompactSummary()` 硬拼 4 个字段，丢失大量上下文 |
| P1 | D-14 | 受控联网搜索 | `external_search` 工具 + `ExternalSearchPort` + guardrail |
| P2 | D-12 | 崩溃恢复分阶段 | 开发期从头来，稳定期从上一焦点恢复 |
| P2 | D-13 | 流式输出架构预留 | 暂不做 SSE，但 `ReviewRuntimeVisualizer` 接口已预留 |

---

## 红线规则（禁止触碰）

每次输出代码前必须自检。发现任何一条红线立即停止并报告用户。

| 红线编号 | 禁止行为 | 验证方法 |
|---------|---------|---------|
| R-06 | 把运行期状态写回 PostDraftReviewPackage 或 ProjectKnowledgeBase | 代码审查检查写操作路径 |
| R-09 | 把 HITL 做成排障式（agent 卡死等人排障，人诊断后手动恢复） | 代码审查：NO_PROGRESS 不直接 FAILED；HumanInTheLoopGateway.submit 返回人的回答 |
| R-10 | NO_PROGRESS 直接标记 FAILED 而不请求人工帮助 | 代码审查：failNoProgress 走 request_human_review 路径 |
| R-11 | 继续往 ReviewToolExecutor 加 switch case 而不先做工具解耦 | 代码审查：新工具必须实现 ReviewTool 接口 |
| R-12 | 压缩摘要硬拼 4 个字段 | 代码审查：buildCompactSummary 从 session 各记忆项提取信息 |
| R-13 | 让 LLM 自由访问网络（绕过受控工具接口的联网搜索） | 代码审查：联网搜索必须通过 external_search 工具 + ExternalSearchPort + guardrail |
| R-14 | 把 loop 临时状态塞回 TranslationTaskInput 或其他稳定执行输入契约 | 代码审查：TranslationTaskInput 不承载巨型运行态 |

以下红线已在代码中消除，归档备查（不得重新引入）：

| 红线编号 | 禁止行为 | 当前状态 |
|---------|---------|---------|
| R-01 | 用 allowedActions 过滤 LLM 决策空间 | ✅ 已消除 |
| R-02 | 出现 legacyFallback 相关逻辑 | ✅ 已消除 |
| R-03 | Self-check 返回硬编码 true 或永远 passed | ✅ 已消除 |
| R-04 | 用 enum ReviewAgentActionType 作为动作生成唯一入口 | ✅ 已消除 |
| R-05 | 用 maxLoopRounds 或轮次硬编码作为 loop 中断机制 | ✅ 已消除 |
| R-07 | 在 Loop 控制层使用外部状态机驱动 agent 决策 | ✅ 已消除 |
| R-08 | 绕过 LLM 调用走手写启发式决策 | ✅ 已消除 |

---

## 工作流程

### 阶段一：出设计稿（不动代码）

1. 根据方向锚定文档出详细设计稿
2. 设计稿必须包含：
   - 组件拆解（哪些新文件/类/接口）
   - 数据流（输入 → 处理 → 输出）
   - 与现有保留资产的对接方式
   - 红线自检结果（每条红线逐一说明是否触碰及原因）
3. **设计稿用 Write 工具写到 `docs/superpowers/plans/` 下**，格式：`{日期}-{主题}-design.md`
4. **用户确认设计稿之前，不写代码**

### 阶段二：实现（设计确认后才动）

1. 按设计稿实现代码
2. 每实现一个组件，执行红线自检
3. 实现过程中发现设计稿有问题，立即停下报告用户，不擅自修改设计

### 阶段三：验收

1. 用 `grep` 工具验证所有有效红线（R-06, R-09~R-14）
2. 报告每条红线的验证结果
3. 运行 `mvn test -pl . -Dtest="io.quillloom.application.postdraft.review.*"` 确保测试通过
4. 用户进行人工抽查

---

## 遇到不确定时的处理

1. **停下来**，不要猜
2. 用 AskUserQuestion 向用户提问
3. 等用户明确答复后再继续
4. 禁止用"我觉得这样也行"自行决定

---

## 关键架构约束

1. **不回退大 orchestrator**：仍是单一 agent，不引入多 agent 协调层
2. **不回退 A/B/C0**：Review Agent 只读 `PostDraftReviewPackage` 和 `ProjectKnowledgeBase`
3. **联网搜索必须受控**：通过 `external_search` 工具 + `ExternalSearchPort` + guardrail + capability policy
4. **不把运行期状态塞回稳定领域对象**：`record_confirmed_terms` 是受控写回，不是自由写库
5. **HITL 必须是求助式**：agent 主动问人，人回答后自动继续，不需要外部 resume
6. **工具系统必须解耦**：新工具只需实现 `ReviewTool` 接口，自动注册、自动校验、自动执行
7. **压缩摘要必须结构化**：从 session 各记忆项提取信息，不硬拼 4 字段
8. **D 不联网**：D 的 loop 只做本地知识库补卡，不承担主检索

---

## 参考文件路径

- 方向锚定：`docs/superpowers/plans/2026-04-18-review-agent-direction-anchor.md`
- 产品定义：`docs/superpowers/plans/2026-04-18-review-agent-product-definition.md`
- 差距分析：`docs/superpowers/plans/2026-04-18-review-agent-e2e-run-gap-analysis.md`
- 加固计划：`docs/superpowers/plans/2026-04-18-review-agent-e2e-hardening-plan.md`
- 工作规则：`docs/superpowers/plans/CODEx_HANDOFF_RULES.md`
- claw-code 参考：`E:\learnAgent\cc\claw-code`

---

## 开始执行

读取方向锚定文档、产品定义和 CODEx_HANDOFF_RULES.md，然后用你自己的话复述：
1. Review Agent 的定位和核心功能
2. 哪些决策已完成（D-01~D-06），哪些待实现（D-07~D-14）
3. 哪些红线仍然有效

复述准确后，等待用户确认，然后开始工作。
