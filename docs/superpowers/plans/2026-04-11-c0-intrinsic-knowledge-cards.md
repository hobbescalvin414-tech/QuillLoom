# C0 内生知识卡设计计划

## 1. 背景

当前 C0 的正式知识卡主链是外部证据驱动：

1. `LlmKnowledgeNeedPlanner`
2. `KnowledgeSearchGate`
3. `NetworkBackedKnowledgeSearchTool`
4. `KnowledgeSearchResultOrganizer`
5. `KnowledgeCardDraftNormalizer`
6. `ToolDrivenKnowledgeEnricher`

这条链适合：

1. 真实世界背景
2. 历史背景
3. 文化背景
4. 礼仪、制度、典故、术语来源

但它不适合单独承担以下知识的建卡：

1. 虚构人物
2. 虚构地点
3. 虚构组织
4. 书内独有设定
5. 只在本书上下文中成立的关系和称谓

这些知识并不是“搜不到而已”，而是本来就更适合从文本内部沉淀。

## 2. 目标

新增一条 `C0 内生知识卡` 能力，使系统可以在不联网、不伪造外部证据的前提下，从 chunk 标注和书内文本中沉淀可复用知识卡。

目标不是替代外部搜索链，而是补齐另一类知识来源。

## 3. 设计原则

1. 保持当前职责边界不变。
2. 内生知识卡仍然属于 C0，不转移给装配层或 D。
3. D 仍然不联网。
4. 不把运行期临时状态塞回稳定领域对象。
5. 不允许因为搜不到外部证据，就静默编造“外部背景卡”。
6. 内生知识卡必须与外部证据卡在来源语义上区分清楚。

## 4. 要解决的真实问题

### 4.1 外部搜索无法覆盖虚构世界

例如：

1. 纯虚构人物
2. 小说内部才存在的地名
3. 架空组织与制度

这些对象即使对翻译非常重要，也可能完全无法通过联网搜索稳定获得。

### 4.2 目前“实体”主要落到 CandidateTerm，而不是正式知识卡

当前 `ToolDrivenKnowledgeEnricher` 会从 `entities` 生成 `CandidateTerm`，但这不足以支撑：

1. 人物关系理解
2. 设定连续性
3. 局部上下文复用
4. 装配层与 D 的本地 RAG

### 4.3 书内知识与外部知识没有显式来源分层

当前最终 `KnowledgeCard.sourceRefs` 更偏外部 URL，这对虚构对象并不适用。

## 5. 建议方案

建议在 C0 内补一条与“联网建卡”并行的 `书内证据建卡支路`。

### 5.1 总体结构

推荐结构：

1. `LlmKnowledgeNeedPlanner`
2. `KnowledgeSearchGate`
3. 分流：
   - 外部证据 Need -> `NetworkBackedKnowledgeSearchTool`
   - 内生 Need -> `IntrinsicKnowledgeCardPlanner` 或同类组件
4. 两路都产出可统一归一的中间草稿
5. 继续复用 `KnowledgeCardDraftNormalizer`
6. 继续复用 `KnowledgeCardMergeService`
7. 统一入 `ProjectKnowledgeBase`

核心思想：

`C0 仍然是唯一建库入口，但允许两种证据来源并行产卡。`

### 5.2 适合内生建卡的知识类型

第一阶段建议只支持：

1. `CHARACTER_PROFILE`
2. `SETTING_ENTRY`
3. `TERM_EXPLANATION`

原因：

1. 这三类卡当前本来就支持增量 merge
2. 它们最容易从书内实体和局部上下文稳定抽取
3. 风险比直接做文化/历史背景卡小

### 5.3 内生知识卡的来源

建议先只消费这些来源：

1. `entities`
2. chunk `summary`
3. chunk `sourceText`
4. 当前 chunk 前后局部 chunk 的 summary
5. `keyExpressions`

暂不直接做：

1. 从整本书回溯所有共现关系
2. 复杂人物图谱
3. 自动跨章设定归并

### 5.4 内生知识卡的最小内容要求

建议内生卡至少包含：

1. 该对象在当前 chunk 中的角色/指向
2. 当前已知的稳定称谓或别名
3. 适用 chunk 范围
4. 来源是“书内证据”，不是外部资料

