# D Boundary And Person Alias Hints Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 收紧 Agent D 初稿正文边界，降低知识卡泄漏和过度文风化，同时为后续初稿一致性提供最小侵入的人名弱提示。

**Architecture:** 这次实现不改主检索职责、不引入正式 alias registry，也不把运行期临时状态写回稳定契约。D 侧改为“配置化 prompt + 第 1 轮初稿 + 规则问题清单 + 第 2 轮定向净化修订”，chunk 标注侧仅新增可选的人名弱提示供翻译参考，不进入长期记忆。

**Tech Stack:** Java 21, Spring Boot configuration properties, JUnit 5, existing translation/chunk-annotation pipeline

---

### Task 1: 配置化 D Prompt 与全局翻译导向

**Files:**
- Create: `src/main/java/io/quillloom/infrastructure/translation/TranslationPromptProperties.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/TranslationPromptRenderer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/TranslationGeneratorConfiguration.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/io/quillloom/infrastructure/translation/TranslationPromptRendererTest.java`

- [ ] **Step 1: 先写 prompt 配置化测试**

```java
@Test
void shouldRenderConfiguredPolicyAndBoundaryRules() {
    TranslationPromptProperties properties = new TranslationPromptProperties();
    properties.getGlobal().setAccuracyPolicy("准确、忠实、自然、克制优先");
    properties.getGlobal().setStyleWarnings(List.of("不要追逐华丽辞藻"));
    properties.getDraftRound().setCoreInstructions(List.of("正文不得写入知识卡内容"));

    TranslationPromptRenderer renderer = new TranslationPromptRenderer(properties);

    String prompt = renderer.renderDraftRound(createInput());

    assertThat(prompt).contains("准确、忠实、自然、克制优先");
    assertThat(prompt).contains("不要追逐华丽辞藻");
    assertThat(prompt).contains("正文不得写入知识卡内容");
    assertThat(prompt).doesNotContain("冷调诗性汉语");
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q "-Dtest=TranslationPromptRendererTest" test`
Expected: FAIL，提示 `TranslationPromptProperties` 或新构造器不存在

- [ ] **Step 3: 实现最小配置对象与渲染接线**

```java
@ConfigurationProperties(prefix = "quillloom.translation.chunk-translation.prompt")
public class TranslationPromptProperties {

    private final Global global = new Global();
    private final DraftRound draftRound = new DraftRound();
    private final RevisionRound revisionRound = new RevisionRound();

    public static class Global {
        private String accuracyPolicy = "准确、忠实、自然、克制优先。";
        private List<String> styleWarnings = List.of();
    }
}
```

```java
builder.append("【翻译总原则】\n");
builder.append(properties.getGlobal().getAccuracyPolicy()).append("\n");
properties.getGlobal().getStyleWarnings()
        .forEach(item -> builder.append("- ").append(item).append("\n"));
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q "-Dtest=TranslationPromptRendererTest" test`
Expected: PASS

### Task 2: 收束 D 第 2 轮为“问题清单驱动的正文净化修订”

**Files:**
- Create: `src/main/java/io/quillloom/infrastructure/translation/TranslatedTextIssue.java`
- Create: `src/main/java/io/quillloom/infrastructure/translation/TranslatedTextIssueDetector.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/TranslationPromptRenderer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/LlmChunkTranslator.java`
- Test: `src/test/java/io/quillloom/infrastructure/translation/TranslatedTextIssueDetectorTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/translation/LlmChunkTranslatorTest.java`

- [ ] **Step 1: 先写问题探测器测试**

```java
@Test
void shouldDetectKnowledgeCardLeakAndBracketExplanationPatterns() {
    TranslatedTextIssueDetector detector = new TranslatedTextIssueDetector();

    List<TranslatedTextIssue> issues = detector.detect(
            "孔代咖啡馆（Le Conde）——巴黎左岸一家边缘文化据点——是街区里最晚打烊的咖啡馆。"
    );

    assertThat(issues).extracting(TranslatedTextIssue::code)
            .contains("bracketed-explanation", "encyclopedic-insertion");
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q "-Dtest=TranslatedTextIssueDetectorTest" test`
Expected: FAIL，提示探测器不存在

- [ ] **Step 3: 实现最小规则探测器**

