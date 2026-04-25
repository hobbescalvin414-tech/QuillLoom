# A/B/C0/D Pipeline Health Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 A/B/C0/D 前置流水线中会污染 D 初稿与 post-draft review package 的术语合并、异常处理、trace 与构造边界问题，保证后续 127 chunk Review Agent 冒烟基于可信初稿数据。

**Architecture:** 本计划不改变 C0/D/Review Agent 职责边界，不新增 agent 工具，不重做 orchestrator。核心策略是先修正确性和可诊断性：术语 key 语义统一、Locale 稳定、LLM 修订轮异常不静默吞掉、workflow 异常不被 trace flush 掩盖；再处理低风险维护性问题。

**Tech Stack:** Java 17, Spring Boot 3.5, Maven, PostgreSQL, Jackson.

---

## 1. 当前是否应停止正在跑的链路

### 1.1 建议

如果当前这次运行的目标是生成“可作为 Review Agent 127 chunk 冒烟 baseline 的干净 D 初稿数据”，建议立即停止，等本计划中的 P0/P1 正确性修复完成后，从 `/book` 重新跑。

原因：

- 当前 confirmed term / candidate term 的大小写与 normalize 语义不一致，可能让术语冲突绕过检测。
- `LlmChunkTranslator.executeRevisionRoundWithFallback(...)` 会吞掉任意 `RuntimeException`，可能把网络/解析/程序 bug 降级成“保留第一轮结果”。
- 如果本次跑出的 package 后续被用作 agent baseline，agent 看到的问题可能来自前置流水线污染，而不是 agent 自身行为。

### 1.2 可以继续跑完的场景

如果只是想观察：

- 127 chunk 的耗时；
- LLM 限流频率；
- 控制台日志；
- workflow trace 是否能生成；
- 大体是否能跑通；

可以让当前链路跑完。但这次产物只能作为诊断样本，不建议创建正式 baseline，也不建议作为 Review Agent 质量判断依据。

### 1.3 推荐操作

本轮更稳的执行顺序：

```text
停止当前 D 全链路运行
-> 修复 P0 / P1 正确性问题
-> 跑定向测试
-> 从 /book 重新跑 D 全链路
-> create-baseline
-> start Review Agent 127 chunk 冒烟
```

如果当前运行已经接近结束且你想保留诊断数据，也可以让它自然结束，但不要用它做正式 baseline。

## 2. P0 问题：术语 key 语义不一致

### 2.1 candidate dedup 大小写语义不一致

位置：

- `src/main/java/io/quillloom/application/translation/service/TranslationApplicationService.java`
- `src/main/java/io/quillloom/application/postdraft/assembler/PostDraftReviewPackageAssembler.java`

现状：

```java
// TranslationApplicationService
private String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase();
}

// PostDraftReviewPackageAssembler
private String normalize(String value) {
    return value == null ? "" : value.trim();
}
```

影响：

```text
D 阶段:
Le Condé|孔代咖啡馆
le condé|孔代咖啡馆
=> 视为同一 candidate

Post-draft package 阶段:
Le Condé|孔代咖啡馆
le condé|孔代咖啡馆
=> 视为两条 candidate
```

这会导致 D 阶段与 post-draft package 的 `effectiveCandidateTerms` 不一致。

### 2.2 confirmed term 冲突检测存在伪 normalize

位置：

- `TranslationApplicationService.mergeConfirmedTermOrThrow(...)`
- `PostDraftReviewPackageAssembler.mergeConfirmedTermOrThrow(...)`
- `WorkflowDraftRunResponse.mergeConfirmedTermOrThrow(...)`
- `RepositoryBackedPostDraftReviewAgentTermWriter.recordConfirmedTerms(...)`

当前代码中已经存在 `mergeConfirmedTermOrThrow(...)`，但实现存在“伪 normalize”问题：方法会先计算 normalized source/target，却只用它们做 blank check，后续查找和写入仍使用原始 `sourceTerm`，target 比较也使用原始 `targetTerm`。

```java
String normalizedSourceTerm = normalize(sourceTerm);
String normalizedTargetTerm = normalize(targetTerm);
if (normalizedSourceTerm.isBlank() || normalizedTargetTerm.isBlank()) {
    return;
}
String existing = confirmedTerms.get(sourceTerm);
if (existing == null) {
    confirmedTerms.put(sourceTerm, targetTerm);
    return;
}
if (existing.equals(targetTerm)) {
    return;
}
```

