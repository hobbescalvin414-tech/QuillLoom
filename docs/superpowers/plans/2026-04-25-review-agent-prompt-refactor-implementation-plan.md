# Review Agent Prompt Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 review-agent 的 prompt 分层设计落到代码中：瘦身 system prompt、把 investigation gate 收敛到维度模板、补齐 evaluation / revision / self-check 的最终英文 prompt，并在不改变 runtime 主骨架、工具协议、repair / retry / persistence / resume 的前提下完成回归验证。

**Architecture:** 只改 prompt builder、structured-generation client 的 schema 文案、以及少量 repair prompt 文案；不改 `AutonomousProjectReviewAgent` 主循环，不改工具集合，不改外部协议。实现时以 `docs/superpowers/specs/2026-04-23-review-agent-prompt-refactor-design.md` 为唯一设计来源，其中 `6A` 的中文是规范基线，`English Program Version` 是写入程序的英文 prompt。

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Maven

---

## 1. 文件范围与职责

### 1.1 必改文件

- `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
  - 按新分层瘦身 system prompt
  - 只保留 Layer A 的全局规则
- `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
  - 按 `6A.2 / 6A.3` 重写 investigation prompt
  - 让 `Decision Gate Summary` 使用四个维度模板
- `src/main/java/io/quillloom/application/postdraft/review/prompt/EvaluationPromptBuilder.java`
  - 改为 `6A.4` 英文 prompt
  - 明确 Evaluation Inputs / Handoff / Task / Constraints / Output Contract
- `src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionPromptBuilder.java`
  - 改为 `6A.5` 英文 prompt
  - 显式引入 `Revision Target`
- `src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java`
  - 改为 `6A.6` 英文 prompt
  - self-check 围绕 `Revision Target` 验收
- `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
  - 调整 investigation schema description 文案，避免与新 prompt 分层冲突
  - 不改 schema shape，只改说明文字

### 1.2 可能小改文件

- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`
  - 若 repair prompt 文案与新 prompt 分层明显冲突，做最小对齐
  - 不改 repair / replan 主流程

### 1.3 必改测试

- `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
  - 更新 system / investigation / evaluation / revision / self-check prompt 断言
- `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
  - 若 repair prompt 相关断言受影响，更新为新文案
- `src/test/java/io/quillloom/application/postdraft/review/PromptBackedStrategyEvaluationServiceTest.java`
  - 补 evaluation prompt / output contract 相关断言
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftRevisionServiceTest.java`
  - 补 revision / self-check prompt 关键上下文断言

### 1.4 不改文件

- `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- `src/main/java/io/quillloom/infrastructure/postdraft/review/RetryingReviewAgentStructuredGenerationPort.java`
- persistence / resume 相关类
- 任何工具协议 DTO / enum shape

## 2. 实施顺序

1. 先加或改 prompt builder 测试，锁定新文案的关键断言。
2. 再改 system + investigation。
3. 再改 evaluation。
4. 再改 revision + self-check。
5. 最后改 schema description / repair 文案并跑回归。

## 3. 任务拆分

### Task 1: 锁定 Prompt Builder 新断言

