# Workflow Trace

## 1. 目标

workflow trace 同时服务两类需求：

1. 机器诊断
2. 人工阅读

因此当前目录同时保留机器产物和人读产物。

## 2. 当前产物分层

### 机器产物

1. `00-manifest.json`
2. `01-events.ndjson`
3. 各阶段 `.json`
4. 旧的逐事件 `.txt`

### 人读产物

1. `00-run-overview.txt`
2. `30-annotations-readable.txt`
3. `40-c0-readable.txt`
4. `50-translation-input-readable.txt`
5. `60-draft-readable.txt`
6. `00-draft-overview.txt`

## 3. 推荐阅读顺序

1. 先看 `00-run-overview.txt`
2. 再按需要看 annotate、C0、translation-input、draft
3. 需要精确字段时，再回看 json/ndjson

## 4. 当前 readable 文件各自回答什么问题

### `30-annotations-readable.txt`

看：

1. chunk annotate 产出了什么
2. `entities / backgroundQuestions / translationRisks / keyExpressions` 是否合理

### `40-c0-readable.txt`

看：

1. 哪些 chunk 触发了知识搜索
2. planned needs 是什么
3. 通过 gate 的 Need 是什么
4. 建了哪些卡
5. 拒绝建卡的原因是什么

### `50-translation-input-readable.txt`

看：

1. 装配层给每个 chunk 选了哪些卡
2. confirmed terms、continuity notes 是否合理
3. 是否存在空卡输入或异常偏斜

### `60-draft-readable.txt`

看：

1. 每个 chunk 的译文结果
2. commentary 是否暴露真实知识缺口

## 5. 主要代码入口

- `src/main/java/io/quillloom/infrastructure/workflow/trace/WorkflowTraceArtifactWriter.java`
- `src/main/java/io/quillloom/infrastructure/workflow/trace/WorkflowReadableTraceRenderer.java`
- `src/main/java/io/quillloom/infrastructure/workflow/trace/WorkflowDraftArtifactWriter.java`
