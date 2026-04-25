# C0 知识增强模块

## 1. 模块目标

C0 负责基于 chunk 标注做主检索、知识整理和项目级知识库沉淀。

## 2. 当前主链

当前主链是：

1. `LlmKnowledgeNeedPlanner`
2. `KnowledgeSearchGate`
3. `NetworkBackedKnowledgeSearchTool`
4. `KnowledgeSearchResultOrganizer`
5. `KnowledgeCardDraftNormalizer`
6. `ToolDrivenKnowledgeEnricher`

## 3. 当前设计重点

### Need 规划

当前 planner 已显式消费：

1. `entities`
2. `backgroundQuestions`
3. `translationRisks`
4. `keyExpressions`

`KnowledgeNeed` 当前包含：

1. `needKind`
2. `signalSource`
3. `coverageKey`
4. `searchIntent`

这意味着 C0 不再主要围绕人名与地名扩搜，而是允许：

1. 背景问题扩成历史/文化/制度类 Need
2. 翻译风险扩成称谓、语体、翻译策略类 Need
3. 关键表达扩成意象、固定表达、术语类 Need

### Gate

`KnowledgeSearchGate` 当前只做：

1. 预算裁剪
2. 覆盖拦截
3. 去重

它不再承担主语义判断，不负责决定“什么算真正的知识需求”。

### 拒绝分支

organizer 的“拒绝建卡”是正常业务分支：

1. 当前 Need 可以被跳过
2. trace 会记录拒绝信息
3. workflow 继续

## 4. 当前已知改进方向

1. 知识卡类型表达仍可继续细化。
2. 卡内容的原子化与治理仍需增强。
3. C0 与 D 的衔接已经改善，但检索命中解释仍可更透明。
4. 需要补一条 `书内证据驱动` 的内生知识卡支路，以覆盖虚构人物、虚构地点、书内设定。

相关计划：

- [docs/superpowers/plans/2026-04-11-c0-intrinsic-knowledge-cards.md](../superpowers/plans/2026-04-11-c0-intrinsic-knowledge-cards.md)

## 5. 主要代码入口

- `src/main/java/io/quillloom/infrastructure/preprocess/LlmKnowledgeNeedPlanner.java`
- `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeNeed.java`
- `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeSearchGate.java`
- `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeSearchResultOrganizer.java`
- `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeCardDraftNormalizer.java`
- `src/main/java/io/quillloom/infrastructure/preprocess/ToolDrivenKnowledgeEnricher.java`
