# D 草稿链路问题说明

本文档记录 Agent D 草稿链路当前重点治理的问题与已落地的修复方向。

## 已确认的主要问题

1. 正文中混入括号注、百科式插入和知识卡式扩写。
2. `zh` 目标下仍可能残留整句或整段外语。
3. 已确认术语未必真的被正文沿用。
4. 原文名与确认译名可能在同一正文中混用。
5. revision round 容易退化成重翻或自由润色，而不是按问题清单修订。

## 已落地的治理

1. `TranslatedTextIssueDetector` 已能识别：
   - `bracketed-explanation`
   - `encyclopedic-insertion`
   - `target-language-purity`
2. 这些问题会进入 validator，转成可修复的 decision note，而不是直接硬失败。
3. revision round 已开始接收正文问题清单。
4. `GlossaryComplianceIssueDetector` 已能识别：
   - `name-residue-warning`（原文名与确认译名混用）
   - `glossary-entry-not-applied`（已确认术语未沿用）
   - `first-name-confirmation-missing`（首次出现未确认译名）
5. revision prompt 已明确按 issue 清单定向修订，不是重翻，不是自由润色。
6. glossary issue 与正文纯度 issue 已一并纳入 revision round。

## 接下来必须继续收口的点

1. 进一步提升 glossary 合规检测的召回率（当前依赖 LLM 识别，可能遗漏隐式混用）。
2. 跨 chunk 术语一致性仍需 Review Agent 保障（D 只做单 chunk 内检测）。

## 边界

1. D 不承担主检索职责。
2. D 不联网。
3. D 的 loop 只做本地补卡与问题修订。
4. validator 默认产出 `repair-required` 风格问题，不滥用硬失败。
