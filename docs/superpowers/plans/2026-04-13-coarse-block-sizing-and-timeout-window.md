# Coarse Block Sizing And Timeout Window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Agent A 的粗分块比当前稍细一点，同时延长按文本长度计算出的超时窗口，减少链路因单次超时直接中断的概率。

**Architecture:** 只改 Agent A 的 coarse block 规划提示和超时策略，不重做 B/C0/D，不退回大 orchestrator。粗分块层保留“章节/场景/时空/视角变化优先切分”的原则，但从“宁可少切”收紧为“默认偏大但需要受目标体量约束”；超时层继续显式抛异常，不做静默 fallback，只把基础超时和按文本长度扩展的窗口放宽。

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Maven, existing preprocess pipeline

---

### Task 1: 收紧 Agent A 粗分块提示

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/coarsechunkplanning/CoarseChunkPlanningPromptRenderer.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/coarsechunkplanning/CoarseChunkPlanningPromptRendererTest.java`

- [ ] **Step 1: 先写失败测试**

补一条测试，要求 prompt 明确表达这些约束：
- 粗分块仍然偏大，但不能无限并块
- 需要控制在“稍细于当前”的目标体量
- 当连续段落已经明显过长时，允许在自然段落边界切开
- 禁止为了凑长度机械切断一个完整动作/对话单元

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q "-Dtest=CoarseChunkPlanningPromptRendererTest" test`
Expected: FAIL，缺少新的提示语断言

- [ ] **Step 3: 最小修改 prompt**

在 `CoarseChunkPlanningPromptRenderer` 中把以下语义写清楚：
- “粗分块默认偏大，但不能一味并块”
- “若一个 coarse block 已明显过长，应优先在自然段落边界切开”
- “保持章节/场景/时间/空间/视角切换优先”
- “不要为凑长度打断完整动作或强连续对话”

- [ ] **Step 4: 回跑测试**

Run: `mvn -q "-Dtest=CoarseChunkPlanningPromptRendererTest" test`
Expected: PASS

### Task 2: 放宽按文本长度扩展的超时窗口

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/TextLengthTimeoutPolicy.java`
- Modify: `src/test/java/io/quillloom/infrastructure/preprocess/TextLengthTimeoutPolicyTest.java`

- [ ] **Step 1: 先写失败测试**

增加断言，覆盖：
- 短文本仍用基础超时
- 超过阈值后，额外时间增长更快
- 接近大文本时更容易打到更高上限

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q "-Dtest=TextLengthTimeoutPolicyTest" test`
Expected: FAIL，当前超时增长过慢

- [ ] **Step 3: 最小修改算法**

把 `TextLengthTimeoutPolicy` 调整为更保守的超时计算：
- 保留基础超时
- 保留按文本长度扩展
- 但在超出阈值后更早进入额外 step
- 继续受 `maxTimeoutSeconds` 约束

- [ ] **Step 4: 回跑测试**

Run: `mvn -q "-Dtest=TextLengthTimeoutPolicyTest" test`
Expected: PASS

### Task 3: 调整默认超时配置

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/bookanalysis/BookAnalysisLlmProperties.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/coarsechunkplanning/CoarseChunkPlanningLlmProperties.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 调整默认值**

把 A 相关阶段默认值适度上调，例如：
- `base-timeout-seconds`
- `timeout-step-chars`
- `timeout-step-seconds`
- `max-timeout-seconds`

要求：
- Book Analysis 和 Coarse Chunk Planning 都放宽
- 仍然保留显式最大值，不做无限等待

- [ ] **Step 2: 跑相关测试**

Run: `mvn -q "-Dtest=TextLengthTimeoutPolicyTest,LlmCoarseChunkPlanGeneratorTest" test`
Expected: PASS

### Task 4: 同步交接文档并做定向验证

**Files:**
- Modify: `docs/handoff.md`

- [ ] **Step 1: 更新交接文档**

写明：
- 粗分块从“强并块”收紧为“偏大但受目标体量约束”
- 超时窗口已放宽，但仍显式抛异常

- [ ] **Step 2: 跑最终定向验证**

Run: `mvn -q "-Dtest=CoarseChunkPlanningPromptRendererTest,TextLengthTimeoutPolicyTest,LlmCoarseChunkPlanGeneratorTest" test`
Expected: PASS