问题：

```text
Le Condé -> 孔代咖啡馆
le condé -> 勒孔代咖啡馆
```

可能绕过冲突检测，因为 map key 不同。

### 2.3 解决方案：引入统一术语 key normalizer

新增小工具类，避免每个类各写一套 normalize：

```text
src/main/java/io/quillloom/domain/shared/TermTextNormalizer.java
```

职责：

```java
public final class TermTextNormalizer {
    public static String displayText(String value) {
        return value == null ? "" : value.trim();
    }

    public static String keyText(String value) {
        return displayText(value).toLowerCase(Locale.ROOT);
    }

    public static String pairKey(String sourceTerm, String targetTerm) {
        return keyText(sourceTerm) + "|" + keyText(targetTerm);
    }
}
```

语义：

- `displayText(...)`：保留给 UI、prompt、错误信息、最终写库值。
- `keyText(...)`：用于 dedup、冲突检测、map key。
- `pairKey(...)`：用于 candidate pair dedup。

confirmed term 合并规则：

```text
内部用 normalized source key 检测冲突
保留第一次出现的 display sourceTerm / targetTerm 作为最终展示值，采用先到先得策略
后续同 source key + 同 target key 的条目只用于去重，不覆盖 display 值
同 source key + 同 target key => 视为重复
同 source key + 不同 target key => 生成 confirmed_term_conflict 诊断，不在 D 阶段直接 fail-fast
```

实现上推荐不要直接用 `Map<String, String>` 做中间结构，而是用一个内部 accumulator：

```java
private record ConfirmedTermEntry(
        String displaySourceTerm,
        String displayTargetTerm,
        String targetKey
) {
}
```

最后再输出 `Map<String, String>`：

```java
Map<String, String> result = entries.values().stream()
        .collect(toMap(ConfirmedTermEntry::displaySourceTerm, ConfirmedTermEntry::displayTargetTerm));
```

候选项合并规则：

```text
pair key = lower(sourceTerm) + "|" + lower(candidateTranslation)
展示值保留第一次出现的 sourceTerm/candidateTranslation/rationale/requiresReview
```

### 2.4 D 阶段术语生命周期与冲突 repair 策略

当前业务规则应明确为：

```text
C0 先给 D 一份全局命名基线
D 顺序翻译 chunk
D 每个 chunk 可以补充 C0 没给出的新术语
一旦某个术语进入 effectiveProjectMemory，后续 chunk 必须沿用
如果后续 chunk 试图改写它，不直接失败，而是把具体失败原因和冲突点交给当前 chunk repair
repair 成功继续，repair 失败才停
```

因此 `TranslationApplicationService.evolveProjectMemory(...)` 不应在第一次发现 `source key` 相同但 `target key` 不同的时候直接抛出终止全链路。它应向当前 chunk 翻译流程返回一个结构化冲突诊断，例如：

```java
record ConfirmedTermConflict(
        String sourceKey,
        String existingSourceTerm,
        String existingTargetTerm,
        String incomingSourceTerm,
        String incomingTargetTerm,
        String evidenceChunkId
) {
}
```

冲突含义：

```text
当前 effectiveProjectMemory 已有：
Le Condé -> 孔代咖啡馆

当前 chunk 输出：
le condé -> 勒孔代咖啡馆

问题：
当前 chunk 更新并使用了与已生效术语不同的译名。
```

D 应只重试当前 chunk，不重跑之前 chunk。repair prompt 必须明确给出：

```text
已生效译名：Le Condé -> 孔代咖啡馆
本轮冲突输出：le condé -> 勒孔代咖啡馆
要求：
1. 当前 chunk 正文必须沿用“孔代咖啡馆”
2. confirmedTermUpdates 不得写入 le condé -> 勒孔代咖啡馆
3. 若仍需要登记该术语，只能写入与已生效译名一致的 Le Condé -> 孔代咖啡馆
4. translatorCommentary / decisionNotes 也要同步说明沿用既有译名
```

重试次数应比普通 repair 更宽松，保证长链路尽量产出 agent 可用数据。建议：

```text
confirmedTermConflictRepairMaxAttempts = 3
```

含义是当前 chunk 最多额外 repair 3 次；初始 draft 不计入 repair attempt。每次 repair 都必须记录 trace：

