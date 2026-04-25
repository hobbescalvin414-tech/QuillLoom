# 分块模块

## 1. 模块目标

分块模块负责把全文拆成可消费的结构化单元，同时保留足够稳定的边界信息供后续流程使用。

## 2. 当前设计

### Agent A

1. 负责全书级粗分块。
2. 关注章节、场景、时间空间跃迁量级。
3. 输出 coarse block，而不是最终 chunk。

### Agent B

1. 负责 coarse block 内的细分块。
2. 生成 chunk summary 和结构化标注。
3. 普通正文 chunk 不宜过碎。
4. 标题、题词、诗歌、书信抬头、列表项等允许短 chunk 独立存在。

## 3. 当前关键实现前提

1. A / B 都已经切换到按段号切边界。
2. 当前更偏好段落边界，而不是长字符串锚点。
3. `ParagraphView` / `ParagraphSegment` 是统一基础设施。

## 4. 主要代码入口

- `src/main/java/io/quillloom/infrastructure/preprocess/ParagraphView.java`
- `src/main/java/io/quillloom/infrastructure/preprocess/ParagraphSegment.java`
- `src/main/java/io/quillloom/infrastructure/preprocess/coarsechunkplanning/CoarseChunkPlanningPromptRenderer.java`
- `src/main/java/io/quillloom/infrastructure/preprocess/coarsechunkplanning/CoarseChunkPlanningLlmResultNormalizer.java`
- `src/main/java/io/quillloom/infrastructure/preprocess/chunksegmentation/ChunkSegmentationPromptRenderer.java`
- `src/main/java/io/quillloom/infrastructure/preprocess/chunksegmentation/ChunkSegmentationPlanningLlmResultNormalizer.java`

## 5. 当前注意点

1. 不要把段号边界退回字符串锚点。
2. 不要为了追求整齐长度牺牲语义完整性。
3. A 与 B 的边界职责不要重新揉在一起。
