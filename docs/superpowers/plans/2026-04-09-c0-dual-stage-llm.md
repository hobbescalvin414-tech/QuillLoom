# C0 Dual-Stage LLM Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 C0 重构为“LLM 选择知识需求 -> 搜索 -> LLM 整理证据 -> 本地规范化建卡”的受控流水线，不改 A/B/D，不恢复 heuristic fallback。

**Architecture:** 在 C0 内新增 `KnowledgeNeed` 和 `OrganizedKnowledgeEvidence` 两个中间对象。第一阶段用 `LlmKnowledgeNeedPlanner` 从 `ChunkAnnotation` 中选出少量值得搜索的知识需求；第二阶段用 `LlmKnowledgeSearchResultOrganizer` 将搜索命中整理为结构化证据；最后由本地 `KnowledgeCardDraftNormalizer` 规范化后落成 `KnowledgeCard`。`KnowledgeSearchGate` 退化为薄门槛，只做限流、去重和知识库覆盖拦截。

**Tech Stack:** Spring Boot, Java 21, LangChain4j OpenAI-compatible chat model, Jackson, JUnit 5, Maven

---

### Task 1: 写入失败测试并锁定新边界

**Files:**
- Modify: `src/test/java/io/quillloom/infrastructure/preprocess/C0NetworkKnowledgeCardFlowTest.java`
- Create: `src/test/java/io/quillloom/infrastructure/preprocess/LlmKnowledgeNeedPlannerTest.java`
- Create: `src/test/java/io/quillloom/infrastructure/preprocess/KnowledgeCardDraftNormalizerTest.java`

- [ ] **Step 1: 写 `LlmKnowledgeNeedPlannerTest`，断言 planner 只产少量 need，并把 Victorian church etiquette 判成 `CULTURAL_BACKGROUND`**

```java
@Test
void shouldSelectOnlyStableKnowledgeNeedsForBackgroundQuestion() {
    ChunkAnnotation chunk = new ChunkAnnotation(
            new ChunkDescriptor("chunk-1", 1, "block-1", 0, 120, "Alice visited St. Mary parish."),
            "Alice needs etiquette background before visiting the parish.",
            List.of("Alice", "St. Mary parish"),
            List.of("What are the rules of Victorian church etiquette?"),
            List.of("Religious background may affect tone and address."),
            List.of("church etiquette", "parish")
    );
    LlmKnowledgeNeedPlanner planner = new LlmKnowledgeNeedPlanner(
            new KnowledgeNeedPlanningPromptRenderer(),
            prompt -> """
                    {
                      "needs": [
                        {
                          "shouldSearch": true,
                          "cardType": "CULTURAL_BACKGROUND",
                          "queryText": "Victorian church etiquette rules and forms of address",
                          "anchorNames": ["St. Mary parish"],
                          "keywords": ["Victorian", "church", "etiquette", "address"],
                          "originRefs": ["chunk:chunk-1#backgroundQuestion:1"],
                          "reason": "需要礼仪背景来稳定称呼与叙述语气。",
                          "priority": 1
                        }
                      ]
                    }
                    """,
            new KnowledgeNeedPlanningResultParser()
    );

    List<KnowledgeNeed> needs = planner.plan(chunk);

    assertEquals(1, needs.size());
    assertEquals(KnowledgeCardType.CULTURAL_BACKGROUND, needs.getFirst().cardType());
    assertEquals("Victorian church etiquette rules and forms of address", needs.getFirst().queryText());
    assertFalse(needs.getFirst().anchorNames().contains("What are the rules of Victorian church etiquette?"));
    assertEquals(List.of("St. Mary parish"), needs.getFirst().anchorNames());
}
```

- [ ] **Step 2: 运行 planner 测试，确认当前代码失败**

Run: `mvn -q "-Dtest=LlmKnowledgeNeedPlannerTest" test`  
Expected: FAIL，提示 `LlmKnowledgeNeedPlanner` 或 `KnowledgeNeed` 不存在

- [ ] **Step 3: 写 `KnowledgeCardDraftNormalizerTest`，断言 `sourceRefs` 只保留 URL，完整问题句不会落到 `anchorNames`**