内容要保守，不推断超出文本证据的事实。

## 6. 数据模型建议

### 6.1 建议新增“来源语义”字段

当前 `KnowledgeCard` 里缺少对“卡来自哪里”的显式表达。

建议新增类似字段：

1. `knowledgeOriginType`
   - `EXTERNAL_EVIDENCE`
   - `INTRINSIC_TEXTUAL`

或等价表达。

作用：

1. 区分外部背景卡与书内内生卡
2. 让 trace、调试、后续治理更清楚
3. 避免把虚构人物卡误装成外部知识卡

### 6.2 建议保留书内来源引用

对内生卡，建议保留：

1. chunk 引用
2. 触发该卡的实体或表达
3. 可选的局部摘录定位

不建议把这些继续塞进当前 `sourceRefs` 的 URL 语义里。

更合理的方向是：

1. 外部 URL 留在 `sourceRefs`
2. 书内来源进入单独字段，例如 `originRefs`

如果本轮不想扩领域模型，也至少要在中间 trace 中清晰保留。

## 7. 组件建议

建议新增但保持高内聚的组件：

1. `IntrinsicKnowledgeNeedClassifier`
   - 判断哪些 Need 更适合走书内建卡

2. `IntrinsicKnowledgeCardPlanner`
   - 基于 chunk 标注和局部文本生成内生知识草稿

3. `IntrinsicKnowledgeEvidence`
   - 如需要，可增加与外部 evidence 对称的中间对象

4. 复用：
   - `KnowledgeCardDraftNormalizer`
   - `KnowledgeCardMergeService`
   - `KnowledgeCardRetrievalTextBuilder`

不建议：

1. 把内生知识逻辑塞进装配层
2. 把 D 的补卡逻辑扩成主建库链
3. 在 `TranslationTaskInput` 里临时堆状态

## 8. 与当前链路的衔接建议

### 8.1 Need 层先分流，不要让搜索工具承担判断

建议在 Need 仍是 `KnowledgeNeed` 的阶段，先判断：

1. 这类需求是更适合外部证据
2. 还是更适合书内内生知识

不要等到搜索失败后再临时 fallback 到“书内建卡”。

原因：

1. 搜索失败不等于应该建内生卡
2. fallback 会掩盖真实边界
3. 这会让链路更难诊断

### 8.2 与 merge 策略保持一致

第一阶段内生卡优先限定在已有 merge 能力较成熟的类型：

1. `CHARACTER_PROFILE`
2. `SETTING_ENTRY`
3. `TERM_EXPLANATION`

这样能减少重复卡爆炸。

## 9. 建议分两期做

### 第一期：最小可用链路

目标：

1. 支持从 `entities + summary + sourceText` 生成内生人物卡/设定卡
2. 明确标记来源为书内证据
3. 入库后可被装配层和 D 检索到

不做：

1. 复杂关系抽取
2. 复杂图谱
3. 自动角色演化建模

### 第二期：增强治理

目标：

1. 更强的人物别名归并
2. 章节级/项目级的虚构设定连续性治理
3. 书内来源引用的更细粒度展示

## 10. 实施顺序建议

1. 先补设计与测试，不直接改主链
2. 明确内生卡范围与来源语义
3. 先做最小中间对象与单元测试
4. 再把内生支路接入 C0
5. 最后验证装配层和 D 的消费是否自然受益

## 11. 验证建议

至少补以下测试：

1. `虚构人物` 无外部结果时，仍能基于书内证据生成 `CHARACTER_PROFILE`
2. `虚构地点/组织` 能基于书内证据生成 `SETTING_ENTRY`
3. 内生卡不会误写外部 URL 型 `sourceRefs`
4. 内生卡能进入项目知识库并被装配层检索到
5. D 的运行期补卡可以命中内生卡
6. 同一人物/设定跨 chunk 出现时能增量 merge

## 12. 对后续会话的明确提醒

1. 不要把“搜不到外部结果”直接等价成“自动建内生卡”。
2. 不要把内生卡实现成静默 fallback。
3. 不要把内生知识链路塞进 D。
4. 不要在没有来源区分的情况下，把内生卡和外部卡混成同一种证据语义。
5. 第一阶段先做小而稳的 `人物 / 设定 / 术语`，不要一次性铺太大。
