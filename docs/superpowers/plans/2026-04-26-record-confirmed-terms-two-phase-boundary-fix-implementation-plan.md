# Record Confirmed Terms Two-Phase Boundary Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 `record_confirmed_terms` 的两阶段边界，让 next-step 只负责路由选择，不再因缺少最终 `arguments.entries` 卡死。

**Architecture:** 保持现有业务语义不变，只把 `record_confirmed_terms` 的 next-step contract 与 executable contract 分开。`PromptBackedNextStepDecisionProvider` 在 next-step 合法路由后进入 proposal，由 proposal 生成 `entries[]`，再组装成最终 executable decision。`OpenAiCompatibleReviewAgentStructuredGenerationClient` 和 `ReviewToolDecisionContractValidator` 同步改成阶段感知校验。

**Tech Stack:** Java, JUnit 5, Maven

---

### Task 1: 补 next-step 两阶段失败测试

**Files:**
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
- Modify: `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolDecisionContractValidatorTest.java`

- [ ] **Step 1: 添加 provider 失败测试**

新增测试覆盖：

- next-step 返回 `{"toolName":"record_confirmed_terms","arguments":{},"reason":"..."}` 时，应直接进入 proposal path
- 不应因为缺少 `entries` 返回 `invalid_argument:entries`

- [ ] **Step 2: 运行定向测试，确认先失败**

Run: `mvn -q "-Dtest=PromptBackedNextStepDecisionProviderTest,OpenAiCompatibleReviewAgentStructuredGenerationClientTest,ReviewToolDecisionContractValidatorTest" test`

Expected: FAIL，失败点应体现当前 next-step 仍要求 `entries`

- [ ] **Step 3: 添加 validator/client 失败测试**

新增测试覆盖：

- next-step mode 下，`record_confirmed_terms(arguments={})` 合法
- executable mode 下，`record_confirmed_terms(arguments={})` 非法
- client 的 `generateNextToolDecision(...)` 不再因 route-stage 缺少 `entries` 而抛 `invalid_argument:entries`

---

### Task 2: 实现阶段感知 validator

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolDecisionContractValidatorTest.java`

- [ ] **Step 1: 给 validator 增加阶段模式**

增加 next-step 与 executable 两种校验入口，保持默认 executable 语义不变。

- [ ] **Step 2: 仅对 `record_confirmed_terms` 放宽 next-step 校验**

next-step mode 下：

- 允许 `toolName=record_confirmed_terms`
- 允许 `arguments={}`
- 不要求 `entries`

executable mode 下维持原行为：

- `entries` 必须存在
- `entries` 必须是非空 `object{string:string}`

- [ ] **Step 3: 运行 validator 定向测试**

Run: `mvn -q "-Dtest=ReviewToolDecisionContractValidatorTest" test`

Expected: PASS

---

### Task 3: 修改 client 与 provider 的两阶段切换顺序

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
- Modify: `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java`

- [ ] **Step 1: client 改为 next-step 只做 next-step contract 校验**

`generateNextToolDecision(...)` 不再直接套用 executable 校验。

- [ ] **Step 2: provider 改为对 next-step 使用 next-step mode**

在 provider 内：

- next-step 先按 next-step mode 校验
- `record_confirmed_terms` 路由成立后再判断 proposal 入口
- proposal 组装完成后再按 executable mode 校验最终 decision

- [ ] **Step 3: 去掉 next-step repair 对 `entries` 的 first-stage 强制要求**

只保留 route-stage 修复语义，不再要求 next-step repair 里补最终 `arguments.entries`

- [ ] **Step 4: 运行 provider/client 定向测试**

Run: `mvn -q "-Dtest=PromptBackedNextStepDecisionProviderTest,OpenAiCompatibleReviewAgentStructuredGenerationClientTest" test`

Expected: PASS

---

### Task 4: 运行回归并确认没有引入新的死循环

**Files:**
- Verify only

- [ ] **Step 1: 运行本次相关测试集合**

Run: `mvn -q "-Dtest=PromptBackedNextStepDecisionProviderTest,OpenAiCompatibleReviewAgentStructuredGenerationClientTest,ReviewToolDecisionContractValidatorTest,AutonomousProjectReviewAgentTest" test`

Expected: PASS

- [ ] **Step 2: 记录验证结果**

在收尾说明中明确列出实际执行命令与结果，不用旧结果代替。
