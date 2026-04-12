# 交接说明

本文件只保留新会话进入时最该先看的入口与最近的重要结论。

## 新会话先看

1. [docs/README.md](./README.md)
2. [docs/current-architecture.md](./current-architecture.md)
3. [docs/current-status.md](./current-status.md)
4. [docs/modules/name-consistency.md](./modules/name-consistency.md)
5. [docs/modules/d-draft-chain-issues.md](./modules/d-draft-chain-issues.md)

## 最近重要结论

1. A 的 `globalConstraints` 已做执行层边界治理，非法约束不会继续下游传播，并会进入 trace。
2. C0 已接入最小内生人物卡分支，内生卡与外部知识卡并存，不互相替代。
3. alias 归一当前默认保守，只支持 `OBSERVED / SUSPECTED_ALIAS / CONFIRMED_ALIAS` 三态。
4. D 已接入正文边界污染与目标语言纯度检测，但 glossary 正文合规与 revision 收口仍需继续完善。

## 文档职责

1. 当前架构和边界看 [current-architecture.md](./current-architecture.md)
2. 当前进展与待办看 [current-status.md](./current-status.md)
3. 名称一致性现状看 [modules/name-consistency.md](./modules/name-consistency.md)
4. D 草稿链路问题看 [modules/d-draft-chain-issues.md](./modules/d-draft-chain-issues.md)
5. 历史计划与方案看 `docs/superpowers/` 与 `docs/history/`
