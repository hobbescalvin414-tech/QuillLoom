# D 前全局命名阶段与审校输入包设计

日期：2026-04-13

## 1. 设计结论

本次设计推翻“首见专名由 D 临场定名”的思路，改为：

1. 在 D 开始翻译初稿之前，新增一个明确的“全局命名阶段”。
2. 这个阶段基于 C 现有产物，整理出两张表：
   - `DraftStageGlobalGlossary`
   - `GlobalAliasConsistencyTable`
3. D 初稿阶段严格优先按这两张表执行。
4. D 只对表外项做增量补充：
   - 足够稳定的写入 `confirmedTermUpdates`
   - 仍不稳定的写入 `candidateUpdates`
5. alias 一致性当前不由 D 回写；D 只消费 alias 表，不更新 alias 事实层。

本设计目标不是一开始就得到终局最优译名，而是先保证初稿全局一致。

## 2. 为什么要重设计

当前真实问题不是“字段不够”，而是“执行时机不对”：

1. C 当前给 D 的是知识卡、候选项、实体和 alias 线索这些原料，不是可直接执行的全局命名规则。
2. D 当前的做法是：
   - 有 `confirmedTerms` 就沿用
   - 没有就结合候选、知识卡、实体、alias hint 临场判断
3. 这导致首次出现的人名、地名、核心专名，经常在 D 首轮里裸奔。
4. 一旦首次出现时保留源语原形，污染已经进入初稿。
5. 现有 detector 又抓不住“中文正文里夹着源语专名残留”。

所以要改的不是 D 的 prompt 细节，而是 D 之前的全局命名输入。

## 3. 当前代码现状：C 到底产出什么

### 3.1 C 当前已有产物

C 当前已经产出以下原料：

1. `KnowledgeCard`
   - 人物卡
   - 设定卡
   - 术语解释卡
   - 历史/文化/意象卡
2. `CandidateTerm`
   - 候选术语/名称资产
   - 与知识卡并列存在，不是知识卡
3. alias 相关知识资产
   - 内生人物卡里的 `canonicalName / aliases / surfaceForms / aliasState / confidence`
4. B 侧传来的实体与弱提示
   - `entities`
   - `personAliasHints`

### 3.2 C 当前没有直接产出什么

C 当前没有直接给 D 两样关键东西：

1. 一张可直接执行的初稿全局译名表
2. 一张可直接执行的全局 alias 一致性表

因此当前 C 给 D 的是材料，不是规则表。

## 4. 设计目标

### 4.1 必须满足

1. 让 D 在翻初稿前拿到一份明确的全局译名表。
2. 让 D 在翻初稿前拿到一份明确的 alias 一致性表。
3. D 初稿优先按这两张表执行，以保证初稿全局一致。
4. 不另起一套和现有 `confirmed / candidate / alias / knowledge card` 冲突的体系。
5. 不把运行期临时状态塞回稳定领域对象。
6. 不退回大 orchestrator。
7. D 仍不承担主检索职责。
8. alias 当前不允许由 D 直接回写更新。
9. 为后续审校 agent 留下显式结构化输入包。

### 4.2 非目标

1. 不重写现有知识检索/向量召回方案。
2. 不把 `CandidateTerm` 直接升级成稳定事实。
3. 不把知识卡 `title / anchorNames` 直接当稳定译名。
4. 不让 detector 自动改写事实层。
5. 不让 D 成为项目级 alias 主决策者。

## 5. 新增的 D 前全局命名阶段

新增一个位于 C 之后、D 之前的阶段，职责只有一个：

**把 C 现有的原料整理成 D 初稿可直接执行的两张全局表。**

链路变为：

`C 产物 -> 全局命名阶段 -> D 初稿 -> D 增量输出 -> 审校阶段`

## 6. 两张全局表

### 6.1 `DraftStageGlobalGlossary`

定义：

**D 初稿阶段必须优先沿用的全局译名表。**

这张表不是终局稳定事实表，而是“初稿执行表”。

目标：

1. 先保证初稿全局一致
2. 降低 D 首见专名时的临场命名自由度
3. 让主要人名、地名、核心专名在初稿里先统一下来

建议字段：

- `hardEntries`
- `softEntries`
- `coverageSummary`

`GlossaryEntry` 建议字段：

- `sourceTerm`
- `targetTerm`
- `entryStrength`
  - `HARD`
  - `SOFT`
- `sourceKind`
  - `CONFIRMED_TERM`
  - `CANDIDATE_TERM`
  - `KNOWLEDGE_CARD_DERIVED`
  - `ALIAS_ASSISTED`
