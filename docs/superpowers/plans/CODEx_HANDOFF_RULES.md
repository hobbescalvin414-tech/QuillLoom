# Codex 工作约束

> 本文件是给 Codex 的工作规则。Codex 在 QuillLoom 项目内执行任何任务前，必须遵守本文件所有条款。
>
> 本文件与方向锚定文档 `2026-04-18-review-agent-direction-anchor.md` 配套使用。两者冲突时，以方向锚定文档为准。

---

## 第一步：读核心文档

在 QuillLoom 项目内开始任何设计或代码工作之前，**必须**按以下步骤执行：

1. 用 `Read` 工具读取以下文件全文：
   - `docs/superpowers/plans/2026-04-18-review-agent-direction-anchor.md`
   - `docs/superpowers/plans/2026-04-18-review-agent-product-definition.md`
2. 在回复中，**用自己的话**复述以下三项：
   - Review Agent 的定位和核心功能（审校+精修+重译+收尾+衔接+逻辑检查）
   - 哪些决策已完成落地（D-01~D-06），哪些待实现（D-07~D-14）
   - 哪些红线仍然有效（R-06, R-09~R-14）
3. 只有复述准确的，才可以开始设计工作
4. **如果复述有误，用户会指出，用户指出后必须重新复述，不得擅自继续**

---

## 绝对禁止的行为

以下行为在任何阶段都是禁止的。Codex 每次输出代码前必须自检，发现任何一条红线立即停止并报告用户。

### 仍有效的红线

| 红线编号 | 禁止行为 | 验证方法 |
|---------|---------|---------|
| R-06 | 把运行期状态写回 PostDraftReviewPackage 或 ProjectKnowledgeBase | 代码审查检查写操作路径 |
| R-09 | 把 HITL 做成排障式（agent 卡死等人排障，人诊断后手动恢复） | 代码审查：NO_PROGRESS 不直接 FAILED；HumanInTheLoopGateway.submit 返回人的回答 |
| R-10 | NO_PROGRESS 直接标记 FAILED 而不请求人工帮助 | 代码审查：failNoProgress 走 request_human_review 路径 |
| R-11 | 继续往 ReviewToolExecutor 加 switch case 而不先做工具解耦 | 代码审查：新工具必须实现 ReviewTool 接口 |
| R-12 | 压缩摘要硬拼 4 个字段 | 代码审查：buildCompactSummary 从 session 各记忆项提取信息 |
| R-13 | 让 LLM 自由访问网络（绕过受控工具接口的联网搜索） | 代码审查：联网搜索必须通过 external_search 工具 + ExternalSearchPort + guardrail |
| R-14 | 把 loop 临时状态塞回 TranslationTaskInput 或其他稳定执行输入契约 | 代码审查：TranslationTaskInput 不承载巨型运行态 |

### 已消除的红线（归档备查，不得重新引入）

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

## 工作流程：设计先行

### 阶段一：出设计稿（不动代码）

1. Codex 根据方向锚定文档，出详细设计稿
2. 设计稿必须包含：
   - 组件拆解（哪些新文件/类/接口）
   - 数据流（输入 → 处理 → 输出）
   - 与现有保留资产的对接方式
   - 红线自检结果（每条有效红线逐一说明是否触碰及原因）
3. **设计稿必须用 Write 工具写到 `docs/superpowers/plans/` 下**，不能只存在于 Codex 的输出里
4. 用户确认设计稿之前，**不得开始写代码**

### 阶段二：实现（仅在设计稿被确认后）

1. 按设计稿实现代码
2. 每实现一个组件，逐一执行红线自检
3. 如果实现过程中发现设计稿有问题，立即停下来报告用户，不擅自修改设计

### 阶段三：验收

1. Codex 自检所有有效红线（用 `grep` 工具验证 R-06, R-09~R-14）
2. 运行 `mvn test -pl . -Dtest="io.quillloom.application.postdraft.review.*"` 确保测试通过
3. 自检全部通过后，报告用户"可验收"，列出每条红线的验证结果和测试结果
4. 用户进行人工抽查

---

## 遇到不确定时的处理原则

如果 Codex 在实现过程中遇到方向锚定文档没有明确说明的情况：

1. **停下来**，不要猜
2. 用 AskUserQuestion 工具向用户提问
3. 等用户明确答复后再继续
4. **绝对不能用"我觉得这样也行"的方式自己决定**

常见不确定场景的处理：

| 场景 | 处理方式 |
|------|---------|
| 方向锚定文档说"待设计"，但没有给具体方案 | 停下来问用户，不是自己随便选一个 |
| 发现方向锚定文档描述与代码实际不符 | 停下来报告用户，等用户更新文档后再继续 |
| 某条红线的验证结果不明确 | 停下来报告用户，不自行判断是否触碰红线 |
| 想加一个方向锚定文档没有提到的组件/机制 | 停下来问用户，不擅自添加 |

---

## 文件命名规范

- 设计稿：`docs/superpowers/plans/{日期}-{具体主题}-design.md`
- 验收报告：`docs/superpowers/plans/{日期}-{组件名}-verification.md`

---

## 参考文档

- 方向锚定：`docs/superpowers/plans/2026-04-18-review-agent-direction-anchor.md`
- 产品定义：`docs/superpowers/plans/2026-04-18-review-agent-product-definition.md`
- 差距分析：`docs/superpowers/plans/2026-04-18-review-agent-e2e-run-gap-analysis.md`
- 记忆体系：方向锚定文档 §6
- 架构边界：方向锚定文档 §4
