# B Paragraph Boundary Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Agent B 的细分块边界从字符串 `endAnchor` 切换为段落编号 `endParagraphIndex`，同时保留现有 `boundaryHint` 和上下游职责边界。

**Architecture:** 复用已有 `ParagraphView`，让 B 的 prompt、LLM 结构化输出、normalizer、compiler 全部围绕段落边界工作。只改锚点表达，不改 C0、装配层、D，也不在本轮引入新的分块策略。

**Tech Stack:** Java 17, Spring Boot, LangChain4j JSON schema, JUnit 5, Maven

---

### Task 1: 切换 B 的边界 DTO 与 prompt/schema

**Files:**
- Modify: `src/main/java/io/quillloom/application/preprocess/model/ChunkBoundaryPlan.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/chunksegmentation/ChunkSegmentationPlanningLlmBoundary.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/chunksegmentation/ChunkSegmentationPromptRenderer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/chunksegmentation/OpenAiCompatibleLlmChunkSegmentationPlanClient.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/chunksegmentation/ChunkSegmentationPromptRendererTest.java`

- [ ] 把 `ChunkBoundaryPlan` 从 `endAnchor` 改为 `endParagraphIndex`
- [ ] 把 `ChunkSegmentationPlanningLlmBoundary` 改为 `Integer endParagraphIndex`
- [ ] 让 prompt 只展示当前 coarse block 的 `ParagraphView.renderIndexedView()`，要求模型返回 `endParagraphIndex`
- [ ] 让 OpenAI JSON schema 改成整数段号输出
- [ ] 更新 prompt 测试，验证 `paragraphView` 和 `endParagraphIndex`

### Task 2: 切换 B 的 normalizer 与 compiler

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/chunksegmentation/ChunkSegmentationPlanningLlmResultNormalizer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/chunksegmentation/ChunkDescriptorCompiler.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/chunksegmentation/LlmChunkSegmentationPlanGeneratorTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/chunksegmentation/ChunkDescriptorCompilerTest.java`

- [ ] normalizer 改为基于 `ParagraphView` 校验段号合法、递增、覆盖到 block 最后一段
- [ ] compiler 改为按段落 offset 切块，不再查找字符串锚点
- [ ] 更新生成器测试，验证段号输出与越界/未覆盖报错
- [ ] 更新 compiler 测试，验证按段落切块与递增校验

### Task 3: 修补测试支撑并验证 B 链闭合

**Files:**
- Modify: `src/test/java/io/quillloom/support/PreprocessTestSupport.java`
- Modify: `src/test/java/io/quillloom/infrastructure/preprocess/ChunkAnnotationOrchestratorTest.java`
- Verify: `mvn -q "-Dtest=ParagraphViewTest,ChunkSegmentationPromptRendererTest,LlmChunkSegmentationPlanGeneratorTest,ChunkDescriptorCompilerTest,ChunkAnnotationOrchestratorTest" test`
- Verify: `mvn -q -DskipTests compile`

- [ ] 把测试支撑中的 `ChunkBoundaryPlan` 构造全部切到段号
- [ ] 更新 `ChunkAnnotationOrchestratorTest` 里依赖旧锚点的断言
- [ ] 跑 B 的定向测试集合，确认全部通过
- [ ] 跑全量编译，确认没有遗留 `endAnchor` 访问穿帮