```java
@Test
void shouldNormalizeEvidenceIntoStableKnowledgeCardDraft() {
    OrganizedKnowledgeEvidence evidence = new OrganizedKnowledgeEvidence(
            KnowledgeCardType.CULTURAL_BACKGROUND,
            "Victorian church etiquette",
            "Visitors were expected to lower their voice and use formal forms of address.",
            List.of("What are the rules of Victorian church etiquette?", "Alice", "St. Mary parish"),
            List.of("https://example.com/church-etiquette"),
            List.of("chunk:chunk-1#backgroundQuestion:1"),
            "searxng",
            "HIGH"
    );

    KnowledgeCardDraft draft = new KnowledgeCardDraftNormalizer().normalize(
            "chunk-1",
            List.of("Alice", "St. Mary parish"),
            evidence
    );

    assertEquals(List.of("Alice", "St. Mary parish"), draft.anchorNames());
    assertEquals(List.of("https://example.com/church-etiquette"), draft.sourceRefs());
}
```

- [ ] **Step 4: 运行 normalizer 测试，确认当前代码失败**

Run: `mvn -q "-Dtest=KnowledgeCardDraftNormalizerTest" test`  
Expected: FAIL，提示 `OrganizedKnowledgeEvidence` / `KnowledgeCardDraftNormalizer` 不存在

- [ ] **Step 5: 扩写 `C0NetworkKnowledgeCardFlowTest`，断言 `sourceRefs` 不混 provenance/provider，且 `anchorNames` 不含完整问题句**

```java
assertTrue(cards.stream().allMatch(card -> card.sourceRefs().equals(List.of("https://example.com/church-etiquette", "https://example.com/parish-customs"))));
assertTrue(cards.stream().noneMatch(card -> card.anchorNames().contains("What are the rules of Victorian church etiquette?")));
```

- [ ] **Step 6: 运行 C0 定向测试，确认在当前实现下失败**

Run: `mvn -q "-Dtest=C0NetworkKnowledgeCardFlowTest" test`  
Expected: FAIL，卡类型/锚点/来源字段不符合新断言

### Task 2: 实现第一阶段 LLM knowledge need selection

**Files:**
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeNeed.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeNeedPlanningPromptRenderer.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeNeedPlanningResult.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeNeedPlanningResultParser.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/LlmKnowledgeNeedPlanner.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/LlmKnowledgeNeedPlannerClient.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeSearchGate.java`

- [ ] **Step 1: 添加 `KnowledgeNeed` 和 planner 结果对象，显式承载 `originRefs / reason / priority`**

```java
public record KnowledgeNeed(
        KnowledgeCardType cardType,
        String queryText,
        List<String> anchorNames,
        List<String> keywords,
        List<String> originRefs,
        String reason,
        int priority
) {
}
```

- [ ] **Step 2: 实现 `KnowledgeNeedPlanningPromptRenderer`，把 chunk 标注渲染成受控 JSON 任务**

```java
public String render(ChunkAnnotation chunk) {
    return """
            你是 C0 的知识需求规划器。只输出 JSON。
            目标：从 chunk 标注中选出少量真正值得联网搜索的知识需求。
            约束：
            1. 只选择对翻译直接有帮助的知识。
            2. anchorNames 只能包含稳定锚点，不能包含完整问题句。
            3. cardType 只能从固定枚举中选择。
            4. 如果不值得搜索，返回空 needs。
            chunkId: %s
            summary: %s
            entities: %s
            backgroundQuestions: %s
            translationRisks: %s
            keyExpressions: %s
            """.formatted(...);
}
```

- [ ] **Step 3: 实现 `KnowledgeNeedPlanningResultParser`，严格解析并校验 JSON 输出**

```java
public List<KnowledgeNeed> parse(ChunkAnnotation chunk, String raw) {
    KnowledgeNeedPlanningResult result = objectMapper.readValue(raw, KnowledgeNeedPlanningResult.class);
    return safeList(result.needs()).stream()
            .filter(item -> item != null && item.shouldSearch())
            .map(item -> new KnowledgeNeed(
                    KnowledgeCardType.valueOf(item.cardType()),
                    item.queryText().trim(),
                    normalizeAnchors(item.anchorNames(), chunk.entities()),
                    normalizeKeywords(item.keywords()),
                    normalizeOriginRefs(item.originRefs(), chunk.chunk().chunkId()),
                    defaultText(item.reason(), ""),
                    Math.max(1, item.priority())
            ))
            .limit(3)
            .toList();
}
```

- [ ] **Step 4: 实现 `LlmKnowledgeNeedPlanner`，失败直接抛错，不回退规则 planner**

```java
public List<KnowledgeNeed> plan(ChunkAnnotation chunk) {
    String prompt = promptRenderer.render(chunk);
    String response = client.generate(prompt);
    return parser.parse(chunk, response);
}
```

- [ ] **Step 5: 将 `KnowledgeSearchGate` 改成薄 gate，输入从 `KnowledgeSearchQuery` 切到 `KnowledgeNeed`**

```java
public List<KnowledgeNeed> filterNeeds(ChunkAnnotation chunk,
                                       ProjectKnowledgeBase knowledgeBase,
                                       List<KnowledgeNeed> plannedNeeds) {
    List<KnowledgeNeed> allowed = new ArrayList<>();
    for (KnowledgeNeed need : safeList(plannedNeeds)) {
        if (isCoveredByKnowledgeBase(need, knowledgeBase)) {
            continue;
        }
        allowed.add(need);
        if (allowed.size() >= Math.max(0, properties.getMaxQueriesPerChunk())) {
            break;
        }
    }
    return List.copyOf(allowed);
}
```

- [ ] **Step 6: 运行 planner 定向测试，确认通过**

Run: `mvn -q "-Dtest=LlmKnowledgeNeedPlannerTest" test`  
Expected: PASS

### Task 3: 实现第二阶段 evidence organize 与本地 draft 规范化

**Files:**
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/OrganizedKnowledgeEvidence.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeCardDraft.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeCardDraftNormalizer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/LlmKnowledgeSearchResultOrganizer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeSearchResultOrganizer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeSearchResultCondenser.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/NetworkBackedKnowledgeSearchTool.java`

