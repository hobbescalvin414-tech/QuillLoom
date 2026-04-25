# 当前状态

本文档记录截至当前代码状态的真实进展、已知问题与推荐顺序。

## 已完成

### 1. A / B 主链已稳定可跑
1. A 负责全书级 coarse block 规划。
2. B 负责 coarse block 内最终 chunk 切分与结构化标注。
3. 当前 chunk 大小用户基本认可，coarse block 粒度仍需校准。

### 2. C0 项目知识库已正式落地
1. C0 会沉淀 KnowledgeCard 与 CandidateTerm。
2. 已有 postgres 仓储实现。
3. 已有双阶段 LLM（Need Planner + Evidence Organizer）。
4. 已有内生实体卡分支。

### 3. D 主链已稳定
1. D 前全局命名阶段已接入（DraftStageGlobalGlossary + GlobalAliasConsistencyTable）。
2. D 初稿按两张表优先执行。
3. D revision 已按 issue 驱动，不再是自由润色。
4. 已确认术语正文合规检测（name-residue-warning、glossary-entry-not-applied、first-name-confirmation-missing）。

### 4. 持久化已部分完成
1. 已正式持久化：ProjectKnowledgeBase（postgres）、PostDraftReviewPackage（memory/postgres）。
2. Review Agent session 持久化：FileReviewSessionStore（JSON 文件），但 StoredReviewSession 丢失关键信息。
3. 尚未正式持久化：A/B/C0/D 的完整历史快照、通用全阶段 stage persistence。

### 5. Review Agent 已完成自主 agent 核心架构
1. 已从"最小单轮闭环"升级为真正自主 agent。
2. 核心架构：单 agent 内核 + 动态工具注册表 + guardrail 校验。
3. 已完成的能力：
   - 自主循环（while true + 13 工具 + LLM 决策 + guardrail 校验）
   - Focus anchor + working set 模型（可从单个 anchor 扩展到多 chunk）
   - 13 个工具全部可用（含向量检索 lookup_knowledge_cards）
   - TranscriptStore + EvidenceBundle 自动压缩
   - System Prompt 分离 + Per-tool JSON Schema + repair retry
   - 结构化输出 + LLM 格式容错
   - Console 可视化
   - 术语读取/写回工具（read_confirmed_terms + record_confirmed_terms）
   - Reader 缓存（ConcurrentHashMap + 装饰器）
4. 已通过 smoke test 验证（book-smoke-1776178359703）。

## 当前已确认的问题

### 1. Review Agent 修订译文不落库
`PassThroughPostDraftReviewAgentWriter` 只透传，不写库。agent 跑完修订后的译文只存在于方法返回值里，进程结束就没了。

### 2. HITL 是排障式而非求助式
当前 agent 卡死（NO_PROGRESS）才停机，人需要看日志诊断问题。应该是 agent 主动问人，人回答后自动继续（Codex 风格）。

### 3. 压缩摘要质量差
`buildCompactSummary()` 硬拼 4 个字段，丢失矛盾证据、guardrail 拒绝历史、工具调用统计、策略变化历史等。

### 4. Session 持久化丢失关键信息
`StoredReviewSession` 是精简快照，丢失 currentFocusSession、completedChunkOutcomes 完整内容、humanReviewRequest 等。

### 5. LLM 调用无重试/退避
429/503/网络超时直接崩整个 agent。

### 6. 工具系统耦合
ReviewToolExecutor 是 653 行 switch 表达式，新增工具需改 2-3 个文件（Executor、Registry、ContractValidator）。

### 7. coarse block 仍需再调
之前为了解决"过粗"问题，把 A 收得有点过头。当前 chunk 大小基本可接受，但 coarse block 还是偏细。

## 当前最推荐的下一步

详见 [差距分析文档](./superpowers/plans/2026-04-18-review-agent-e2e-run-gap-analysis.md)

最小可跑通路径：
1. **1.1 修订译文写回数据库** — 最关键，没有这个跑完也白跑
2. **1.3 Session 持久化可恢复** — HITL 的前提
3. **1.2 求助式 HITL** — 依赖 1.3，改变 HITL 设计方向
4. **2.5 Spring Bean 装配** — REST API 的前提

扩展性路径：
5. **2.1 工具系统解耦** — 后续扩展的基础
6. **2.2 结构化压缩摘要** — 提升压缩质量
7. **2.6 LLM 重试/退避** — 130 chunk 长跑不被限流搞崩

## 注意事项
1. 当前系统仍是受控流水线，不是自治 agent 社会。
2. 不要把 D 的 loop 扩成主检索层。
3. 不要把运行期临时状态塞回稳定领域契约。
4. HITL 必须是"求助式"而非"排障式"。
5. 工具系统需要解耦，加一个工具只需 1 个文件。
6. 压缩摘要需要结构化，不是硬拼 4 个字段。