```java
public List<TranslatedTextIssue> detect(String translatedText) {
    List<TranslatedTextIssue> issues = new ArrayList<>();
    if (translatedText.contains("——") && translatedText.contains("一家")) {
        issues.add(new TranslatedTextIssue("encyclopedic-insertion", "检测到正文中出现解释性插入结构"));
    }
    if (translatedText.contains("（") && translatedText.contains("）")) {
        issues.add(new TranslatedTextIssue("bracketed-explanation", "检测到正文中出现括号注样式"));
    }
    return List.copyOf(issues);
}
```

- [ ] **Step 4: 先写 D 第二轮调度测试**

```java
@Test
void shouldSendDetectedTextIssuesToRevisionRoundInsteadOfFailingFast() {
    FakeClient client = new FakeClient(
            draftResultWithTranslatedText("孔代咖啡馆（Le Conde）——巴黎左岸一家边缘文化据点——"),
            revisionResultWithTranslatedText("孔代咖啡馆")
    );
    LlmChunkTranslator translator = createTranslator(client);

    ChunkTranslationDraft draft = translator.translate(createInput());

    assertThat(draft.translatedText()).isEqualTo("孔代咖啡馆");
    assertThat(client.prompts().get(1)).contains("【正文问题清单】");
    assertThat(client.prompts().get(1)).contains("encyclopedic-insertion");
}
```

- [ ] **Step 5: 跑测试确认失败**

Run: `mvn -q "-Dtest=LlmChunkTranslatorTest" test`
Expected: FAIL，提示第二轮 prompt 未包含问题清单或调度逻辑未生效

- [ ] **Step 6: 实现最小调度逻辑**

```java
List<TranslatedTextIssue> textIssues = issueDetector.detect(draftRoundResult.translatedText());
if (!textIssues.isEmpty()) {
    String prompt = promptRenderer.renderRevisionRound(input, draftRoundResult, textIssues) + renderSupplementalKnowledgePrompt(lookupResponse);
    return executeRound(input, prompt, "revision");
}
```

- [ ] **Step 7: 跑测试确认通过**

Run: `mvn -q "-Dtest=TranslatedTextIssueDetectorTest,LlmChunkTranslatorTest" test`
Expected: PASS