```text
event=confirmed_term_conflict_repair_attempt
chunkId
attempt
sourceKey
existingSourceTerm
existingTargetTerm
incomingSourceTerm
incomingTargetTerm
```

如果 3 次 repair 后仍输出冲突，才抛出受控异常并停止 D 链路：

```text
confirmed_term_conflict_repair_exhausted
```

这不是兜底掩盖问题，因为冲突被显式检测、repair 次数受控、日志可诊断，且无法修复时仍会失败。

## 3. P0 问题：裸 `toLowerCase()` 的 Locale 敏感 bug

### 3.1 位置

已确认至少存在：

- `TranslationApplicationService.java`
- `ChunkTranslationResultValidator.java`
- `GlobalConstraintBoundaryJudge.java`
- `ToolDrivenKnowledgeEnricher.java`
- `IntrinsicEntityCardPlanner.java`

示例：

```java
value.trim().toLowerCase()
```

问题：

`String.toLowerCase()` 不指定 `Locale` 时使用默认系统 locale。在 Turkish/Azerbaijani 环境下，`"I".toLowerCase()` 会得到无点 `ı`，导致术语 key、禁用词检查、card id 等行为漂移。

### 3.2 解决方案

统一替换为：

```java
toLowerCase(Locale.ROOT)
```

对 prompt 展示用文本不要 lower-case；只在 key / matching / id 生成场景 lower-case。

## 4. P1 问题：`LlmChunkTranslator` 修订轮吞掉所有 RuntimeException

### 4.1 当前行为

位置：

```text
src/main/java/io/quillloom/infrastructure/translation/LlmChunkTranslator.java
```

代码：

```java
private ChunkTranslationLlmResult executeRevisionRoundWithFallback(...) {
    try {
        ...
        return executeRound(input, prompt, "revision");
    } catch (RuntimeException exception) {
        return markRevisionRoundFallback(draftRoundResult, exception);
    }
}
```

问题：

这会把以下错误全部吞掉：

- NPE；
- `IllegalStateException`；
- 网络超时；
- 429 / 503；
- LLM 客户端 bug；
- validator / parser bug；
- trace bug。

然后系统继续使用第一轮译文，附加一个 fallback decision note。这违反“不兜底掩盖问题”。

### 4.2 解决方案

只允许明确可恢复的结构化输出问题走 fallback。推荐引入或复用异常类型：

```text
ChunkTranslationStructuredOutputException
ChunkTranslationRepairExhaustedException
```

如果当前没有这些类型，可先用已有 parser / structured output 异常类型。原则：

```text
可 fallback:
- 修订轮 LLM 输出结构化格式错误，且该错误只影响第二轮修订
- 修订轮 repair 失败，但第一轮 draft 已经通过 validator

不可 fallback，必须向上抛:
- 网络超时 / 429 / 503
- NPE / IllegalArgumentException / IllegalStateException
- validator 发现契约违规
- trace / IO / repository 异常
```

如果暂时无法精确区分异常类型，宁可先禁用 fallback，直接抛出 revision 异常。稳定性优先级低于数据可信度。

### 4.3 D 阶段 LLM 瞬态错误策略

D 阶段是顺序处理 127 chunk 的长链路。如果修订轮遇到 429 / 503 / 网络超时，直接向上抛会中断整个 D 链路；但静默 fallback 到第一轮结果会污染数据。因此本轮策略应为：

```text
429 / 503 / 网络超时
=> 不允许 revision fallback
=> 先在 LLM 客户端层做有限重试与退避
=> 重试耗尽后向上抛出受控异常，中断 D 链路
```

重试位置应在 D 的 LLM client 层，而不是 workflow loop 层。原因：

- workflow loop 不应理解具体 HTTP / provider 错误；
- 重试日志应和一次 LLM 调用绑定；
- 避免和 D 的 chunk 顺序推进、术语记忆演化、trace 状态混在一起。

建议与 Review Agent 的 `RetryingReviewAgentStructuredGenerationPort` 对齐，但不要复用 Review Agent 端口类。D 可新增独立包装器或在 `OpenAiCompatibleLlmChunkTranslationClient` 内部实现显式重试。

最低要求：

```text
retry_attempt
retry_reason
backoff_ms
chunkId 或 roundLabel
```

不可重试：

- 400 / 401；
- 结构化输出解析失败；
- validator 契约失败；
- NPE / IllegalStateException；
- confirmed_term_conflict。