- `evidenceRefs`
- `notes`

语义：

- `hardEntries`
  - 当前已经足够稳定，D 必须沿用
  - 主要来自现有 `confirmedTerms`
- `softEntries`
  - 当前初稿阶段应统一采用，D 初稿优先沿用
  - 不是最终稳定事实
  - 主要来自候选项、知识证据和 alias 辅助判断后的整理结果

### 6.2 `GlobalAliasConsistencyTable`

定义：

**D 初稿阶段使用的全局 alias 一致性表。**

它负责表达“哪些不同叫法可能是同一个实体”，不负责直接定译名。

建议字段：

- `clusters`
- `unresolvedClusters`
- `coverageSummary`

`AliasCluster` 建议字段：

- `clusterId`
- `surfaceForms`
- `canonicalSourceNameOptional`
- `aliasState`
  - `OBSERVED`
  - `SUSPECTED_ALIAS`
  - `CONFIRMED_ALIAS`
- `confidence`
- `evidenceRefs`
- `recommendedRenderingFamily`

语义：

- 它告诉 D：
  - 哪些名字/称呼是同一实体
  - 当前置信度怎样
  - 同一实体的中文称呼体系应尽量保持一致
- 它不告诉 D：
  - 某个 source term 一定要翻成哪个稳定译名

## 7. 两张表的来源

### 7.1 `DraftStageGlobalGlossary` 来源

来源可以包括：

1. `confirmedTerms`
2. `CandidateTerm`
3. `candidateTermUpdates`
4. 选中的知识卡
5. 已知的重要实体
6. alias 表提供的辅助归并结果

但进入表时必须经过整理，不能直接原样搬运。

### 7.2 `GlobalAliasConsistencyTable` 来源

来源可以包括：

1. `personAliasHints`
2. 内生人物卡 metadata 中的 alias 相关信息
3. 知识卡中的人物别名证据
4. 当前项目范围内已经出现过的实体表面形式

边界：

- alias 线索可以整理成全局 alias 表
- 但 alias 表当前不由 D 回写

## 8. 两张表里哪些能进，哪些不能进

### 8.1 能进 `DraftStageGlobalGlossary`

1. 已有 `confirmedTerms`
2. 证据足够强、应在初稿阶段先统一的人名/地名/核心专名
3. 由候选项、知识卡证据、上下文整理后，已经足够支撑“当前阶段统一采用”的项

### 8.2 不能直接进 `DraftStageGlobalGlossary`

1. 单条 `personAliasHints`
2. 单独的 `anchorNames`
3. 单独的知识卡标题
4. 没有证据支撑、只是当前 chunk 的临场猜法

### 8.3 能进 `GlobalAliasConsistencyTable`

1. 多个 source forms 的归并结果
2. alias 状态与置信度
3. 证据来源

### 8.4 不能直接进 `GlobalAliasConsistencyTable`

1. D 当前 chunk 的临场称呼偏好
2. 未经整理的单条 hint
3. 任何需要 D 直接回写的 alias 决策

## 9. D 的新工作流程

### 9.1 D 输入

D 在翻当前 chunk 初稿前，拿到：

1. `DraftStageGlobalGlossary`
2. `GlobalAliasConsistencyTable`
3. 当前 chunk 原文与上下文
4. 当前 chunk 相关知识卡

### 9.2 D 初稿执行规则

D 初稿阶段按以下顺序工作：

1. 先看 `DraftStageGlobalGlossary.hardEntries`
   - 有的必须沿用
2. 再看 `DraftStageGlobalGlossary.softEntries`
   - 有的优先沿用
3. 再看 `GlobalAliasConsistencyTable`
   - 判断多个叫法是否属于同一实体
   - 同一实体尽量保持同一套中文称呼体系
4. 如果两张表都没有覆盖到
   - D 才临场给出当前最合适的译法

### 9.3 D 的权限

D 的权限重新收紧为：

1. 对表内已覆盖项
   - 执行
   - 不得随意重命名
2. 对表外项
   - 可以做当前 chunk 的增量判断
3. 对 alias 一致性
   - 可以消费
   - 不负责更新系统 alias 表

## 10. D 的输出

D 仍输出原有结构，但语义地位改变：

### 10.1 正式翻译结果

- `translatedText`
- `translatorCommentary`
- `decisionNotes`
- `transitionNote`

### 10.2 增量译名结果

- `confirmedTermUpdates`
- `candidateUpdates`

语义：

- `confirmedTermUpdates`
  - 只用于表外新项的稳定增量