### Task 3: 让 validator 只输出确定性问题，不直接卡死链路

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/translation/ChunkTranslationResultValidator.java`
- Modify: `src/test/java/io/quillloom/infrastructure/translation/ChunkTranslationResultValidatorTest.java`

- [ ] **Step 1: 先写 validator 行为测试**

```java
@Test
void shouldRecordDeterministicTextBoundaryNotesWithoutThrowing() {
    ChunkTranslationResultValidator validator = new ChunkTranslationResultValidator();

    ChunkTranslationLlmResult validated = validator.validate(inputWithConfirmedTerms(), resultWithText(
            "孔代咖啡馆（Le Condé）——巴黎左岸一家边缘文化据点——"
    ));

    assertThat(validated.translatedText()).contains("孔代咖啡馆");
    assertThat(validated.decisionNotes())
            .extracting(ChunkTranslationDecisionNoteResult::type)
            .contains("text-boundary-warning");
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q "-Dtest=ChunkTranslationResultValidatorTest" test`
Expected: FAIL，提示未追加 `text-boundary-warning`

- [ ] **Step 3: 实现最小 validator 追加逻辑**

```java
decisionNotes.addAll(sanitizeTextBoundaryWarnings(result.translatedText()));
return new ChunkTranslationLlmResult(
        result.translatedText(),
        sanitizeTranslatorCommentary(result.translatorCommentary()),
        List.copyOf(decisionNotes),
        ...
);
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q "-Dtest=ChunkTranslationResultValidatorTest" test`
Expected: PASS

### Task 4: 为 chunk 标注增加人名弱提示 `personAliasHints`

**Files:**
- Create: `src/main/java/io/quillloom/domain/preprocess/PersonAliasHint.java`
- Modify: `src/main/java/io/quillloom/domain/preprocess/ChunkAnnotation.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/chunkannotation/ChunkAnnotationLlmResult.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/chunkannotation/ChunkAnnotationPromptRenderer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/chunkannotation/ChunkAnnotationLlmResultNormalizer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/chunkannotation/ChunkAnnotationLlmResultParser.java`
- Modify: `src/main/java/io/quillloom/infrastructure/workflow/trace/WorkflowReadableTraceRenderer.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/chunkannotation/ChunkAnnotationLlmResultNormalizerTest.java`
- Test: `src/test/java/io/quillloom/infrastructure/preprocess/chunkannotation/LlmChunkAnnotationGeneratorTest.java`

- [ ] **Step 1: 先写 chunk annotation 新字段测试**

```java
@Test
void shouldNormalizeOptionalPersonAliasHints() {
    ChunkAnnotationLlmResultNormalizer normalizer = new ChunkAnnotationLlmResultNormalizer();

    ChunkAnnotationLlmResult normalized = normalizer.normalize(
            createTaskInput(),
            new ChunkAnnotationLlmResult(
                    "summary",
                    List.of("Louki"),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(new PersonAliasHintResult(List.of("Bowling", "le Capitaine"), "same-person-name-variant", "HIGH", "同段中交替出现"))
            )
    );

    assertThat(normalized.personAliasHints()).hasSize(1);
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q "-Dtest=ChunkAnnotationLlmResultNormalizerTest,LlmChunkAnnotationGeneratorTest" test`
Expected: FAIL，提示新字段不存在

- [ ] **Step 3: 实现最小可选字段**

```java
public record PersonAliasHint(
        List<String> surfaceForms,
        String hintType,
        String confidence,
        String evidence
) {}
```

```java
builder.append("JSON 必须严格包含以下字段：summary、entities、backgroundQuestions、translationRisks、keyExpressions、personAliasHints。\n");
builder.append("6. personAliasHints：数组，仅记录当前 chunk 内可能指向同一人物的不同称呼；没有可返回空数组。\n");
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q "-Dtest=ChunkAnnotationLlmResultNormalizerTest,LlmChunkAnnotationGeneratorTest" test`
Expected: PASS

### Task 5: 将人名弱提示下发给 D，但不写入长期记忆

**Files:**
- Modify: `src/main/java/io/quillloom/domain/translation/TranslationSourceMaterial.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/TranslationPromptRenderer.java`
- Modify: `src/test/java/io/quillloom/infrastructure/translation/TranslationPromptRendererTest.java`
- Modify: `src/test/java/io/quillloom/application/translation/assembler/TranslationTaskInputAssemblerTest.java`

- [ ] **Step 1: 先写 D prompt 展示弱提示测试**

```java
@Test
void shouldRenderPersonAliasHintsAsReferenceOnly() {
    TranslationPromptRenderer renderer = new TranslationPromptRenderer(createPromptProperties());

    String prompt = renderer.renderDraftRound(createInputWithAliasHints());

    assertThat(prompt).contains("【人名弱提示】");
    assertThat(prompt).contains("仅供参考，不代表已确认事实");
    assertThat(prompt).contains("Bowling");
    assertThat(prompt).contains("le Capitaine");
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q "-Dtest=TranslationPromptRendererTest,TranslationTaskInputAssemblerTest" test`
Expected: FAIL，提示 source material 或 prompt 未包含新字段

- [ ] **Step 3: 实现最小下发**

```java
builder.append("【人名弱提示】\n");
builder.append("以下提示仅供参考，不代表已确认事实，不要自动合并为同一稳定译名：\n");
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q "-Dtest=TranslationPromptRendererTest,TranslationTaskInputAssemblerTest" test`
Expected: PASS

### Task 6: 补文档与回归验证

**Files:**
- Modify: `docs/handoff.md`
- Modify: `docs/current-status.md`
- Modify: `docs/modules/d-draft-chain-issues.md`

- [ ] **Step 1: 更新 handoff 与状态文档**

```md
- D prompt 已配置化，默认导向从“文风驱动”收束为“准确优先”
- 第 2 轮当前承担正文边界净化，不再由 validator 直接卡死链路
- chunk annotation 新增 `personAliasHints`，仅供初稿阶段参考，不进入长期记忆
```

- [ ] **Step 2: 运行定向测试集**

Run: `mvn -q "-Dtest=TranslationPromptRendererTest,ChunkTranslationResultValidatorTest,TranslatedTextIssueDetectorTest,LlmChunkTranslatorTest,ChunkAnnotationLlmResultNormalizerTest,LlmChunkAnnotationGeneratorTest,TranslationTaskInputAssemblerTest" test`
Expected: PASS

- [ ] **Step 3: 运行一组链路回归测试**

Run: `mvn -q "-Dtest=C0ToDIntegrationSmokeTest,TranslationApplicationServiceTest" test`
Expected: PASS