- [ ] **Step 1: 将 organizer 输出升级为 `OrganizedKnowledgeEvidence`，分离 `evidenceUrls / originRefs / searchProvider`**

```java
public record OrganizedKnowledgeEvidence(
        KnowledgeCardType cardType,
        String title,
        String content,
        List<String> anchorNames,
        List<String> evidenceUrls,
        List<String> originRefs,
        String searchProvider,
        String confidence
) {
}
```

- [ ] **Step 2: 修改 organizer 抽象与 condenser，实现结构化 evidence 输出**

```java
public interface KnowledgeSearchResultOrganizer {

    OrganizedKnowledgeEvidence organize(ChunkAnnotation chunk,
                                        KnowledgeNeed need,
                                        List<KnowledgeSearchHit> hits);
}
```

- [ ] **Step 3: 实现 `KnowledgeCardDraftNormalizer`，只把 URL 写入 `sourceRefs`，过滤整句锚点**

```java
public KnowledgeCardDraft normalize(String chunkId,
                                    List<String> chunkEntities,
                                    OrganizedKnowledgeEvidence evidence) {
    List<String> anchors = mergeStableAnchors(chunkEntities, evidence.anchorNames());
    List<String> sourceRefs = filterUrls(evidence.evidenceUrls());
    return new KnowledgeCardDraft(
            evidence.cardType(),
            normalizeTitle(evidence.title()),
            normalizeContent(evidence.content()),
            anchors,
            sourceRefs,
            List.of(chunkId)
    );
}
```

- [ ] **Step 4: 让 `NetworkBackedKnowledgeSearchTool` 接收 `KnowledgeNeed`，对每个 need 搜索并交给 organizer**

```java
public List<OrganizedKnowledgeEvidence> search(ChunkAnnotation chunk,
                                               List<KnowledgeNeed> needs) {
    List<OrganizedKnowledgeEvidence> results = new ArrayList<>();
    for (KnowledgeNeed need : needs) {
        OrganizedKnowledgeEvidence evidence = resultOrganizer.organize(chunk, need, knowledgeSearchClient.search(toQuery(need)));
        if (evidence != null) {
            results.add(evidence);
        }
    }
    return List.copyOf(results);
}
```

- [ ] **Step 5: 运行 normalizer 与 organizer 测试，确认通过**

Run: `mvn -q "-Dtest=KnowledgeCardDraftNormalizerTest,KnowledgeSearchResultOrganizerParserTest,KnowledgeSearchResultCondenserTest" test`  
Expected: PASS

