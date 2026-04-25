# Workflow Fixed LLM Timeout Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把当前 workflow 链路中所有固定写死为 60 秒的 LLM HTTP 超时统一提升到 600 秒，减少长文本运行中的本地超时失败。

**Architecture:** 这次只改固定超时常量，不改任何输入输出契约、不改持久化、不改动态超时策略。新增一个共享超时工具类，所有固定 60 秒配置统一引用同一个 600 秒常量，降低分散修改带来的风险。

**Tech Stack:** Java 17, Spring Boot, LangChain4j, Maven, JUnit 5

---

### Task 1: 为固定超时提供共享常量

**Files:**
- Create: `src/main/java/io/quillloom/infrastructure/llm/WorkflowFixedLlmTimeouts.java`
- Create: `src/test/java/io/quillloom/infrastructure/llm/WorkflowFixedLlmTimeoutsTest.java`

- [ ] **Step 1: 写失败测试，锁定固定超时为 600 秒**

```java
package io.quillloom.infrastructure.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowFixedLlmTimeoutsTest {

    @Test
    void shouldUseTenMinutesForFixedWorkflowLlmTimeout() {
        assertEquals(Duration.ofMinutes(10), WorkflowFixedLlmTimeouts.standardTimeout());
    }
}
```

- [ ] **Step 2: 运行测试，确认当前失败**

Run: `mvn -q "-Dtest=WorkflowFixedLlmTimeoutsTest" test`
Expected: FAIL，因为类和方法还不存在。

- [ ] **Step 3: 实现共享超时工具类**

```java
package io.quillloom.infrastructure.llm;

import java.time.Duration;

public final class WorkflowFixedLlmTimeouts {

    private static final Duration STANDARD_TIMEOUT = Duration.ofMinutes(10);

    private WorkflowFixedLlmTimeouts() {
    }

    public static Duration standardTimeout() {
        return STANDARD_TIMEOUT;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q "-Dtest=WorkflowFixedLlmTimeoutsTest" test`
Expected: PASS

### Task 2: 用共享常量替换固定 60 秒超时

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/translation/ChunkTranslatorConfiguration.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/chunksegmentation/ChunkSegmentationPlanGeneratorConfiguration.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/chunkannotation/ChunkAnnotationGeneratorConfiguration.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeNeedPlannerConfiguration.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeSearchOrganizerConfiguration.java`

- [ ] **Step 1: 把所有固定 `.timeout(Duration.ofSeconds(60))` 统一替换成共享常量**

目标形式：

```java
.timeout(WorkflowFixedLlmTimeouts.standardTimeout())
```

- [ ] **Step 2: 保持其余逻辑不变**

不修改：
- properties 结构
- validate 逻辑
- response schema
- retry 语义
- workflow / persistence 契约

### Task 3: 回归测试与交接文档同步

**Files:**
- Modify: `docs/handoff.md`
- Test: `src/test/java/io/quillloom/infrastructure/llm/WorkflowFixedLlmTimeoutsTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/translation/ChunkTranslatorConfigurationTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/translation/OpenAiCompatibleLlmChunkTranslationClientTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/translation/LlmChunkTranslatorTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/chunksegmentation/LlmChunkSegmentationPlanGeneratorTest.java`

- [ ] **Step 1: 在交接文档补充固定超时统一提升结论**

写明：
- workflow 链路中当前固定 60 秒的 LLM HTTP 超时已统一提高到 600 秒
- 不影响动态超时环节
- 不影响翻译/预处理/持久化契约

- [ ] **Step 2: 运行定向回归**

Run: `mvn -q "-Dtest=WorkflowFixedLlmTimeoutsTest,ChunkTranslatorConfigurationTest,OpenAiCompatibleLlmChunkTranslationClientTest,LlmChunkTranslatorTest,LlmChunkSegmentationPlanGeneratorTest" test`
Expected: PASS

