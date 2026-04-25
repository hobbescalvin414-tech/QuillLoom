# Chunk Segmentation Terminal Coverage Repair Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 chunk segmentation 增加一次显式、受控、可诊断的修复式重试，处理“未覆盖最后一个段落”导致整条长文本 workflow 失败的问题，同时不改变 B 的输入和输出契约。

**Architecture:** 保持 `ChunkSegmentationTaskInput`、`ChunkSegmentationPlanningResult` 与 normalizer 语义不变，只在 `LlmChunkSegmentationPlanGenerator` 内部增加一轮 repair prompt。第一次 LLM 结果若命中结构错误，则记录失败事件并发起一次修复式重试；若第二次仍失败，则显式抛出“repair exhausted”错误。严格不做静默 fallback，不在 normalizer 中偷偷补尾段。

**Tech Stack:** Java 21, Spring Boot, JUnit 5

---

### Task 1: 写 repair 行为失败测试

**Files:**
- Modify: `src/test/java/io/quillloom/infrastructure/preprocess/chunksegmentation/LlmChunkSegmentationPlanGeneratorTest.java`

- [ ] **Step 1: 写失败测试，约束首次漏尾段时发起一次 repair 并成功返回**

```java
@Test
void shouldRetryOnceWhenFirstResponseMissesFinalParagraph() {
    AtomicInteger callCount = new AtomicInteger();
    WorkflowTraceRecorder traceRecorder = new WorkflowTraceRecorder();
    traceRecorder.startRun("run-seg-repair-1", "draft-workflow", input.projectId());

    LlmChunkSegmentationPlanClient client = new LlmChunkSegmentationPlanClient() {
        @Override
        public LlmChunkSegmentationPlanClientResponse generateDetailed(String prompt) {
            if (callCount.getAndIncrement() == 0) {
                return new LlmChunkSegmentationPlanClientResponse(
                        "{\"boundaries\":[{\"endParagraphIndex\":1,\"boundaryHint\":\"stops too early\"}]}",
                        new ChunkSegmentationPlanningLlmResult(List.of(
                                new ChunkSegmentationPlanningLlmBoundary(1, "stops too early")
                        ))
                );
            }
            assertTrue(prompt.contains("未覆盖最后一个段落"));
            return new LlmChunkSegmentationPlanClientResponse(
                    "{\"boundaries\":[{\"endParagraphIndex\":1,\"boundaryHint\":\"first\"},{\"endParagraphIndex\":2,\"boundaryHint\":\"second\"}]}",
                    new ChunkSegmentationPlanningLlmResult(List.of(
                            new ChunkSegmentationPlanningLlmBoundary(1, "first"),
                            new ChunkSegmentationPlanningLlmBoundary(2, "second")
                    ))
            );
        }
    };

    LlmChunkSegmentationPlanGenerator generator = new LlmChunkSegmentationPlanGenerator(
            new ChunkSegmentationPromptRenderer(),
            new ChunkSegmentationRepairPromptRenderer(),
            client,
            new ChunkSegmentationPlanningLlmResultNormalizer(),
            traceRecorder
    );

    var result = generator.generate(input);
    var events = traceRecorder.snapshotEvents();

    assertEquals(2, callCount.get());
    assertEquals(2, result.boundaries().size());
    assertTrue(events.stream().anyMatch(event -> event.eventType().equals("chunk_segmentation_repair_requested")));
    assertTrue(events.stream().anyMatch(event -> event.eventType().equals("chunk_segmentation_repair_succeeded")));
}
```

- [ ] **Step 2: 写失败测试，约束 repair 失败时显式报错而不是静默兜底**

```java
@Test
void shouldFailWhenRepairStillMissesFinalParagraph() {
    LlmChunkSegmentationPlanClient client = new LlmChunkSegmentationPlanClient() {
        @Override
        public LlmChunkSegmentationPlanClientResponse generateDetailed(String prompt) {
            return new LlmChunkSegmentationPlanClientResponse(
                    "{\"boundaries\":[{\"endParagraphIndex\":1,\"boundaryHint\":\"still too early\"}]}",
                    new ChunkSegmentationPlanningLlmResult(List.of(
                            new ChunkSegmentationPlanningLlmBoundary(1, "still too early")
                    ))
            );
        }
    };

    LlmChunkSegmentationPlanGenerator generator = new LlmChunkSegmentationPlanGenerator(
            new ChunkSegmentationPromptRenderer(),
            new ChunkSegmentationRepairPromptRenderer(),
            client,
            new ChunkSegmentationPlanningLlmResultNormalizer()
    );

    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> generator.generate(input));

    assertTrue(ex.getMessage().contains("chunk segmentation repair exhausted"));
    assertTrue(ex.getMessage().contains("firstFailure="));
    assertTrue(ex.getMessage().contains("secondFailure="));
}
```

