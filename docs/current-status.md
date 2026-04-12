# 当前状态

本文档记录截至当前代码状态的真实进展、已知问题与推荐推进顺序。

## 已完成

### A 边界治理

1. Agent A 的 `globalConstraints` 已新增执行层边界判定。
2. 实体级“不译”、引文原文保留这类非法全局约束不会再继续下游传播。
3. 被拒收的约束会进入 preprocess trace，并写入可读产物。

### C0 内生知识

1. 已新增最小内生人物卡草稿结构：`canonicalName / aliasSet / surfaceForms / evidenceChunks / firstSeenChunkId / roleSummary / aliasState / confidence`。
2. 已新增保守 alias 状态机：`OBSERVED / SUSPECTED_ALIAS / CONFIRMED_ALIAS`。
3. 内生人物卡已挂接到 C0 主链，会与外部知识卡并存，不替代外部搜索链路。
4. `KnowledgeCard` 已支持最小 `metadata` 承载内生卡附加信息，并保持旧构造器兼容。

### D 软约束与修订前置

1. `TranslatedTextIssueDetector` 已支持按目标语言检测正文纯度问题。
2. `zh` 目标下的整句或整段外语残留，已能生成 `target-language-purity` issue。
3. D 侧 revision round 已开始消费正文问题清单，而不是只依赖自由修订。

## 正在收口的剩余问题

1. `active glossary` 正文合规还未彻底闭环。
2. revision prompt 还需要更明确地围绕 issue 清单定向修订。
3. 文档需要同步到当前代码状态，避免继续受历史乱码内容干扰。

## 当前推荐顺序

1. 完成 D 侧 glossary 合规检测。
2. 完成 revision round 的 issue 驱动修订约束。
3. 同步文档并跑一轮目标回归。

## 注意事项

1. 当前系统仍是受控流水线，不是自治 agent 社会。
2. 不要把 D 的 loop 扩成主检索层。
3. 不要把运行期临时状态塞回稳定领域契约。
4. 不要把 C0 的候选 alias 误写成稳定事实。
