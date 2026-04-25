# Review Agent `record_confirmed_terms` 最小实施版计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 仅用最小改动修复两个真实问题：错误触发 `record_confirmed_terms` 与 `entries` 错误形状未被严格拦截。

**Architecture:** 本轮只做边界加硬，不做架构重构。保留现有 13 个工具、保留 `ReviewToolDecision(toolName, arguments, reason)` 契约、保留 `ReviewToolExecutor` 现有 switch。实现层仅在业务门槛与参数校验上收敛。

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Maven。

---

## 本轮目标与非目标

### 本轮只解决
1. Agent 被低权重信息误导，错误触发 `record_confirmed_terms`。
2. `record_confirmed_terms.entries` 常见错误形状没有被严格拦住。

### 本轮不追求
1. 不追求“自动修好格式后继续跑”。
2. 不追求“统一 tool decision repair 架构”。
3. 不追求“项目级决策机制重构”。

### 本轮原则
1. 边界更硬。
2. 失败更清楚。
3. 系统不漂移。

---

## 红线（必须遵守）

1. 不新增工具。
2. 不扩 `ReviewToolExecutor` switch。
3. 不改 schema 到新的大架构。
4. 不改 `ReviewToolDecision(toolName, arguments, reason)` 基本契约。
5. 不把 `NO_PROGRESS` 转成 HITL。
6. 不引入 `actionId / allowedActions` 重构。
7. 不做 repair 责任下沉到 `OpenAiCompatibleReviewAgentStructuredGenerationClient` 的迁移。

---

## 业务规则（本轮写死）

### A. `record_confirmed_terms` 写入门槛（保守规则）
1. `record_confirmed_terms` 不是 miss 后补登记工具。
2. `confirmedTermLookupMiss` 不能单独构成写表依据。
3. `decisionNotes` / `transitionNote` / `translatorCommentary` 不能单独构成写表依据。
4. `sourceText` / `translatedText` 的自由文本对齐推断不能单独构成写表依据。
5. 首版只允许：当前 workingSet 内已有 `confirmedTermUpdates` 正向支持的条目才可登记。
6. 不允许人工 note、模糊证据、自由文本推断放行。

### B. `entries` 格式规则（写死）
只允许：

```json
{"entries":{"Bernolle":"贝尔诺勒"}}
```

明确拒绝：

```json
{"entries":[{"sourceTerm":"Bernolle","targetTerm":"贝尔诺勒"}]}
```

```json
{"entries":{"sourceTerm":"Bernolle","targetTerm":"贝尔诺勒"}}
```

```json
{"entries":["Bernolle=贝尔诺勒"]}
```

禁止行为：
1. 不允许静默转换错误格式。
2. 不允许系统自动把数组改成 map。
3. 不允许“错格式但猜得出来”就放行。

---

## 关于格式错误反馈（本轮限制）

1. 本轮不设计 repair 下沉。
2. 本轮不把业务语义修复放进底层 client。
3. 本轮只允许如下表述与行为：
   - 格式错误时返回明确的 `invalid_argument:entries` 诊断，由上层决定是否重试。

---

## 任务结构（只保留 Task 1 / Task 2）

### Task 1：收紧 `record_confirmed_terms` 业务门槛

**为什么这是小修边界，不是架构重构：**
只改执行前业务放行条件，不改工具集、不改协议、不改状态机。

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`

- [ ] **Step 1: 先写失败测试（正反两类）**

必须覆盖：
1. `miss + 低权重 notes` 触发写表 -> 拒绝。
2. 仅 source/translation 自由文本对齐 -> 拒绝。
3. 当前 workingSet `confirmedTermUpdates` 已有同源同目标 -> 允许。

建议测试名：
- `shouldRejectRecordConfirmedTermsWhenOnlyDrivenByLookupMissAndLowAuthorityNotes`
- `shouldRejectRecordConfirmedTermsWhenOnlySupportedBySourceTargetAlignment`
- `shouldAllowRecordConfirmedTermsWhenSupportedByCurrentWorkingSetConfirmedTermUpdates`

- [ ] **Step 2: 跑红测，确认当前行为不满足规则**

Run:
```powershell
mvn -q "-Dtest=ReviewToolExecutorGuardrailTest" test
```

Expected:
```text
BUILD FAILURE（新增用例先失败）
```

- [ ] **Step 3: 最小实现通过业务门槛**

实现要求：
1. 仅当 `entries` 全部被当前 workingSet 的 `confirmedTermUpdates` 同源同目标支持时放行。
2. 其他情况统一拒绝为 `invalid_record_confirmed_terms_basis`（或等价受控错误码）。
3. 保留现有 writer 冲突检测，不新增 fallback。

- [ ] **Step 4: 跑绿测**

Run:
```powershell
mvn -q "-Dtest=ReviewToolExecutorGuardrailTest" test
```

Expected:
```text
BUILD SUCCESS
```

---

### Task 2：收紧 `record_confirmed_terms.entries` 格式校验

**为什么这是小修边界，不是架构重构：**
只改参数校验，不改工具执行路径，不改 client repair 责任分层。

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolDecisionContractValidatorTest.java`

- [ ] **Step 1: 先写失败测试**

必须覆盖：
1. `entries=[{sourceTerm,targetTerm}]` -> `invalid_argument:entries`
2. `entries={sourceTerm,targetTerm}` -> `invalid_argument:entries`
3. `entries=["Bernolle=贝尔诺勒"]` -> `invalid_argument:entries`
4. `entries={"Bernolle":"贝尔诺勒"}` -> 通过

建议测试名：
- `shouldRejectRecordConfirmedTermsEntriesWhenEntriesIsArrayOfObjects`
- `shouldRejectRecordConfirmedTermsEntriesWhenUsingSourceTermTargetTermPairObject`
- `shouldRejectRecordConfirmedTermsEntriesWhenEntriesIsArrayOfStrings`
- `shouldAcceptRecordConfirmedTermsEntriesWhenEntriesIsStringMap`

- [ ] **Step 2: 跑红测**

Run:
```powershell
mvn -q "-Dtest=ReviewToolDecisionContractValidatorTest" test
```

Expected:
```text
BUILD FAILURE（新增用例先失败）
```

- [ ] **Step 3: 最小实现格式校验**

实现要求：
1. `entries` 必须是非空 `Map<String,String>`。
2. key/value 都必须非空白字符串。
3. 显式拒绝 pair-object 语义形状（`sourceTerm/targetTerm`）。
4. 不做任何自动转换。

- [ ] **Step 4: 跑绿测**

Run:
```powershell
mvn -q "-Dtest=ReviewToolDecisionContractValidatorTest" test
```

Expected:
```text
BUILD SUCCESS
```

---

## 后置项说明（原 Task 3 / Task 4，不实施）

### 后置项 1（原 Task 3）
不做 repair 下沉到 `OpenAiCompatibleReviewAgentStructuredGenerationClient`。

后置原因：
1. 这是责任边界变更，不是本轮最小修复。
2. 容易把“格式修复”和“业务放行”耦合，产生语义漂移。
3. 当前先把门槛与校验收紧，已可避免错误写表落库。

### 后置项 2（原 Task 4）
不做 prompt/registry 扩散式对齐改造（除非为 Task 1/2 直接必需）。

后置原因：
1. 文案扩散会放大改动面，不利于本轮快速止血。
2. 本轮核心在执行边界，而不是提示语覆盖率。
3. 先用测试锁定行为，再考虑文案一致性扩展。

---

## Final Verification

按顺序执行：

1. `mvn -q "-Dtest=ReviewToolExecutorGuardrailTest" test`
2. `mvn -q "-Dtest=ReviewToolDecisionContractValidatorTest" test`
3. `mvn -q "-Dtest=ReviewToolExecutorGuardrailTest,ReviewToolDecisionContractValidatorTest,AutonomousProjectReviewAgentTest,PostDraftReviewAgentEndToEndSmokeTest" test`
4. `mvn -q test -DskipITs`

说明：
1. 第 1、2 条是本轮核心边界回归。
2. 第 3 条用于确认未破坏 agent 主流程。
3. 第 4 条作为最终全量非 IT 验证。

---

## 交付要求

实施完成后必须在结果中列出：
1. 实际改动文件。
2. 实际执行命令。
3. 每条命令结果（PASS/FAIL）。
4. 是否严格只实现 Task 1/2。
5. Task 3/4 明确未做。