## 5. P1 问题：`NovelTranslationWorkflowService` 构造器破坏六边形边界

### 5.1 当前行为

位置：

```text
src/main/java/io/quillloom/application/workflow/service/NovelTranslationWorkflowService.java
```

非 Spring 构造器中硬编码：

```java
new PostDraftReviewPackageAssembler()
new PostDraftContinuationContextAssembler()
new InMemoryProjectKnowledgeBaseRepository()
new InMemoryPostDraftReviewPackageRepository()
```

问题：

- application 层直接 import infrastructure 的 in-memory 仓储。
- 手工 new service 时可能误用 in-memory 仓储，导致以为写库但实际没写。
- assembler 也被手动 new，绕过 DI。

### 5.2 解决方案

保留一个 Spring `@Autowired` 全参构造器。

测试如需手工构造，必须显式传入测试 double：

```java
new NovelTranslationWorkflowService(
        preprocessApplicationService,
        translationApplicationService,
        draftCompilationAssembler,
        postDraftReviewPackageAssembler,
        postDraftContinuationContextAssembler,
        projectKnowledgeBaseRepository,
        postDraftReviewPackageRepository
)
```

删除或标记禁用 3 参数构造器。不要在 application service 内部 new repository。

## 6. P1 问题：Workflow trace recorder 多处手动 new

### 6.1 当前行为

多处存在：

```java
new WorkflowTraceRecorder()
```

例如：

- `NovelTranslationWorkflowService`
- `TranslationTaskInputAssembler`
- `LlmChunkTranslator`
- `PreprocessBookAnalyzer`
- `ChunkAnnotationOrchestrator`
- `ToolDrivenKnowledgeEnricher`
- A/B/C0 若干 LLM generator

### 6.2 真实影响

外部清单说“实例不共享导致 trace 汇聚不了”不完全准确，因为 `WorkflowTraceRecorder` 内部使用 static `ThreadLocal<WorkflowTraceSession>`。同一线程里，不同 recorder 实例仍能写入同一个 current session。

但问题仍然存在：

- 依赖关系不可见；
- 跨线程或异步时 ThreadLocal 会断；
- 构造器越来越难维护；
- 测试和生产路径行为容易不一致。

### 6.3 解决方案

把 `WorkflowTraceRecorder` 注册为 Spring Bean，并逐步让生产 Bean 通过 DI 注入。测试中仍可显式 new。

本项不建议插在当前 D 初稿修复前做大规模改造；它可以作为 P2/P3 架构清理。

## 7. P1 问题：workflow 失败时 trace flush 可能掩盖原始异常

### 7.1 当前行为

位置：

```text
NovelTranslationWorkflowService.runDraftWorkflow(...)
```

当前 catch：

```java
} catch (RuntimeException exception) {
    flushTraceArtifacts();
    traceRecorder.failRun(exception);
    throw exception;
}
```

`flushTraceArtifacts()` 可能抛 `IllegalStateException`。如果 flush 失败，原始业务异常会被覆盖。

### 7.2 解决方案

原始异常优先：

```java
} catch (RuntimeException exception) {
    try {
        traceRecorder.failRun(exception);
        flushTraceArtifacts();
    } catch (RuntimeException flushException) {
        exception.addSuppressed(flushException);
    }
    throw exception;
}
```

并且建议先 `failRun(exception)` 再 flush，让 trace artifact 能记录失败事件。

## 8. P2 问题：`GlobalNamingStageAssembler` 重复计算

### 8.1 当前行为

位置：

```text
src/main/java/io/quillloom/application/translation/assembler/GlobalNamingStageAssembler.java
```

当前在同一次 assemble 中重复调用：

```java
buildHardEntries(projectMemory)
buildSoftEntries(projectMemory, knowledgeBase, selectedCards)
```

### 8.2 解决方案

改为：

```java
List<GlossaryEntry> hardEntries = buildHardEntries(projectMemory);
List<GlossaryEntry> softEntries = buildSoftEntries(projectMemory, knowledgeBase, selectedCards);
DraftStageGlobalGlossary glossary = new DraftStageGlobalGlossary(
        hardEntries,
        softEntries,
        Map.of(
                "hardEntryCount", hardEntries.size(),
                "softEntryCount", softEntries.size()
        )
);
```

这是低风险清理。

## 9. P2 问题：Alias consistency table 构建逻辑重复

### 9.1 当前重复

