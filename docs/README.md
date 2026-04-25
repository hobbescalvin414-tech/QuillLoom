# QuillLoom 文档导航

本文档目录只承担一件事：告诉后续会话应该先看什么，以及每份文档分别回答什么问题。

## 建议阅读顺序

1. [handoff.md](./handoff.md) — 交接说明、当前状态、关键设计结论
2. [方向锚定文档](./superpowers/plans/2026-04-18-review-agent-direction-anchor.md) — **必读**，总领性方向文件
3. [current-architecture.md](./current-architecture.md) — 当前真实架构
4. [current-status.md](./current-status.md) — 当前进展与问题
5. [差距分析](./superpowers/plans/2026-04-18-review-agent-e2e-run-gap-analysis.md) — 下一步做什么
6. 按主题进入 `docs/modules/`

## 文档分层

### 方向锚定（必读）

- [Review Agent 方向锚定文档](./superpowers/plans/2026-04-18-review-agent-direction-anchor.md)
  - 总领性方向文件：定位、已完成决策、待实现决策、红线规则、记忆体系锚定
  - 所有后续设计、实现、重构必须在此文档约束下展开

- [Review Agent 产品定义](./superpowers/plans/2026-04-18-review-agent-product-definition.md)
  - 产品视角：解决什么问题、给谁用、核心功能、输入输出、能力边界、当前可用性

### 现状文档

- [handoff.md](./handoff.md)
  - 交接说明、Review Agent 当前真实状态、关键设计结论、文档索引
- [current-architecture.md](./current-architecture.md)
  - 当前真实架构、职责边界、稳定前提、Review Agent 架构
- [current-status.md](./current-status.md)
  - 当前已完成内容、已知问题、推荐推进顺序
- [run-and-debug.md](./run-and-debug.md)
  - 如何运行、如何阅读 `run-output`、如何排障

### Review Agent 计划与报告

- [端到端加固计划](./superpowers/plans/2026-04-18-review-agent-e2e-hardening-plan.md)
  - 8 个内部健壮性问题，5 个已完成，1 个半做（HITL 方向已变更为求助式），2 个未做
- [端到端加固实施报告](./superpowers/plans/2026-04-18-review-agent-e2e-hardening-impl-report.md)
  - 已完成工作的详细记录
- [完整跑通差距分析](./superpowers/plans/2026-04-18-review-agent-e2e-run-gap-analysis.md)
  - 下一步要做的事：修订译文写回、求助式 HITL、Session 持久化、工具解耦等

### 方向 C 设计文档（历史参考，已被 04-18 方向锚定取代）

> ⚠️ 以下三份文档为方向 C 的原始设计稿，其中部分技术方案已全部消除。当前以 04-18 方向锚定文档为准。

- [方向 C 锚定文档](./superpowers/plans/2026-04-16-direction-c-true-autonomous-agent-anchor.md)
  - 自主 agent 的原始目标和红线规则（已被 04-18 方向锚定取代）
- [方向 C 详细设计稿](./superpowers/plans/2026-04-16-direction-c-true-autonomous-agent-design.md)
  - 组件拆解、数据流（部分已过时）
- [方向 C 实施计划](./superpowers/plans/2026-04-16-direction-c-true-autonomous-agent-refactor-plan.md)
  - 分步实施计划（部分已过时）

### Codex 工作规则

- [Codex 工作启动 Prompt](./superpowers/plans/CODEx_WORKSTART_PROMPT.md)
- [Codex 工作约束](./superpowers/plans/CODEx_HANDOFF_RULES.md)

### 模块文档

- [modules/chunking.md](./modules/chunking.md)
  - A / B 分块边界、段号切边界机制、相关代码入口
- [modules/c0-knowledge.md](./modules/c0-knowledge.md)
  - C0 知识增强主链、Need 规划、建卡与拒绝分支
- [modules/retrieval-and-rag.md](./modules/retrieval-and-rag.md)
  - 装配层与 D 的本地 RAG、混合检索、场景策略
- [modules/workflow-trace.md](./modules/workflow-trace.md)
  - trace 目录结构、机器产物与人读产物、日志阅读路径
- [modules/d-draft-chain-issues.md](./modules/d-draft-chain-issues.md)
  - D 初稿链路暴露出的正文污染、风格过载、名称漂移等问题记录
- [modules/name-consistency.md](./modules/name-consistency.md)
  - 当前人名、地名、称谓、专名一致性的真实实现链路

### 历史文档

- [history/README.md](./history/README.md)
  - 旧交接文档、阶段性计划、历史方案说明
- `superpowers/plans/` 中 04-08 到 04-15 的计划文档均为已完成的历史计划
- 标题带 `[OUTDATED]` 的文档已被后续方案取代，仅供参考

## 使用约定

1. 新会话先读"现状文档"，不要直接从旧 plan 开始。
2. "历史文档"只用于追溯设计演化，不作为当前真实状态的唯一依据。
3. 功能改造前，优先更新对应模块文档，再补历史记录或计划。
4. `superpowers/plans/` 中标题带 `[OUTDATED]` 的文档已被取代，不要按其内容做决策。
