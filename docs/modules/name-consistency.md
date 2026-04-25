# 名称一致性模块

本文档说明当前项目对人名、地名、称谓、专名等一致性内容的真实实现链路。

## 当前链路
1. B 在每个 `chunk` 上产出：
   - `entities`
   - `keyExpressions`
   - `personAliasHints`
2. C0 会基于这些信号做两类产物：
   - `CandidateTerm`
   - 外部知识卡与内生人物卡
3. D 前的装配层会把现有原料整理成两张执行表：
   - `DraftStageGlobalGlossary`
   - `GlobalAliasConsistencyTable`
4. D 初稿严格优先按这两张表执行。
5. D 只对表外项补：
   - `confirmedTermUpdates`
   - `candidateUpdates`

## 当前几类对象分别代表什么

### 1. `confirmedTerms`
1. 代表当前项目内已经生效、后续必须优先沿用的稳定译名。
2. D 不允许改写已有 `confirmedTerms`。
3. 这是当前系统里最接近“稳定译名事实”的对象。

### 2. `candidateTermUpdates`
1. 代表候选译法，不等于当前生效译名。
2. 可供后续参考，但不会直接覆盖 `confirmedTerms`。
3. 仍属于辅助信号，不是稳定事实。

### 3. `DraftStageGlobalGlossary`
1. 是 D 前的执行视图，不是长期 memory。
2. `hardEntries` 主要来自已有 `confirmedTerms`。
3. `softEntries` 来自候选项、知识卡显式推荐译法等原料。
4. 它的作用是让 D 在初稿开始前就拿到一份可执行命名表，而不是首次出现时临场定名。

### 4. `GlobalAliasConsistencyTable`
1. 是 D 前的 alias 执行视图。
2. 来源主要是 `personAliasHints` 和知识卡中的 alias 线索。
3. 当前 alias 只读消费，不允许由 D 回写。
4. 单条 alias hint 不能直接升级成稳定 alias 事实。

### 5. 知识卡
1. 知识卡可提供背景、人物关系、命名线索和显式推荐译法。
2. 但知识卡本身不天然等于稳定译名表。
3. `title / anchorNames` 不能直接当稳定译名事实。

## 当前新增收口
1. 若正文仍残留词池中已有对应译法的外文命名，revision 会收到：
   - `name-residue-warning`
   - `glossary-entry-not-applied`
2. 若高频核心人名尚未进入当前生效译名表，D 本轮无论决定翻成中文还是保留原文，都必须把该决定写入 `confirmedTermUpdates`。
3. 若遗漏首次命名登记，validator 会补出：
   - `first-name-confirmation-missing`

## 当前仍未完成的部分
1. 初稿输出后的正式启动包已持久化为 `PostDraftReviewPackage`。
2. 当前已能按 `projectId` 联动加载：
   - `PostDraftReviewPackage`
   - `ProjectKnowledgeBase`
   以从初稿完成点恢复后续 agent。
3. 因此，名称一致性链路已与初稿后正式恢复入口打通。
4. 当前仍未完成的是：
   - 全阶段 stage persistence
   - A / B / C0 历史快照恢复

## 当前不能违反的边界
1. 不能把 B 的弱提示直接升级成项目级稳定事实。
2. 不能把运行期临时 glossary 塞回稳定领域对象。
3. 不能新发明一套与 `confirmed / candidate / alias / knowledge card` 冲突的新体系。
4. 不能把 D 重新做成大 orchestrator。