- `GlobalNamingStageAssembler`
- `PostDraftReviewPackageAssembler`

两边都有：

- `dedup(...)`
- `stringList(...)`
- `stringValue(...)`
- `parseAliasState(...)`
- alias cluster 构建逻辑

### 9.2 解决方案

后置提取：

```text
src/main/java/io/quillloom/application/translation/assembler/AliasConsistencyTableBuilder.java
```

或放在更中性的 application 层包：

```text
src/main/java/io/quillloom/application/memory/AliasConsistencyTableBuilder.java
```

输入：

```java
ChunkAnnotation chunk
List<ChunkAnnotation> chunks
ProjectKnowledgeBase knowledgeBase
```

输出：

```java
GlobalAliasConsistencyTable
```

本项不应和 P0/P1 修复混做。

## 10. P2 问题：`PostDraftReviewAgentService` 望远镜构造器

### 10.1 当前问题

`PostDraftReviewAgentService` 构造器过多，其中两个接受以下参数但完全忽略：

- `PostDraftReviewStrategyResolver`
- `PostDraftRetranslationService`

这会误导调用方，以为传入依赖生效。

### 10.2 解决方案

短期：

- 删除包含死参数的两个构造器。
- 保留 Spring 全参构造器。
- 保留必要测试构造器。

中期：

- 引入参数对象或 builder，例如 `PostDraftReviewAgentServiceDependencies`。

本项不影响正在跑的 D 初稿，但影响 Review Agent 维护健康。

## 11. P3 问题：`ChapterMemorySnapshot` 缺少防御性拷贝

### 11.1 当前行为

位置：

```text
src/main/java/io/quillloom/domain/memory/ChapterMemorySnapshot.java
```

record 没有 compact constructor：

```java
public record ChapterMemorySnapshot(
        String chapterId,
        Map<String, String> confirmedTerms,
        List<String> unresolvedIssues,
        List<String> continuityNotes
) {
}
```

外部传入可变 Map/List 后，后续修改会污染 snapshot。

### 11.2 解决方案

与 `ProjectMemorySnapshot` 对齐：

```java
public ChapterMemorySnapshot {
    confirmedTerms = confirmedTerms == null ? Map.of() : Map.copyOf(confirmedTerms);
    unresolvedIssues = unresolvedIssues == null ? List.of() : List.copyOf(unresolvedIssues);
    continuityNotes = continuityNotes == null ? List.of() : List.copyOf(continuityNotes);
}
```

## 12. P3 问题：`TranslationTaskInputAssembler.buildCoarseBlockContext(...)` 过长

### 12.1 当前问题

该方法包含：

- 查当前 coarse block index；
- 查 previous / next block；
- 过滤当前 block 的 chunks；
- 查当前 chunk 在 block 内序号；
- 10 参数构造 `CoarseBlockContext`。

可读性弱，但当前行为没有明显错误。

### 12.2 解决方案

后置提取：

```java
private int findBlockIndex(List<CoarseChunkBlock> blocks, String coarseBlockId)
private int findChunkIndexInBlock(List<ChunkAnnotation> chunksInCurrentBlock, String chunkId)
private CoarseBlockContext toCoarseBlockContext(...)
```

本项不应阻塞 127 chunk 冒烟。

## 13. 建议实施顺序

### Phase 1：正确性修复，必须先做

1. 新增 `TermTextNormalizer`。
2. 修 D 阶段 confirmed term 冲突检测：同 source key 不同 target key 不直接 fail-fast，而是返回当前 chunk 可 repair 的冲突诊断。
3. 在 D 当前 chunk 翻译流程中加入 confirmed term conflict repair loop，最多额外 repair 3 次。
4. 修 post-draft / API / agent term writer 的 confirmed term 合并 key：`PostDraftReviewPackageAssembler`、`WorkflowDraftRunResponse`、`RepositoryBackedPostDraftReviewAgentTermWriter`。这些非 D repair 场景仍应在冲突时抛出受控异常。
5. 修 candidate dedup key：`TranslationApplicationService`、`PostDraftReviewPackageAssembler`、`WorkflowDraftRunResponse` 如涉及 candidate 聚合。
6. 全部 `toLowerCase()` 改成 `toLowerCase(Locale.ROOT)`。
7. 修 `LlmChunkTranslator.executeRevisionRoundWithFallback(...)`，禁止吞通用 RuntimeException。

