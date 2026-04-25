# Active Glossary Consistency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让初稿翻译阶段对人名地名等术语优先沿用全局一致译名，同时继续产出候选译名供后续审核 agent 使用。

**Architecture:** 采用方案 A 的语义，但先复用现有 `confirmedTerms` / `confirmedTermUpdates` 作为 draft-stage active glossary，避免大规模重命名稳定契约。通过强化 D 的 prompt、validator 和顺序传播规则，让首次出现的实体尽快进入 active glossary，后续 chunk 必须沿用；不同译名继续保留在 `candidateUpdates` 中。

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Maven

---

### Task 1: 补齐术语一致性失败测试

**Files:**
- Modify: `src/test/java/io/quillloom/infrastructure/translation/TranslationPromptRendererTest.java`
- Modify: `src/test/java/io/quillloom/infrastructure/translation/ChunkTranslationResultValidatorTest.java`
- Modify: `src/test/java/io/quillloom/application/translation/service/TranslationApplicationServiceTest.java`

- [ ] 增加 prompt 测试，验证未进入 active glossary 的实体也会被要求给出“当前生效译名”，并允许同时给出候选译名
- [ ] 增加 validator 测试，验证已有 active glossary 时不同译名只能保留为 candidate / decision note，不能覆盖 active
- [ ] 增加顺序翻译测试，验证第 1 个 chunk 产出的 active 译名会在后续 chunk 强制沿用，同时候选仍可保留

### Task 2: 调整 D 的输出语义与约束

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/translation/TranslationPromptRenderer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/ChunkTranslationResultValidator.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/OpenAiCompatibleLlmChunkTranslationClient.java`

- [ ] 把 prompt 中的 `confirmedTerms/confirmedTermUpdates` 语义改成“当前初稿阶段生效译名”，要求对未收敛实体尽早给出当前生效译名
- [ ] 保留 `candidateUpdates`，明确允许对同一 source term 继续记录其他候选译名
- [ ] 在 validator 中继续禁止覆盖已有 active glossary，并把冲突保留到 `decisionNotes`

### Task 3: 收紧顺序传播与装配输入

**Files:**
- Modify: `src/main/java/io/quillloom/application/translation/assembler/TranslationTaskInputAssembler.java`
- Modify: `src/main/java/io/quillloom/application/translation/service/TranslationApplicationService.java`
- Modify: `src/main/java/io/quillloom/domain/translation/ChunkTranslationDraft.java`

- [ ] 明确 `confirmedTerms` 在初稿阶段代表 active glossary
- [ ] 保持顺序翻译时只传播 active glossary，不让后续 chunk 覆盖已有生效译名
- [ ] 保持候选译名留存在 draft 结果中，供后续审核 agent 汇总使用

### Task 4: 跑定向验证

**Files:**
- Verify: `src/test/java/io/quillloom/infrastructure/translation/TranslationPromptRendererTest.java`
- Verify: `src/test/java/io/quillloom/infrastructure/translation/ChunkTranslationResultValidatorTest.java`
- Verify: `src/test/java/io/quillloom/application/translation/service/TranslationApplicationServiceTest.java`

- [ ] 运行 `mvn -q "-Dtest=TranslationPromptRendererTest,ChunkTranslationResultValidatorTest,TranslationApplicationServiceTest,LlmChunkTranslatorTest" test`
- [ ] 如涉及知识库或工作流回归，再补跑 `mvn -q "-Dtest=C0ToDIntegrationSmokeTest,BookWorkflowSmokeTest" test`