- [ ] **Step 3: 运行测试，确认因缺少 repair 流程而失败**

Run: `mvn -q "-Dtest=LlmChunkSegmentationPlanGeneratorTest" test`
Expected: FAIL，提示缺少 `ChunkSegmentationRepairPromptRenderer` 或缺少 repair 事件/逻辑

### Task 2: 实现 repair prompt 与一次重试逻辑

**Files:**
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/chunksegmentation/ChunkSegmentationRepairIssue.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/chunksegmentation/ChunkSegmentationRepairPromptRenderer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/chunksegmentation/LlmChunkSegmentationPlanGenerator.java`

- [ ] **Step 1: 新增 repair issue 与 prompt renderer**

```java
public record ChunkSegmentationRepairIssue(
        String detail,
        String rawResponse
) {
}
```

```java
@Component
public class ChunkSegmentationRepairPromptRenderer {

    public String render(String originalPrompt, ChunkSegmentationRepairIssue issue) {
        StringBuilder builder = new StringBuilder();
        builder.append("你上一次 chunk segmentation 输出失败了。\n");
        builder.append("这不是让你重做任务，而是只修复边界结构问题。\n");
        builder.append("上一次失败原因：").append(nullToEmpty(issue.detail())).append("\n");
        builder.append("请重新输出完整、可解析、按顺序递增的 boundaries。\n");
        builder.append("只返回 JSON 对象，字段仍然只允许有 boundaries。\n");
        builder.append("每个边界仍然只包含 endParagraphIndex、boundaryHint。\n");
        builder.append("endParagraphIndex 必须严格递增，最后一个边界必须覆盖最后一段。\n");
        builder.append("不要输出解释，不要输出 Markdown。\n\n");
        builder.append("【上一次原始输出】\n");
        builder.append(nullToEmpty(issue.rawResponse())).append("\n\n");
        builder.append("【原始任务】\n");
        builder.append(nullToEmpty(originalPrompt));
        return builder.toString();
    }
}
```

- [ ] **Step 2: 在 generator 中增加一次 repair 重试**

```java
try {
    return normalizeFirstResponse(...);
} catch (IllegalStateException ex) {
    record("chunk_segmentation_llm_failed", FAILED, ...);
    String repairPrompt = repairPromptRenderer.render(prompt, new ChunkSegmentationRepairIssue(...));
    record("chunk_segmentation_repair_requested", SUCCEEDED, ...);
    var repairedResponse = llmClient.generateDetailed(repairPrompt);
    record("chunk_segmentation_repair_llm_responded", SUCCEEDED, ...);
    try {
        var normalized = resultNormalizer.normalize(input, repairedResponse.result());
        record("chunk_segmentation_normalized", SUCCEEDED, ...);
        record("chunk_segmentation_repair_succeeded", SUCCEEDED, Map.of("attempts", 2));
        return normalized;
    } catch (IllegalStateException retryEx) {
        record("chunk_segmentation_repair_failed", FAILED, ...);
        throw new IllegalStateException(
                "chunk segmentation repair exhausted. attempts=2, firstFailure=" + ex.getMessage() + ", secondFailure=" + retryEx.getMessage(),
                retryEx
        );
    }
}
```

- [ ] **Step 3: 保持契约不变**

检查并保证：

1. 不修改 `ChunkSegmentationTaskInput`
2. 不修改 `ChunkSegmentationPlanningResult`
3. 不修改 `ChunkBoundaryPlan`
4. 不在 normalizer 中做自动补尾段

- [ ] **Step 4: 运行测试，确认 repair 测试通过**

Run: `mvn -q "-Dtest=LlmChunkSegmentationPlanGeneratorTest" test`
Expected: PASS

### Task 3: 回归验证

**Files:**
- Modify: `docs/handoff.md`

- [ ] **Step 1: 在交接文档补一句当前分段 repair 行为**

```markdown
1. chunk segmentation 当前已增加一次显式 repair 重试。
2. 只修复结构错误，不改变 B 输入/输出契约。
3. 若 repair 仍失败，仍然正式报错，不做静默 fallback。
```

- [ ] **Step 2: 运行相关回归测试**

Run: `mvn -q "-Dtest=LlmChunkSegmentationPlanGeneratorTest,ChunkAnnotationOrchestratorTest" test`
Expected: PASS

