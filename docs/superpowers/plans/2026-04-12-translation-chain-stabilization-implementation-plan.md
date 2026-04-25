# Translation Chain Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 收紧翻译主链中的错误全局约束传播，新增 C0 内生实体卡与保守 alias 归一能力，并为 D 增加可修复式软约束校验，同时保持现有链路能力不被砍掉。

**Architecture:** 本计划分三阶段实施。第一阶段先在 A 结果进入下游前增加 `globalConstraints` 执行层边界治理，切断错误“不译”硬约束污染源。第二阶段在 C0 现有知识增强主链旁挂接“书内证据驱动的内生实体卡”分支，并以保守、可追溯的方式处理 alias 归一。第三阶段在 D 侧保留现有两轮机制，新增“目标语言正文纯度”和“active glossary 正文合规”的 issue 检测与定向修订，不把主链改成机械 hard-fail 流水线。

**Tech Stack:** Java, Spring Boot, JUnit 5, existing QuillLoom preprocess/translation pipeline, workflow trace artifacts

---

## File Map

### A 边界治理

- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/bookanalysis/BookAnalysisPromptRenderer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/bookanalysis/BookAnalysisLlmResultNormalizer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/bookanalysis/BookAnalysisLlmResultParser.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/bookanalysis/GlobalConstraintBoundaryJudge.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/bookanalysis/GlobalConstraintBoundaryDecision.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/bookanalysis/BookAnalysisPromptRendererTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/bookanalysis/GlobalConstraintBoundaryJudgeTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/bookanalysis/LlmBookAnalysisGeneratorTest.java`

### C0 内生实体卡

- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/ToolDrivenKnowledgeEnricher.java`
- Modify: `src/main/java/io/quillloom/domain/knowledge/KnowledgeCard.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/intrinsic/IntrinsicEntityCardPlanner.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/intrinsic/IntrinsicEntityAliasJudge.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/intrinsic/IntrinsicEntityCardDraft.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/intrinsic/IntrinsicAliasState.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/intrinsic/IntrinsicEntityMergeDecision.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/intrinsic/IntrinsicEntityCardPlannerTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/intrinsic/IntrinsicEntityAliasJudgeTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/ToolDrivenKnowledgeEnricherTest.java`

### D 软约束与修订

- Modify: `src/main/java/io/quillloom/infrastructure/translation/TranslatedTextIssueDetector.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/ChunkTranslationResultValidator.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/TranslationPromptRenderer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/LlmChunkTranslator.java`
- Create: `src/main/java/io/quillloom/infrastructure/translation/GlossaryComplianceIssueDetector.java`
- Create: `src/main/java/io/quillloom/infrastructure/translation/TargetLanguagePurityIssueDetector.java`
- Create: `src/main/java/io/quillloom/infrastructure/translation/TranslationIssueSeverity.java`
- Test: `src/test/java/io/quillloom/infrastructure/translation/TranslatedTextIssueDetectorTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/translation/GlossaryComplianceIssueDetectorTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/translation/ChunkTranslationResultValidatorTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/translation/TranslationPromptRendererTest.java`

### 文档

- Modify: `docs/current-status.md`
- Modify: `docs/current-architecture.md`
- Modify: `docs/modules/name-consistency.md`
- Modify: `docs/modules/d-draft-chain-issues.md`

---

### Task 1: A 的 `globalConstraints` 边界治理

**Files:**
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/bookanalysis/GlobalConstraintBoundaryJudge.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/bookanalysis/GlobalConstraintBoundaryDecision.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/bookanalysis/BookAnalysisLlmResultNormalizer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/bookanalysis/BookAnalysisLlmResultParser.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/bookanalysis/GlobalConstraintBoundaryJudgeTest.java`

- [ ] **Step 1: 写失败测试，覆盖合法/非法 `globalConstraints` 分类**

```java
@Test
void shouldRejectEntityLevelDoNotTranslateRules() {
    GlobalConstraintBoundaryJudge judge = new GlobalConstraintBoundaryJudge();

    GlobalConstraintBoundaryDecision decision = judge.judge(
            "consistency",
            "所有专有名词保持法语原文不译，仅首次出现时加中文注释"
    );

    assertFalse(decision.accepted());
    assertEquals("entity-level-do-not-translate", decision.reasonCode());
}