**Files:**
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedStrategyEvaluationServiceTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PostDraftRevisionServiceTest.java`

- [ ] **Step 1: 给 system prompt 增加收缩断言**

新增或更新断言，至少覆盖：
- 不再包含旧式 `[Available Tools]` 全量手册展开
- 仍包含：
  - low-priority signal 约束
  - confirmedTermLookupMiss 不是写表授权
  - strategy 不是 completion signal

- [ ] **Step 2: 给 investigation prompt 增加 gate 模板断言**

新增或更新断言，至少覆盖：
- `Decision Gate Summary` 不再自己展开四维度长规则
- prompt 中出现对当前维度 gate template 的使用提示
- term gate 不重复老式长篇工具说明

- [ ] **Step 3: 给 evaluation prompt 增加输出契约断言**

新增或更新断言，至少覆盖：
- 出现 `Key Evidence / Conflicting Evidence / Evidence Gaps`
- 出现 `recommendedStrategy / strategyReason / evidenceSufficiency / continueInvestigation`
- `evidenceSufficiency` 只允许 `UNKNOWN / SUFFICIENT / PARTIAL / INSUFFICIENT`

- [ ] **Step 4: 给 revision prompt 增加 Revision Target 断言**

新增或更新断言，至少覆盖：
- 出现 `Revision Target`
- 出现 `must_fix_items`
- 出现 `do_not_expand_boundary`
- 出现 `formalTranslation / revisionMode / keyRationales / residualRisks`
- 明确“完整正式译文，不是 diff / fragment”

- [ ] **Step 5: 给 self-check prompt 增加目标闭环断言**

新增或更新断言，至少覆盖：
- 出现 `Revision Target`
- self-check 围绕 `Revision Target` 验收
- 输出字段仅为 `passed / stopReason / findings`

- [ ] **Step 6: 前置锁定 Layer C / D 关键断言**

新增或更新断言，至少覆盖：
- schema description 中仍保留以下工具的最小语义句：
  - `record_confirmed_terms`
  - `request_human_review`
  - `complete_working_set`
  - `complete_project`
- repair prompt 中仍保留：
  - Repair Scope
  - Repair Findings
  - Repair Constraints
  - Repair Target Alignment

- [ ] **Step 7: 跑定向测试**

Run:
```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest,PromptBackedNextStepDecisionProviderTest,PromptBackedStrategyEvaluationServiceTest,PostDraftRevisionServiceTest" test
```

Expected:
- 先失败，暴露旧 prompt 文案与新设计不一致

### Task 2: 重写 System Prompt Builder

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

- [ ] **Step 1: 按 Layer A 保留 system 常驻模块**

将 system prompt 收敛到：
- Agent Role
- Global Hard Rules
- Authority Rules
- Global Working Discipline
- Global Completion / Escalation Rules
- Output Contract

删除或下沉：
- 旧式全工具说明书
- investigation 细门槛
- repair 风格说明

- [ ] **Step 2: 使用设计文档 `6A.1` 的 English Program Version**

逐模块替换为文档英文版，不自行改写语义。

- [ ] **Step 3: 跑 prompt 测试**

Run:
```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest" test
```

Expected:
- system prompt 相关断言通过

### Task 3: 重写 Investigation Prompt Builder

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

- [ ] **Step 1: 保留 Layer B 五个模块**

模块结构固定为：
- Current Facts
- Decision Gate Summary
- Working Set Text Context
- State Memory
- Output Reminder

- [ ] **Step 2: 让 Decision Gate Summary 只做短引导**

实现要求：
- 当前 prompt 中只写“先识别当前维度，再按对应 gate template 决定下一步”
- 真正的四维度 gate 文案来自代码中的维度模板渲染
- 不再把 term / quality / continuity / completion 长篇硬拼进正文

- [ ] **Step 3: 用 `6A.3` 的英文模板落 continuity / term / quality / completion**

特别检查：
- term 模板维持 4 句短门槛
- 不把 `6A.6A` 的实现说明术语混进 prompt

- [ ] **Step 4: 跑 prompt 测试**

Run:
```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest,PromptBackedNextStepDecisionProviderTest" test
```

Expected:
- investigation prompt 断言通过
- next-step provider 相关测试仅在 repair 文案变更处可能失败

### Task 4: 重写 Evaluation Prompt Builder

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/EvaluationPromptBuilder.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedStrategyEvaluationServiceTest.java`

- [ ] **Step 1: 引入五个模块**

模块固定为：
- Evaluation Inputs
- Evaluation Handoff
- Evaluation Task
- Evaluation Constraints
- Output Contract

- [ ] **Step 2: 使用 `6A.4` 的 English Program Version**

重点保持：
- Key Evidence / Conflicting Evidence / Evidence Gaps 是 sufficiency 依据
- 若进入 revision，后续使用 `Revision Target`
- `recommendedStrategy` 只能从 candidate strategies 中选

- [ ] **Step 3: 跑 evaluation 相关测试**

Run:
```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest,PromptBackedStrategyEvaluationServiceTest" test
```

Expected:
- evaluation prompt 相关断言通过

### Task 5: 重写 Revision Prompt Builder

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionPromptBuilder.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftRevisionServiceTest.java`

- [ ] **Step 1: 固定 Revision Target + Revision Contract + Output Contract**

模块固定为：
- Revision Target
- Revision Contract
- Output Contract

- [ ] **Step 2: 用 `6A.5` 的 English Program Version**

重点保持：
- `Revision Target` 中出现：
  - revision_mode
  - must_fix_items
  - confirmed_terms_constraints
  - do_not_expand_boundary
  - residual_risks_summary
- `Revision Contract` 明确：
  - 完整正式译文
  - 不扩张范围
  - 非 `RETRANSLATE` 不得自由重写

- [ ] **Step 3: 只从现有字段渲染 Revision Target**

实现时只允许复用：
- targetStrategy
- keyRationales
- confirmed-term 相关现有证据
- residualRisks

不得新增 phase-state 字段。
若实现确实需要只读 helper / render model，它只能局限在 prompt builder 内部或同文件局部使用，不得升级为新的跨 service 运行时契约对象。

- [ ] **Step 4: 跑 revision 相关测试**

Run:
```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest,PostDraftRevisionServiceTest" test
```

Expected:
- revision prompt 相关断言通过

### Task 6: 重写 Self-Check Prompt Builder

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftRevisionServiceTest.java`

