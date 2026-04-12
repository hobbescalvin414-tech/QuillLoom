# 名称一致性链路说明

本文档说明当前项目对人名、地名、称谓、专名等“一致性内容”的真实实现链路。

## 当前链路

1. B 从 chunk 中产出 `entities` 与 `personAliasHints`。
2. C0 会把 `entities` 派生成 `CandidateTerm`，同时沉淀外部知识卡与内生人物卡。
3. 项目记忆通过 `confirmedTerms` 与 `candidateTermUpdates` 向后续 chunk 顺序传播。
4. 装配层把这些状态装入 `ExecutionContextView`。
5. D prompt 要求沿用已确认译名；未确认名称可以进入 `confirmedTermUpdates` 或 `candidateUpdates`。
6. validator 会阻止覆盖已有 `confirmedTerms`。

## 已新增的内生锚点

1. C0 已支持最小内生人物卡。
2. 内生人物卡的 `metadata` 当前至少包含：
   - `intrinsic`
   - `canonicalName`
   - `aliasState`
   - `confidence`
   - `firstSeenChunkId`
   - `aliases`
   - `surfaceForms`
3. alias 状态机当前分为：
   - `OBSERVED`
   - `SUSPECTED_ALIAS`
   - `CONFIRMED_ALIAS`

## 当前仍未完全解决的问题

1. 正文是否严格沿用 active glossary，还需要执行层校验。
2. 原文名与确认译名混用，还需要显式 issue 检测。
3. 首见命名的后续收敛，仍依赖 D 的输出质量和后续修订。
4. 当前 alias 仍是保守治理，不做跨 chunk 激进合并。

## 边界要求

1. 不把 B 的弱信号直接升级成项目级事实。
2. 不把 D 的局部判断直接回写成稳定实体归一结果。
3. 名称一致性优先落在：
   - 项目记忆传播
   - C0 内生锚点
   - validator 合规检测
   - revision round 定向修订