### Task 4: 接入 `ToolDrivenKnowledgeEnricher` 并保持 C0 出口稳定

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/ToolDrivenKnowledgeEnricher.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeSearchTool.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeSearchToolConfiguration.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/ChunkAwareKnowledgeSearchQueryPlanner.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeSearchTypeResolver.java`

- [ ] **Step 1: 在 `ToolDrivenKnowledgeEnricher` 中接入 `LlmKnowledgeNeedPlanner + filterNeeds + search + normalize`**

```java
List<KnowledgeNeed> plannedNeeds = knowledgeNeedPlanner.plan(chunk);
List<KnowledgeNeed> eligibleNeeds = knowledgeSearchGate.filterNeeds(chunk, currentKnowledgeBase, plannedNeeds);
for (OrganizedKnowledgeEvidence evidence : knowledgeSearchTool.search(chunk, eligibleNeeds)) {
    KnowledgeCardDraft draft = knowledgeCardDraftNormalizer.normalize(chunk.chunk().chunkId(), safeList(chunk.entities()), evidence);
    KnowledgeCard card = toKnowledgeCard(chunk, draft);
    mergeKnowledgeCard(mergedCards, card);
}
```

- [ ] **Step 2: 保持对外仍落成现有 `KnowledgeCard`，不改 A/B/D 依赖**

```java
private KnowledgeCard toKnowledgeCard(ChunkAnnotation chunk, KnowledgeCardDraft draft) {
    return new KnowledgeCard(
            buildCardId(chunk, draft.title()),
            draft.cardType(),
            draft.title(),
            draft.content(),
            buildKeywords(chunk, draft),
            draft.anchorNames(),
            draft.sourceRefs(),
            "PROJECT",
            draft.applicableChunkIds()
    );
}
```

- [ ] **Step 3: 移除 `ChunkAwareKnowledgeSearchQueryPlanner` 在主链路中的直接使用；`KnowledgeSearchTypeResolver` 降为校验辅助**

```java
// 主链路不再从 chunk 直接规则展开 query。
// typeResolver 仅在 parser 校验未知枚举或测试辅助路径中使用。
```

- [ ] **Step 4: 更新配置装配，planner/organizer 任一失败都直接失败，不回退 heuristic**

```java
if (!properties.isEnabled()) {
    throw new IllegalStateException("C0 network knowledge search is disabled");
}
```

- [ ] **Step 5: 运行 C0 定向测试，确认通过**

Run: `mvn -q "-Dtest=C0NetworkKnowledgeCardFlowTest" test`  
Expected: PASS

### Task 5: 文档同步与最终验证

**Files:**
- Modify: `docs/c0-knowledge-improvement-plan.md`
- Modify: `docs/handoff.md`

- [ ] **Step 1: 更新 `docs/c0-knowledge-improvement-plan.md`，把链路改成“双阶段 LLM + 本地规范化”**

```text
当前 C0 主链路：
1. LlmKnowledgeNeedPlanner 选择知识需求
2. KnowledgeSearchGate 做薄门槛过滤
3. NetworkBackedKnowledgeSearchTool 执行搜索
4. LlmKnowledgeSearchResultOrganizer 整理证据
5. KnowledgeCardDraftNormalizer 规范化建卡草稿
6. ToolDrivenKnowledgeEnricher 落库
```

- [ ] **Step 2: 在 `docs/handoff.md` 末尾追加本轮实现结果和验证命令**

```text
2026-04-09 C0 重构补充：
1. C0 已改为双阶段 LLM 流水线。
2. Query selection 不再由规则 planner 主导。
3. sourceRefs 只保留外部 evidence URL。
4. planner / organizer / network 任一失败都直接失败。
```

- [ ] **Step 3: 运行最终定向验证**

Run: `mvn -q "-Dtest=LlmKnowledgeNeedPlannerTest,KnowledgeCardDraftNormalizerTest,KnowledgeSearchResultCondenserTest,KnowledgeSearchResultOrganizerParserTest,C0NetworkKnowledgeCardFlowTest" test`  
Expected: PASS

- [ ] **Step 4: 记录当前工作区阻塞项**

Run: `mvn -q "-Dtest=C0NetworkKnowledgeCardFlowTest" test`  
Expected: 如果仍被其他翻译测试的 `testCompile` 错误阻塞，在文档和最终汇报里明确写出阻塞文件与错误，不声称全量验证通过
