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

## 接下来必须继续收口的点

1. 新增 glossary 正文合规检测：
   - 已确认术语未沿用
   - 原文名与确认译名混用
   - 明显命名漂移
2. 明确 revision prompt：
   - 不是重翻
   - 不是自由润色
   - 是按 issue 清单定向修订
3. 将 glossary issue 与正文纯度 issue 一并纳入 revision round 优先级。

## 边界

1. D 不承担主检索职责。
2. D 不联网。
3. D 的 loop 只做本地补卡与问题修订。
4. validator 默认产出 `repair-required` 风格问题，不滥用硬失败。