- `candidateUpdates`
  - 只用于表外新项的候选增量

也就是说：

- 以后全局命名主流程不再由 D 承担
- D 只做表外项的增量补充

## 11. confirmed/candidate 机制的新位置

### 11.1 机制保留

以下机制继续保留：

- `confirmedTerms = 当前稳定存量`
- `confirmedTermUpdates = 本轮稳定增量`
- `candidateUpdates = 本轮候选增量`

### 11.2 机制地位变化

以前：

- 它是 D 主导命名的核心机制

现在：

- 它是“全局命名阶段未覆盖项”的增量补充机制

这意味着：

1. 主体命名工作前移到 D 前
2. D 只补表外项
3. 首见核心专名不应再主要依赖 D 临场定名

## 12. alias 与 D 的边界

当前代码真实现状是：

1. D 会更新译名增量
2. D 不会更新 alias 状态

因此本设计明确保留这个边界：

1. alias 一致性表由 D 前阶段生成
2. D 只消费 alias 表
3. D 不直接回写 alias
4. 如果 D 发现 alias 风险或冲突，只能输出说明，不更新 alias 事实层

## 13. detector 的补位方向

全局命名前移后，仍需补两类检测：

### 13.1 `name-residue-warning`

用途：

- 抓“全局表里已有译名，但正文仍残留源语专名”

### 13.2 `glossary-entry-not-applied`

用途：

- 抓“全局译名表里已有推荐项，但 D 初稿未采用”

这两类 detector 的目标是检查 D 是否按全局命名规则执行。

## 14. 审校输入包

后续审校 agent 不应只拿正文，应拿一份显式结构化结果包：

### 14.1 包含内容

1. 初稿正文
2. 当前使用的 `DraftStageGlobalGlossary` 快照
3. 当前使用的 `GlobalAliasConsistencyTable` 快照
4. 本轮 `confirmedTermUpdates`
5. 本轮 `candidateUpdates`
6. 风险与未决问题
7. 实际引用的知识证据摘要

### 14.2 目标

让后续审校 agent 知道：

1. 初稿是按什么全局译名规则翻的
2. alias 是怎么理解的
3. 哪些项是本轮新增
4. 哪些项还没收敛

## 15. 与现有知识检索方案的关系

本设计不替换现有知识检索方案。

现有检索继续负责：

1. 召回知识卡
2. 提供候选原料

新增全局命名阶段负责：

1. 整理 `KnowledgeCard`
2. 整理 `CandidateTerm`
3. 整理 alias 线索
4. 产出两张可执行的全局表

所以关系是：

`知识检索 / C 产物 -> 全局命名阶段 -> D 初稿执行`

不是新增平行检索系统。

## 16. 这个方案如何解决前面九个问题

1. 缺少稳定层与提示层之间的可执行装配
   - 由 `DraftStageGlobalGlossary` 解决
2. 稳定化时机过晚
   - 由 D 前全局命名阶段解决
3. C0 与 D 语义没接上
   - 由“两张全局表”解决
4. alias 信号缺受控转译通道
   - 由 `GlobalAliasConsistencyTable` 解决
5. 知识卡语义过散
   - 由“知识卡先作为原料，再整理成规则表”解决
6. detector 对真实故障形态失明
   - 由新 detector 解决
7. confirmed 准入过于依赖 D 临场判断
   - 由“D 只补表外项”解决
8. 运行期缺少中间缓冲层
   - 由 D 前全局命名阶段解决
9. 首见专名译名前置不足
   - 由 D 前全局译名表直接解决

## 17. 实施顺序建议

后续实现建议按以下顺序推进：

1. 先定义两张全局表的数据结构
   - `DraftStageGlobalGlossary`
   - `GlobalAliasConsistencyTable`
2. 实现 D 前全局命名阶段
3. 调整 D 输入，使 D 初稿优先按这两张表执行
4. 保留并接好 `confirmed/candidate` 增量机制
5. 再补 detector
6. 最后补审校输入包

## 18. 最终结论

本次设计的核心变化是：

1. 不再让 D 主导项目级首见命名
2. 先在 D 前明确产出全局译名表和全局 alias 一致性表
3. D 初稿阶段先执行规则，再做增量补充

用一句话概括：

**新增一个 D 前的全局命名阶段：把 C 现有的 knowledge cards、candidate terms、alias 线索整理成“初稿全局译名表 + 全局 alias 一致性表”；D 初稿严格优先按这两张表执行，只对表外项做 confirmed/candidate 增量补充。**