- [ ] **Step 1: 固定 Self-Check Objective + Task + Constraints + Output Contract**

重点：
- Self-Check Objective 只短引用 `Revision Target`
- 不再提虚的 readiness 字段
- 任务里以 `passed` 语义验收

- [ ] **Step 2: 用 `6A.6` 的 English Program Version**

特别检查：
- 只输出 `passed / stopReason / findings`
- 围绕 `Revision Target` 验收 draft
- previous findings 逐项解决

- [ ] **Step 3: 跑 self-check 相关测试**

Run:
```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest,PostDraftRevisionServiceTest" test
```

Expected:
- self-check prompt 相关断言通过

### Task 7: 对齐 Structured Generation Schema 文案

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

- [ ] **Step 1: 保留 schema 形状，重写 investigation schema description 文案**

要求：
- 保留 shape / allowed / required 约束
- 不再把老式工具说明 prose 堆回 schema description
- 对高风险工具保留最小静态语义边界：
  - record_confirmed_terms
  - request_human_review
  - complete_working_set
  - complete_project

- [ ] **Step 2: 不改 JSON shape**

不得修改：
- `toolName`
- `arguments`
- `reason`
- investigation arguments union

- [ ] **Step 3: 跑相关测试**

Run:
```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest,PromptBackedNextStepDecisionProviderTest" test
```

Expected:
- schema / provider 相关测试通过

### Task 8: 对齐 Repair Prompt 文案

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`

- [ ] **Step 1: 只改 repair 文案，不改 repair 流程**

对齐 `6A.7`：
- Repair Scope
- Repair Findings
- Repair Constraints
- Repair Target Alignment

- [ ] **Step 2: 分阶段对齐原任务目标**

保持：
- next-step / evaluation repair -> 当前阶段原始任务目标
- revision / self-check repair -> 当前 `Revision Target`
- proposal repair -> 工具专用局部链路目标

- [ ] **Step 3: 跑 provider 测试**

Run:
```powershell
mvn -q "-Dtest=PromptBackedNextStepDecisionProviderTest" test
```

Expected:
- repair / replan 相关断言通过

### Task 9: 全量回归验证

**Files:**
- No code changes expected
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedStrategyEvaluationServiceTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftRevisionServiceTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`

- [ ] **Step 1: 跑 prompt / provider / revision 相关测试**

Run:
```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest,PromptBackedNextStepDecisionProviderTest,PromptBackedStrategyEvaluationServiceTest,PostDraftRevisionServiceTest" test
```

Expected:
- PASS

- [ ] **Step 2: 跑自主 review agent 回归测试**

Run:
```powershell
mvn -q "-Dtest=AutonomousProjectReviewAgentTest" test
```

Expected:
- PASS

- [ ] **Step 3: 人工核对关键回归点**

必须人工确认：
- system prompt 不再包含全工具手册
- investigation gate 使用四维度模板
- revision 阶段明确知道“修什么”
- self-check 围绕同一 `Revision Target` 验收
- record_confirmed_terms 窄两阶段特例不受破坏
- pending-empty / complete_project 语义未回退

## 4. 覆盖检查

本计划覆盖以下设计要求：

- prompt 分层：Layer A / B / C / D / E
- system prompt 瘦身
- investigation gate 模板化
- evaluation / revision / self-check 的英文最终 prompt 文案
- Revision Target 闭环
- repair target alignment
- schema description 仍保留最小静态语义边界
- 不改 runtime 主骨架 / 工具协议 / persistence / resume

## 5. 风险提醒

1. 不要把 `6A.6A` 的实现说明术语搬进 prompt 正文。
2. 不要为 `Revision Target` 新增持久化字段或新的 phase-state 聚合对象。
   若必须引入只读 helper / render model，它也只能局限在 prompt builder 内部或同文件局部使用，不得进入 service 间流转。
3. 不要把 evaluation 的 `strategyReason` 原文整段无约束塞进 revision，防止 prompt 再次发胖。
4. 不要修改 `ReviewToolDecision`、`ReviewAgentEvaluation`、`RevisionDraft`、`RevisionSelfCheckResult` 的字段 shape。

## 6. 完成标准

1. 所有相关 prompt builder 使用 `6A` 中的英文最终版。
2. 所有相关测试通过。
3. prompt builder 文案与设计文档不再明显分叉。
4. 运行时 prompt 中不再出现文档章节号式交叉引用。
