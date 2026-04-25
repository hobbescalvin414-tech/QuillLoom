# Stage Persistence Design

## 目标

为 `A / B / C0 / D` 设计正式阶段持久化与恢复方案，支持：

- 同一本书的历史运行发现
- 用户选择从头开始，或基于某次历史完成态继续
- 只允许从完成阶段恢复
- 选择从某完成阶段继续时，后续阶段逻辑失效并重跑
- 知识卡按书隔离，不跨书共享

本设计只描述正式方案，不等于当前已实现状态。

## 存储选择

正式持久化统一使用 `postgres`。

原因：

- 当前知识库与向量检索已经在 `postgres` 侧落地
- 阶段产物更适合与知识库、运行记录放在同一套存储中
- 不引入 `mysql`，避免两套数据库与两套恢复逻辑

## 核心对象

### 1. project

现有项目概念，表示协作空间。

### 2. book_scope

表示“某个项目中的一本具体书”。

建议唯一键：

- `project_id`
- `source_text_hash`
- `source_language`
- `target_language`

作用：

- 同一本书的多次运行挂在同一个 `book_scope` 下
- 不同书天然隔离

### 3. workflow_run

表示一次完整的工作流运行。

建议字段：

- `id`
- `project_id`
- `book_scope_id`
- `status`：`RUNNING / COMPLETED / FAILED / ABANDONED`
- `started_at`
- `finished_at`
- `resume_from_stage`
- `resumed_from_run_id`
- `input_fingerprint`
- `prompt_version_fingerprint`
- `model_fingerprint`

### 4. stage_snapshot

表示某个阶段的“完成态快照”。

建议字段：

- `run_id`
- `stage`：`A / B / C0 / D`
- `status`：只允许 `COMPLETED`
- `schema_version`
- `payload_json`
- `artifact_hash`
- `created_at`

注意：

- 中断、失败、半成品不进入 `stage_snapshot`
- 失败信息只记到运行日志，不作为恢复基础

## 各阶段持久化内容

### A

持久化：

- `bookAnalysis`
- `globalConstraints`
- `globalRisks`
- `translationStrategyNotes`
- `coarseChunkPlan`

只有 A 全部完成才写 `A` 阶段快照。

### B

持久化：

- chunk segmentation 结果
- chunk annotation 结果
- `personAliasHints`

只有 B 全部完成才写 `B` 阶段快照。

### C0

持久化分两层：

1. `knowledge_card` 明细资产
2. `C0` 阶段快照，记录本次 run 实际产出的卡与候选项

只有 C0 全部完成才写 `C0` 阶段快照。

### D

持久化：

- chunk draft 结果
- 本次 run 的 confirmed terms / candidate updates 完成态
- draft compilation 结果

只有 D 全部完成才写 `D` 阶段快照。

## 恢复规则

工作流启动时：

1. 先根据当前输入计算 `book_scope`
2. 查询该 `book_scope` 下历史 `COMPLETED` 运行
3. 向用户展示可用恢复选项

用户可选：

- 从头开始
- 复用到某次历史运行的 `A` 完成态
- 复用到某次历史运行的 `B` 完成态
- 复用到某次历史运行的 `C0` 完成态
- 复用到某次历史运行的 `D` 完成态

限制：

- 只能展示该历史运行实际完成到的最高阶段
- 不能从未完成阶段恢复

## “清理后续阶段”的语义

当用户选择“基于某次历史运行，从 B 完成继续”时，语义应为：

- 新建一个新的 `workflow_run`
- 复用旧运行的 `A / B` 完成态
- 对新运行而言，`C0 / D` 视为失效并重新生成

不建议物理删除旧运行数据。

原因：

- 旧运行仍有诊断价值
- 方便对比两次 C0 / D 的差异
- 降低误删风险

所以这里的“清理”应实现为：

- 对新运行逻辑失效
- 不再作为当前 run 的有效后续状态

而不是直接删除旧数据

## 知识卡隔离

当前阶段知识卡必须严格按书隔离。

建议归属键至少包含：

- `project_id`
- `book_scope_id`

含义：

- 同一项目不同书，不共享知识卡
- 不同项目更不共享
- 当前不做跨书知识复用

## 降耦原则

正式持久化只挂在阶段边界，不侵入阶段内部算法。

必须遵守：

- 不直接持久化 `PreprocessDossier`、`TranslationTaskInput` 这类运行时对象作为正式恢复契约
- 使用 `AStageSnapshot / BStageSnapshot / C0StageSnapshot / DStageSnapshot` 一类稳定 DTO
- 每个 snapshot 带 `schema_version`
- 恢复层只认阶段，不认阶段内部子步骤

这样后续改 A/B/C0/D 内部实现时，只需调整阶段快照适配器，而不是整条恢复系统一起改。

## 失效条件

以下任一变化，都不应直接复用旧快照：

- 源文本变化
- 源/目标语言变化
- 关键 prompt 版本变化
- 模型变化且用户不接受复用
- snapshot schema 版本变化

## 错误处理

- 运行失败时保留 `workflow_run` 与 `run_event_log`
- 不生成 `COMPLETED stage_snapshot`
- 恢复选择列表只展示完成态，不展示失败残留
- 不做静默 fallback，不把半成品当作恢复基础

## 推荐实施顺序

1. 落 `book_scope / workflow_run / stage_snapshot`
2. 落 A/B/C0/D 完成态写入
3. 接入启动时历史发现与用户选择
4. 实现“从某完成阶段恢复并重跑后续阶段”