### Phase 2：诊断可信度修复

1. `runDraftWorkflow(...)` 中先 `failRun(exception)` 再 flush。
2. flush 失败加 suppressed，不覆盖原始异常。
3. `ChapterMemorySnapshot` 加 defensive copy。
4. `GlobalNamingStageAssembler` 去掉重复计算。

### Phase 3：架构清理，后置

1. 删除 `NovelTranslationWorkflowService` 中硬编码 in-memory 的 convenience constructor。
2. 收敛 `PostDraftReviewAgentService` 望远镜构造器。
3. `WorkflowTraceRecorder` DI 化。
4. 提取 alias consistency table builder。
5. 统一 `nullToEmpty` / normalize 工具。

## 14. 测试计划

### 14.1 Term key 语义测试

新增或修改：

- `TranslationApplicationServiceTest`
- `PostDraftContinuationAssemblyTest`
- `WorkflowDraftRunResponseTest` 如不存在则新增
- `RepositoryBackedPostDraftReviewAgentTermWriterTest`

覆盖：

```text
TranslationApplicationService.evolveProjectMemory:
chunk-1 confirmedTermUpdates={Le Condé=孔代咖啡馆}
chunk-2 confirmedTermUpdates={le condé=勒孔代咖啡馆}
=> 不直接终止全链路，返回当前 chunk 的 confirmed term conflict 诊断，并触发 conflict repair loop

TranslationApplicationService.evolveProjectMemory:
chunk-1 confirmedTermUpdates={Le Condé=孔代咖啡馆}
chunk-2 confirmedTermUpdates={le condé=孔代咖啡馆}
=> duplicate, no conflict, final display source/target 保留第一次出现的 Le Condé / 孔代咖啡馆

D confirmed term conflict repair:
effectiveProjectMemory={Le Condé=孔代咖啡馆}
当前 chunk 初始输出 confirmedTermUpdates={le condé=勒孔代咖啡馆}
repair prompt 必须包含 existingSourceTerm=Le Condé, existingTargetTerm=孔代咖啡馆, incomingSourceTerm=le condé, incomingTargetTerm=勒孔代咖啡馆
repair 后输出正文沿用“孔代咖啡馆”，confirmedTermUpdates 删除冲突项或改为 Le Condé=孔代咖啡馆
=> 当前 chunk 成功，流程继续

D confirmed term conflict repair exhausted:
同一个 chunk 连续 3 次 repair 后仍输出 le condé=勒孔代咖啡馆
=> 抛 confirmed_term_conflict_repair_exhausted，停止 D 链路

TranslationApplicationService candidate dedup:
chunk-1 candidateUpdates=[Le Condé -> 孔代咖啡馆]
chunk-2 candidateUpdates=[le condé -> 孔代咖啡馆]
=> one candidate

PostDraftReviewPackageAssembler / WorkflowDraftRunResponse / RepositoryBackedPostDraftReviewAgentTermWriter:
Le Condé -> 孔代咖啡馆
le condé -> 孔代咖啡馆
=> duplicate, no conflict

Le Condé -> 孔代咖啡馆
le condé -> 勒孔代咖啡馆
=> confirmed_term_conflict

Le Condé candidate 孔代咖啡馆
le condé candidate 孔代咖啡馆
=> one candidate
```

### 14.2 Locale 测试

新增局部测试：

```java
Locale previous = Locale.getDefault();
Locale.setDefault(Locale.forLanguageTag("tr"));
try {
    ...
} finally {
    Locale.setDefault(previous);
}
```

验证 `I` / `i` 相关 key 不受默认 locale 影响。

### 14.3 Revision fallback 测试

新增：

- 可恢复结构化输出异常：允许 fallback。
- `NullPointerException` / `IllegalStateException`：必须向上抛。
- LLM client transient exception：必须向上抛，由外层重试/失败处理。

### 14.4 Workflow exception 测试

覆盖：

- 业务异常发生；
- flush trace 也失败；
- 最终抛出的是业务异常；
- flush 异常出现在 suppressed 中。

## 15. 红线自检

- 不新增 Review Tool。
- 不改变 C0/D/Review Agent 职责边界。
- 不做联网策略修改。
- 不把运行期临时状态塞回 `TranslationTaskInput`。
- 不用 fallback 掩盖程序 bug。
- 不做大规模 alias builder 抽取，除非进入 Phase 3。
