# 本地检索与 RAG

## 1. 模块目标

本模块描述装配层与 D 如何从本地知识库中召回、排序并选择知识卡。

## 2. 当前边界

1. 装配层不联网，只做首批选卡。
2. D 不联网，只允许本地补卡。
3. D 的补卡不承担主检索职责。
4. C0 仍是主检索与建库入口。

## 3. 当前检索结构

统一底座已拆成三层：

1. `Recall`
2. `Rank`
3. `Select`

对应实现：

- `KeywordKnowledgeRecallService`
- `VectorKnowledgeRecallService`
- `HybridKnowledgeRanker`
- `KnowledgeSelectionService`
- `RuleBasedKnowledgeRetrievalService`

## 4. 当前检索策略

### 装配层首批选卡

偏高精度：

1. 更看重显式锚点命中
2. 更看重类型偏好
3. 允许向量召回补充，但不让语义近似压过明确锚点

### D 运行期补卡

偏高针对性：

1. 更看重缺口对齐
2. 更看重 `requestedTypes`
3. 向量召回权重更积极
4. 返回结果更少

## 5. 配置化策略

当前 `KnowledgeRetrievalProperties` 已按 use case 区分场景参数，例如：

1. `exactAnchorMatchWeight`
2. `preferredTypeWeight`
3. `vectorSimilarityScale`
4. `defaultLimit`
5. `defaultPerTypeLimit`

## 6. 当前不做的事

1. 不给 D 联网。
2. 不引入新的独立向量库体系。
3. 不把装配层扩成主检索层。
4. 不把运行期状态回写稳定对象。

## 7. 主要代码入口

- `src/main/java/io/quillloom/application/translation/service/RuleBasedKnowledgeRetrievalService.java`
- `src/main/java/io/quillloom/application/translation/service/KeywordKnowledgeRecallService.java`
- `src/main/java/io/quillloom/application/translation/service/VectorKnowledgeRecallService.java`
- `src/main/java/io/quillloom/application/translation/service/HybridKnowledgeRanker.java`
- `src/main/java/io/quillloom/application/translation/service/KnowledgeSelectionService.java`
- `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeRetrievalProperties.java`