@Test
void shouldAcceptStableProjectLevelNamingPrinciple() {
    GlobalConstraintBoundaryJudge judge = new GlobalConstraintBoundaryJudge();

    GlobalConstraintBoundaryDecision decision = judge.judge(
            "consistency",
            "全书命名应保持一致，未确认译名不要在不同 chunk 之间随意漂移"
    );

    assertTrue(decision.accepted());
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q "-Dtest=GlobalConstraintBoundaryJudgeTest" test`  
Expected: FAIL，提示 `GlobalConstraintBoundaryJudge` / `GlobalConstraintBoundaryDecision` 不存在

- [ ] **Step 3: 实现最小边界判断器**

```java
public final class GlobalConstraintBoundaryJudge {

    public GlobalConstraintBoundaryDecision judge(String type, String description) {
        String normalized = description == null ? "" : description.trim();
        if (normalized.isBlank()) {
            return GlobalConstraintBoundaryDecision.reject("blank-description");
        }
        if (normalized.contains("所有专有名词") && normalized.contains("原文不译")) {
            return GlobalConstraintBoundaryDecision.reject("entity-level-do-not-translate");
        }
        if (normalized.contains("所有引述文本") && normalized.contains("保持原文")) {
            return GlobalConstraintBoundaryDecision.reject("quoted-text-keep-original");
        }
        return GlobalConstraintBoundaryDecision.accept();
    }
}
```

- [ ] **Step 4: 在 A 结果归一化/解析阶段接入该判断器**

```java
private List<BookAnalysisLlmConstraint> normalizeConstraints(List<BookAnalysisLlmConstraint> values) {
    List<BookAnalysisLlmConstraint> normalized = new ArrayList<>();
    for (BookAnalysisLlmConstraint value : values) {
        GlobalConstraintBoundaryDecision decision = boundaryJudge.judge(value.type(), value.description());
        if (!decision.accepted()) {
            rejectedConstraints.add(new RejectedConstraintTracePayload(value.type(), value.description(), decision.reasonCode()));
            continue;
        }
        normalized.add(new BookAnalysisLlmConstraint(type, description));
    }
    return List.copyOf(normalized);
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -q "-Dtest=GlobalConstraintBoundaryJudgeTest" test`  
Expected: PASS

- [ ] **Step 6: 补 parser/normalizer 级测试，确保非法约束不会进入下游**

```java
assertEquals(1, result.globalConstraints().size());
assertEquals("保持全书命名一致", result.globalConstraints().get(0).description());
```

- [ ] **Step 7: 跑 A 相关测试集**

Run: `mvn -q "-Dtest=BookAnalysisPromptRendererTest,LlmBookAnalysisGeneratorTest,GlobalConstraintBoundaryJudgeTest" test`  
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/quillloom/infrastructure/preprocess/bookanalysis src/test/java/io/quillloom/infrastructure/preprocess/bookanalysis
git commit -m "feat: guard agent a global constraints boundary"
```

---

### Task 2: A 的 trace 与可诊断拒收

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/bookanalysis/BookAnalysisLlmResultNormalizer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/PreprocessBookAnalyzer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/workflow/trace/WorkflowReadableTraceRenderer.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/bookanalysis/LlmBookAnalysisGeneratorTest.java`

- [ ] **Step 1: 写失败测试，要求非法约束拒收信息进入 trace payload**

```java
assertTrue(tracePayload.containsKey("rejectedGlobalConstraints"));
assertEquals("quoted-text-keep-original", rejected.get(0).reasonCode());
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q "-Dtest=LlmBookAnalysisGeneratorTest" test`  
Expected: FAIL，缺少 `rejectedGlobalConstraints`

- [ ] **Step 3: 在 A 阶段 trace 中显式记录拒收项**

```java
traceRecorder.record(
        WorkflowStage.BOOK_ANALYSIS,
        "book_analysis_constraints_filtered",
        WorkflowEventStatus.SUCCEEDED,
        null,
        null,
        Map.of("acceptedGlobalConstraints", accepted, "rejectedGlobalConstraints", rejected)
);
```

- [ ] **Step 4: 在 readable trace 中暴露该内容**

```java
builder.append("rejectedGlobalConstraints:\n");
appendJson(builder, payload.getOrDefault("rejectedGlobalConstraints", List.of()));
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -q "-Dtest=LlmBookAnalysisGeneratorTest" test`  
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/quillloom/infrastructure/preprocess src/main/java/io/quillloom/infrastructure/workflow/trace src/test/java/io/quillloom/infrastructure/preprocess/bookanalysis
git commit -m "feat: trace rejected global constraints"
```

---

### Task 3: C0 内生实体卡最小骨架

**Files:**
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/intrinsic/IntrinsicEntityCardDraft.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/intrinsic/IntrinsicAliasState.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/intrinsic/IntrinsicEntityCardPlanner.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/intrinsic/IntrinsicEntityCardPlannerTest.java`

- [ ] **Step 1: 写失败测试，覆盖“多次出现人物 + alias 提示 -> 产出人物卡草稿”**

```java
@Test
void shouldBuildIntrinsicPersonCardDraftFromChunkSignals() {
    IntrinsicEntityCardPlanner planner = new IntrinsicEntityCardPlanner();

    List<IntrinsicEntityCardDraft> drafts = planner.plan(List.of(chunk1, chunk2));

    assertEquals(1, drafts.size());
    assertEquals("Louki", drafts.get(0).canonicalName());
    assertTrue(drafts.get(0).aliasSet().contains("Jacqueline"));
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q "-Dtest=IntrinsicEntityCardPlannerTest" test`  
Expected: FAIL，planner 和 draft 类型不存在

- [ ] **Step 3: 实现最小 draft 结构与 planner**

```java
public record IntrinsicEntityCardDraft(
        String canonicalName,
        Set<String> aliasSet,
        Set<String> surfaceForms,
        List<String> evidenceChunks,
        String firstSeenChunkId,
        String roleSummary,
        IntrinsicAliasState aliasState,
        String confidence
) {}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q "-Dtest=IntrinsicEntityCardPlannerTest" test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/quillloom/infrastructure/preprocess/intrinsic src/test/java/io/quillloom/infrastructure/preprocess/intrinsic
git commit -m "feat: add intrinsic entity card draft planner"
```

---

### Task 4: C0 保守 alias 归一与状态机

**Files:**
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/intrinsic/IntrinsicEntityAliasJudge.java`
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/intrinsic/IntrinsicEntityMergeDecision.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/intrinsic/IntrinsicEntityAliasJudgeTest.java`

- [ ] **Step 1: 写失败测试，覆盖 observed / suspected / confirmed 三种状态**

```java
@Test
void shouldKeepLowConfidenceAliasAsObservedOnly() {
    IntrinsicEntityAliasJudge judge = new IntrinsicEntityAliasJudge();

    IntrinsicEntityMergeDecision decision = judge.judge("Louki", "Jacqueline", List.of("weak-evidence"));

    assertEquals(IntrinsicAliasState.OBSERVED, decision.state());
}

@Test
void shouldPromoteExplicitRenameToConfirmedAlias() {
    IntrinsicEntityAliasJudge judge = new IntrinsicEntityAliasJudge();

    IntrinsicEntityMergeDecision decision = judge.judge("Louki", "Jacqueline", List.of("explicit-renaming"));

    assertEquals(IntrinsicAliasState.CONFIRMED_ALIAS, decision.state());
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q "-Dtest=IntrinsicEntityAliasJudgeTest" test`  
Expected: FAIL

- [ ] **Step 3: 实现保守归一判断器**

```java
if (evidences.contains("explicit-renaming")) {
    return IntrinsicEntityMergeDecision.confirmed();
}
if (evidences.contains("same-chunk-alias-hint")) {
    return IntrinsicEntityMergeDecision.suspected();
}
return IntrinsicEntityMergeDecision.observed();
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q "-Dtest=IntrinsicEntityAliasJudgeTest" test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/quillloom/infrastructure/preprocess/intrinsic src/test/java/io/quillloom/infrastructure/preprocess/intrinsic
git commit -m "feat: add conservative intrinsic alias judge"
```

---

### Task 5: 将内生实体卡挂接到 C0 主链

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/ToolDrivenKnowledgeEnricher.java`
- Modify: `src/main/java/io/quillloom/domain/knowledge/KnowledgeCard.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/ToolDrivenKnowledgeEnricherTest.java`

- [ ] **Step 1: 写失败测试，要求 C0 现有链路保留外部卡，同时新增内生人物卡**

```java
assertTrue(cards.stream().anyMatch(card -> card.cardType().name().equals("CHARACTER_PROFILE")));
assertTrue(cards.stream().anyMatch(card -> card.title().contains("Louki")));
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q "-Dtest=ToolDrivenKnowledgeEnricherTest" test`  
Expected: FAIL，当前没有内生人物卡产出

- [ ] **Step 3: 在 `ToolDrivenKnowledgeEnricher` 旁挂接内生卡分支**

```java
List<KnowledgeCard> intrinsicCards = intrinsicEntityCardPlanner.plan(command.chunkAnnotations())
        .stream()
        .map(this::toKnowledgeCard)
        .toList();

results.addAll(intrinsicCards);
results.addAll(externalKnowledgeCards);
```

- [ ] **Step 4: 为人物卡补最小 metadata，不改坏现有 `KnowledgeCard` 消费点**

```java
new KnowledgeCard(
        cardId,
        KnowledgeCardType.CHARACTER_PROFILE,
        title,
        content,
        keywords,
        anchorNames,
        sourceRefs,
        "PROJECT",
        applicableChunkIds
);
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -q "-Dtest=ToolDrivenKnowledgeEnricherTest" test`  
Expected: PASS

- [ ] **Step 6: 跑 C0 相关回归测试**

Run: `mvn -q "-Dtest=ToolDrivenKnowledgeEnricherTest,IntrinsicEntityCardPlannerTest,IntrinsicEntityAliasJudgeTest" test`  
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/quillloom/infrastructure/preprocess src/main/java/io/quillloom/domain/knowledge src/test/java/io/quillloom/infrastructure/preprocess
git commit -m "feat: attach intrinsic entity cards to c0 pipeline"
```

---

### Task 6: D 侧目标语言正文纯度检测

**Files:**
- Create: `src/main/java/io/quillloom/infrastructure/translation/TargetLanguagePurityIssueDetector.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/TranslatedTextIssueDetector.java`
- Test: `src/test/java/io/quillloom/infrastructure/translation/TranslatedTextIssueDetectorTest.java`

- [ ] **Step 1: 写失败测试，覆盖 zh 目标下整段法语残留**

```java
@Test
void shouldDetectFrenchParagraphResidualWhenTargetLanguageIsZh() {
    TargetLanguagePurityIssueDetector detector = new TargetLanguagePurityIssueDetector();

    List<TranslatedTextIssue> issues = detector.detect("zh", "Elle se tenait très droite, alors que les autres...");

    assertTrue(issues.stream().anyMatch(issue -> issue.type().equals("target-language-purity-warning")));
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q "-Dtest=TranslatedTextIssueDetectorTest" test`  
Expected: FAIL

- [ ] **Step 3: 实现最小纯度检测器，先支持 zh 高置信残留识别**

```java
if ("zh".equalsIgnoreCase(targetLanguage) && looksLikeFrenchSentence(text)) {
    issues.add(new TranslatedTextIssue(
            "target-language-purity-warning",
            "检测到目标语言为 zh 时仍残留整句或整段法语正文"
    ));
}
```

- [ ] **Step 4: 将该检测挂到现有 `TranslatedTextIssueDetector` 聚合入口**

```java
issues.addAll(targetLanguagePurityIssueDetector.detect(targetLanguage, translatedText));
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -q "-Dtest=TranslatedTextIssueDetectorTest" test`  
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/quillloom/infrastructure/translation src/test/java/io/quillloom/infrastructure/translation
git commit -m "feat: detect target language purity issues"
```

---

### Task 7: D 侧 active glossary 正文合规检测

**Files:**
- Create: `src/main/java/io/quillloom/infrastructure/translation/GlossaryComplianceIssueDetector.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/ChunkTranslationResultValidator.java`
- Test: `src/test/java/io/quillloom/infrastructure/translation/GlossaryComplianceIssueDetectorTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/translation/ChunkTranslationResultValidatorTest.java`

- [ ] **Step 1: 写失败测试，覆盖“已确认术语未沿用”和“混用原文名/译名”**

```java
@Test
void shouldAddDecisionNoteWhenConfirmedTermIsNotAppliedInTranslatedText() {
    GlossaryComplianceIssueDetector detector = new GlossaryComplianceIssueDetector();

    List<ChunkTranslationDecisionNoteResult> issues = detector.detect(
            Map.of("Louki", "露姬"),
            "Louki站在门口，露姬没有回头。"
    );

    assertFalse(issues.isEmpty());
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q "-Dtest=GlossaryComplianceIssueDetectorTest,ChunkTranslationResultValidatorTest" test`  
Expected: FAIL

- [ ] **Step 3: 实现最小 glossary 合规检测器**

```java
if (translatedText.contains(sourceTerm) && translatedText.contains(translatedTerm)) {
    issues.add(new ChunkTranslationDecisionNoteResult(
            "glossary-compliance-warning",
            sourceTerm,
            "检测到原文名与已确认译名在同一正文中混用",
            "请在修订轮统一沿用当前已确认译名"
    ));
}
```

- [ ] **Step 4: 将 issue 接入 validator，默认归为 repair-required 语义**

```java
decisionNotes.addAll(glossaryComplianceIssueDetector.detect(existingConfirmedTerms, result.translatedText()));
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -q "-Dtest=GlossaryComplianceIssueDetectorTest,ChunkTranslationResultValidatorTest" test`  
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/quillloom/infrastructure/translation src/test/java/io/quillloom/infrastructure/translation
git commit -m "feat: add glossary compliance warnings"
```

---

### Task 8: D 第 2 轮按 issue 清单定向修订

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/translation/TranslationPromptRenderer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/LlmChunkTranslator.java`
- Test: `src/test/java/io/quillloom/infrastructure/translation/TranslationPromptRendererTest.java`

- [ ] **Step 1: 写失败测试，要求 revision prompt 明确包含纯度/术语合规 issue**

```java
assertTrue(prompt.contains("目标语言正文纯度"));
assertTrue(prompt.contains("active glossary"));
assertTrue(prompt.contains("按 issue 清单定向修订"));
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q "-Dtest=TranslationPromptRendererTest" test`  
Expected: FAIL

- [ ] **Step 3: 调整 revision prompt，让第二轮只做定向修订**

```java
builder.append("- 本轮不是重翻，不是自由润色，而是按 issue 清单定向修正。\n");
builder.append("- 优先修正目标语言正文纯度问题、active glossary 未沿用问题和正文边界污染问题。\n");
```

- [ ] **Step 4: 在 `LlmChunkTranslator` 中确保相关 issue 会传入 revision round**

```java
List<TranslatedTextIssue> textIssues = translatedTextIssueDetector.detect(
        input.sourceMaterial().project().targetLanguage(),
        draftRoundResult.translatedText()
);
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -q "-Dtest=TranslationPromptRendererTest" test`  
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/quillloom/infrastructure/translation src/test/java/io/quillloom/infrastructure/translation
git commit -m "feat: drive revision round by translation issues"
```

---

### Task 9: 文档同步与回归验证

**Files:**
- Modify: `docs/current-status.md`
- Modify: `docs/current-architecture.md`
- Modify: `docs/modules/name-consistency.md`
- Modify: `docs/modules/d-draft-chain-issues.md`

- [ ] **Step 1: 更新现状文档，明确新增但未删除的链路能力**

```markdown
- A 侧 `globalConstraints` 已新增执行层边界治理；非法全局约束会显式拒收并进入 trace。
- C0 在保留外部知识卡链路的前提下，新增了基于书内证据的内生实体卡分支。
- D 在保留现有两轮机制的基础上，新增了目标语言纯度与 glossary 合规软约束 issue。
```

- [ ] **Step 2: 运行目标测试集合**

Run: `mvn -q "-Dtest=BookAnalysisPromptRendererTest,GlobalConstraintBoundaryJudgeTest,LlmBookAnalysisGeneratorTest,IntrinsicEntityCardPlannerTest,IntrinsicEntityAliasJudgeTest,ToolDrivenKnowledgeEnricherTest,TranslatedTextIssueDetectorTest,GlossaryComplianceIssueDetectorTest,ChunkTranslationResultValidatorTest,TranslationPromptRendererTest" test`

Expected: PASS

- [ ] **Step 3: 如果本地环境允许，跑一次定向 smoke**

Run: `mvn -q "-Dtest=BookWorkflowSmokeTest" "-Dquillloom.test.book-workflow.enabled=true" "-Dspring.profiles.active=dev" test`

Expected: PASS，且 trace 中不再把错误的 A 级“不译”约束传播到 D

- [ ] **Step 4: Commit**

```bash
git add docs/current-status.md docs/current-architecture.md docs/modules/name-consistency.md docs/modules/d-draft-chain-issues.md
git commit -m "docs: sync translation chain stabilization status"
```

---

## Self-Review

### Spec coverage

- A 的 `globalConstraints` 边界治理：Task 1-2 覆盖
- C0 内生实体卡与 alias 归一：Task 3-5 覆盖
- D 软约束与可修复式修订：Task 6-8 覆盖
- 文档同步与验证：Task 9 覆盖

### Placeholder scan

- 无 `TODO` / `TBD`
- 每个任务均列出具体文件、测试与命令
- 没有“按上文类似处理”的模糊指令

### Type consistency

- `GlobalConstraintBoundaryJudge` / `GlobalConstraintBoundaryDecision` 命名一致
- `IntrinsicEntityCardDraft` / `IntrinsicAliasState` / `IntrinsicEntityMergeDecision` 命名一致
- `TargetLanguagePurityIssueDetector` / `GlossaryComplianceIssueDetector` / `TranslatedTextIssueDetector` 命名一致

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-12-translation-chain-stabilization-implementation-plan.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
