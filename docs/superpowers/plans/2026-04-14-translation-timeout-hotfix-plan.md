# [OUTDATED - 已被 2026-04-14-workflow-fixed-llm-timeout-unification-plan.md 取代] Translation Timeout Hotfix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把翻译阶段单次 LLM HTTP 超时从当前固定 60 秒显著提高，避免长文本 smoke test 在翻译阶段因本地超时提前失败。

**Architecture:** 这次只做最小热修。仅修改翻译客户端配置层的固定超时值，不改翻译输入输出契约，不改工作流，不改持久化对象。补一个最小回归测试锁定新的固定超时常量。

**Tech Stack:** Java 17, Spring Boot, LangChain4j, Maven, JUnit 5

---

### Task 1: 锁定新的翻译超时常量

**Files:**
- Create: `src/test/java/io/quillloom/infrastructure/translation/ChunkTranslatorConfigurationTest.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/ChunkTranslatorConfiguration.java`

- [ ] **Step 1: 写一个失败的测试，锁定翻译阶段固定超时值**

```java
package io.quillloom.infrastructure.translation;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkTranslatorConfigurationTest {

    @Test
    void shouldUseExtendedFixedTimeoutForChunkTranslation() {
        assertEquals(Duration.ofSeconds(600), ChunkTranslatorConfiguration.translationTimeout());
    }
}
```

- [ ] **Step 2: 运行测试，确认当前失败**

Run: `mvn -q "-Dtest=ChunkTranslatorConfigurationTest" test`
Expected: FAIL，因为 `translationTimeout()` 还不存在。

- [ ] **Step 3: 在配置类里补固定超时常量和方法，并改为使用该值**

```java
static Duration translationTimeout() {
    return Duration.ofSeconds(600);
}
```

并把：

```java
.timeout(Duration.ofSeconds(60))
```

改为：

```java
.timeout(translationTimeout())
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q "-Dtest=ChunkTranslatorConfigurationTest" test`
Expected: PASS

### Task 2: 回归翻译相关单测并同步交接文档

**Files:**
- Modify: `docs/handoff.md`
- Test: `src/test/java/io/quillloom/infrastructure/translation/ChunkTranslatorConfigurationTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/translation/LlmChunkTranslatorTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/translation/OpenAiCompatibleLlmChunkTranslationClientTest.java`

- [ ] **Step 1: 在交接文档补充这次热修结论**

写明：
- 翻译阶段单次 HTTP 超时已从固定 60 秒提高到固定 600 秒
- 这是为了长文本 smoke test 的最小热修
- 不影响翻译契约与持久化契约

- [ ] **Step 2: 运行翻译相关回归测试**

Run: `mvn -q "-Dtest=ChunkTranslatorConfigurationTest,OpenAiCompatibleLlmChunkTranslationClientTest,LlmChunkTranslatorTest" test`
Expected: PASS

