# [OUTDATED - 已被 2026-04-15-project-level-unified-review-agent-loop-plan.md 取代] Project-Level Autonomous Review Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 `PostDraftReviewPackage + ProjectKnowledgeBase` 底座上实现第一阶段“项目级单自治审校/修订 agent”最小闭环：可按 `projectId + 当前工作焦点` 启动、按需读取正式资产、维护独立 session、多步收敛，并产出正式译文与过程说明。

**Architecture:** 新增一套位于 `application/postdraft/review` 与 `infrastructure/postdraft/review` 的审校 agent 独立实现层，负责组织启动命令、session、能力面与 loop；正式资产继续复用 `PostDraftReviewPackageRepository` 与 `ProjectKnowledgeBaseRepository`；运行期状态只存在于 review session，不回写 `TranslationTaskInput`、`PostDraftReviewPackage` 或其他稳定契约。除为复用正式资产而做最小桥接外，不把实现掺回原先初稿流水线包。第一阶段不实现 subagent、默认 web search、问题表与复杂回改 agenda，只保证单 agent 最小可运行闭环成立。

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, existing postdraft/translation repositories and domain records

---

## File Structure

### New application files

- `src/main/java/io/quillloom/application/postdraft/review/command/StartPostDraftReviewAgentCommand.java`
  - 启动命令，表达 `projectId`、工作焦点与人工指定关注点。
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewFocus.java`
  - 轻量任务锚点，表达 chunk/block/问题类型焦点。
- `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewSession.java`
  - 运行期 session，承载任务锚点、已读摘要、问题模型、证据状态、策略倾向、人审状态。
- `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewAgentResult.java`
  - 最终结果，包含正式译文与过程说明。
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewProcessSummary.java`
  - 过程说明结构化对象。
- `src/main/java/io/quillloom/application/postdraft/review/model/HumanReviewRequest.java`
  - 人审请求对象。
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewStrategy.java`
  - `KEEP / LIGHT_EDIT / DEEP_EDIT / RETRANSLATE / REQUIRE_HUMAN_REVIEW`
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewProblemType.java`
  - 问题类型枚举。
- `src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentReader.java`
  - 审校 agent 读取正式资产的领域能力口。
- `src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentWriter.java`
  - 审校 agent 输出正式译文与说明的能力口。
- `src/main/java/io/quillloom/application/postdraft/review/port/out/HumanInTheLoopGateway.java`
  - 人工请求/恢复能力口。
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewSessionFactory.java`
  - 由启动命令与正式资产构建初始 session。
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewProblemClassifier.java`
  - 初步问题识别。
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewEvidencePlanner.java`
  - 决定下一步该读什么。
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewStrategyResolver.java`
  - 决定保留/轻修/深修/重译/转人工。
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewProcessSummaryAssembler.java`
  - 生成结构化过程说明。
- `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`
  - 第一阶段 loop 编排器。

### New infrastructure files

- `src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java`
  - 复用已有 repository，按需读取 `PostDraftReviewPackage` 与 `ProjectKnowledgeBase`。
- `src/main/java/io/quillloom/infrastructure/postdraft/review/PassThroughPostDraftReviewAgentWriter.java`
  - 第一阶段最小 writer，返回正式结果对象，不直接持久化到新表。
- `src/main/java/io/quillloom/infrastructure/postdraft/review/InMemoryHumanInTheLoopGateway.java`
  - 测试/最小实现用的人审网关。

### Modified files

- `src/main/java/io/quillloom/application/postdraft/assembler/PostDraftContinuationContextAssembler.java`
  - 评估是否抽出已有 continuation 读取逻辑给新 reader 复用。
- `src/main/java/io/quillloom/application/postdraft/port/out/PostDraftReviewPackageRepository.java`
  - 只在需要时补辅助读取方法；默认优先保持不变。
- `src/main/java/io/quillloom/infrastructure/preprocess/PostgresProjectKnowledgeBaseRepository.java`
  - 仅在 reader 装配需要时补测试支撑；默认优先保持不变。

### New tests

- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewSessionFactoryTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewStrategyResolverTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`
- `src/test/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReaderTest.java`

---

### Task 1: 定义启动命令、session 与结果契约

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/command/StartPostDraftReviewAgentCommand.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewFocus.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewSession.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/PostDraftReviewAgentResult.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewProcessSummary.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/HumanReviewRequest.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewStrategy.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/model/ReviewProblemType.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewSessionFactoryTest.java`

- [ ] **Step 1: 写失败测试，约束 session 只承载运行期状态，不复制正式资产全文**

```java
package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.command.StartPostDraftReviewAgentCommand;
import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.service.PostDraftReviewSessionFactory;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostDraftReviewSessionFactoryTest {

    @Test
    void shouldCreateMinimalSessionFromProjectIdAndFocus() {
        StartPostDraftReviewAgentCommand command = new StartPostDraftReviewAgentCommand(
                "project-1",
                ReviewFocus.forChunk("chunk-3"),
                "优先检查这一段是否需要重译"
        );
        PostDraftReviewPackage reviewPackage = ReviewAgentFixtures.reviewPackageWithChunk("project-1", "chunk-3");
        PostDraftReviewSessionFactory factory = new PostDraftReviewSessionFactory();

        PostDraftReviewSession session = factory.create(command, reviewPackage);

        assertEquals("project-1", session.projectId());
        assertEquals("chunk-3", session.focus().chunkId());
        assertTrue(session.readContextSummaries().isEmpty());
        assertTrue(session.problemTypes().isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试，确认因缺少命令与 session 契约而失败**

Run: `mvn -q "-Dtest=PostDraftReviewSessionFactoryTest" test`
Expected: FAIL，提示 `StartPostDraftReviewAgentCommand` / `PostDraftReviewSession` / `PostDraftReviewSessionFactory` 不存在

- [ ] **Step 3: 写最小命令、session 与结果对象**

```java
package io.quillloom.application.postdraft.review.model;

import java.util.List;
import java.util.Set;

public record PostDraftReviewSession(
        String projectId,
        ReviewFocus focus,
        String operatorNote,
        List<String> readContextSummaries,
        Set<ReviewProblemType> problemTypes,
        List<String> evidenceSummaries,
        ReviewStrategy strategy,
        boolean waitingForHumanReview
) {

    public PostDraftReviewSession {
        readContextSummaries = readContextSummaries == null ? List.of() : List.copyOf(readContextSummaries);
        problemTypes = problemTypes == null ? Set.of() : Set.copyOf(problemTypes);
        evidenceSummaries = evidenceSummaries == null ? List.of() : List.copyOf(evidenceSummaries);
        strategy = strategy == null ? ReviewStrategy.KEEP : strategy;
    }
}
```

```java
package io.quillloom.application.postdraft.review.command;

import io.quillloom.application.postdraft.review.model.ReviewFocus;

public record StartPostDraftReviewAgentCommand(
        String projectId,
        ReviewFocus focus,
        String operatorNote
) {
}
```

- [ ] **Step 4: 写最小 session factory**

```java
package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.command.StartPostDraftReviewAgentCommand;
import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;

import java.util.List;
import java.util.Set;

public class PostDraftReviewSessionFactory {

    public PostDraftReviewSession create(StartPostDraftReviewAgentCommand command,
                                         PostDraftReviewPackage reviewPackage) {
        return new PostDraftReviewSession(
                command.projectId(),
                command.focus(),
                command.operatorNote(),
                List.of(),
                Set.of(),
                List.of(),
                ReviewStrategy.KEEP,
                false
        );
    }
}
```

- [ ] **Step 5: 运行测试，确认最小契约通过**

Run: `mvn -q "-Dtest=PostDraftReviewSessionFactoryTest" test`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/quillloom/application/postdraft/review src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewSessionFactoryTest.java
git commit -m "feat: add post-draft review session contracts"
```

### Task 2: 实现按需读取正式资产的 reader 能力口

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentReader.java`
- Create: `src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/assembler/PostDraftContinuationContextAssembler.java`
- Test: `src/test/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReaderTest.java`

- [ ] **Step 1: 写失败测试，约束 reader 可按 projectId + focus 读取最小正式资产**

```java
package io.quillloom.infrastructure.postdraft.review;

import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.domain.postdraft.PostDraftContinuationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepositoryBackedPostDraftReviewAgentReaderTest {

    @Test
    void shouldLoadContinuationContextForFocusedChunk() {
        RepositoryBackedPostDraftReviewAgentReader reader = ReviewAgentFixtures.readerForChunk("project-1", "chunk-2");

        PostDraftContinuationContext context = reader.loadContinuationContext("project-1", ReviewFocus.forChunk("chunk-2"));

        assertEquals("project-1", context.reviewPackage().projectId());
        assertEquals("chunk-2", context.reviewPackage().chunks().get(1).chunkId());
    }
}
```

- [ ] **Step 2: 运行测试，确认因 reader 不存在而失败**

Run: `mvn -q "-Dtest=RepositoryBackedPostDraftReviewAgentReaderTest" test`
Expected: FAIL，提示 `RepositoryBackedPostDraftReviewAgentReader` / `PostDraftReviewAgentReader` 不存在

- [ ] **Step 3: 定义 reader 能力口，只暴露第一阶段必需读取动作**

```java
package io.quillloom.application.postdraft.review.port.out;

import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.domain.postdraft.PostDraftContinuationContext;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.util.List;

public interface PostDraftReviewAgentReader {

    PostDraftContinuationContext loadContinuationContext(String projectId, ReviewFocus focus);

    List<PostDraftChunkRecord> readAdjacentChunks(String projectId, String chunkId, int before, int after);

    List<PostDraftChunkRecord> searchChunksByKeyword(String projectId, String keyword);
}
```

- [ ] **Step 4: 用已有 repository + continuation assembler 实现最小 reader**

```java
package io.quillloom.infrastructure.postdraft.review;

import io.quillloom.application.postdraft.assembler.PostDraftContinuationContextAssembler;
import io.quillloom.application.postdraft.port.out.PostDraftReviewPackageRepository;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.application.preprocess.port.out.ProjectKnowledgeBaseRepository;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftContinuationContext;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;

import java.util.List;

public class RepositoryBackedPostDraftReviewAgentReader implements PostDraftReviewAgentReader {

    private final PostDraftReviewPackageRepository reviewPackageRepository;
    private final ProjectKnowledgeBaseRepository knowledgeBaseRepository;
    private final PostDraftContinuationContextAssembler continuationContextAssembler;

    public RepositoryBackedPostDraftReviewAgentReader(PostDraftReviewPackageRepository reviewPackageRepository,
                                                      ProjectKnowledgeBaseRepository knowledgeBaseRepository,
                                                      PostDraftContinuationContextAssembler continuationContextAssembler) {
        this.reviewPackageRepository = reviewPackageRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.continuationContextAssembler = continuationContextAssembler;
    }

    @Override
    public PostDraftContinuationContext loadContinuationContext(String projectId, ReviewFocus focus) {
        PostDraftReviewPackage reviewPackage = reviewPackageRepository.load(projectId).orElseThrow();
        return continuationContextAssembler.assemble(
                reviewPackage,
                knowledgeBaseRepository.load(projectId).orElseThrow()
        );
    }

    @Override
    public List<PostDraftChunkRecord> readAdjacentChunks(String projectId, String chunkId, int before, int after) {
        PostDraftReviewPackage reviewPackage = reviewPackageRepository.load(projectId).orElseThrow();
        return ReviewAgentChunkWindowSupport.slice(reviewPackage.chunks(), chunkId, before, after);
    }

    @Override
    public List<PostDraftChunkRecord> searchChunksByKeyword(String projectId, String keyword) {
        PostDraftReviewPackage reviewPackage = reviewPackageRepository.load(projectId).orElseThrow();
        return reviewPackage.chunks().stream()
                .filter(chunk -> chunk.sourceText().contains(keyword) || chunk.translatedText().contains(keyword))
                .toList();
    }
}
```

- [ ] **Step 5: 运行测试，确认 reader 可加载最小 continuation context**

Run: `mvn -q "-Dtest=RepositoryBackedPostDraftReviewAgentReaderTest" test`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentReader.java src/main/java/io/quillloom/infrastructure/postdraft/review src/test/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReaderTest.java
git commit -m "feat: add repository-backed post-draft review reader"
```

### Task 3: 实现问题识别与策略收敛的最小判断层

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewProblemClassifier.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewStrategyResolver.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewStrategyResolverTest.java`

- [ ] **Step 1: 写失败测试，约束“有未决 note 时优先转人工，无未决 note 时可进入轻修/重译判断”**

```java
package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.service.PostDraftReviewStrategyResolver;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostDraftReviewStrategyResolverTest {

    @Test
    void shouldRequireHumanReviewWhenDecisionNotesRemainUnresolved() {
        PostDraftChunkRecord chunk = ReviewAgentFixtures.chunkWithDecisionNote("chunk-1", "issue");
        PostDraftReviewStrategyResolver resolver = new PostDraftReviewStrategyResolver();

        ReviewStrategy strategy = resolver.resolve(chunk, 2, true);

        assertEquals(ReviewStrategy.REQUIRE_HUMAN_REVIEW, strategy);
    }
}
```

- [ ] **Step 2: 运行测试，确认因 resolver 不存在而失败**

Run: `mvn -q "-Dtest=PostDraftReviewStrategyResolverTest" test`
Expected: FAIL，提示 `PostDraftReviewStrategyResolver` 不存在

- [ ] **Step 3: 实现最小分类器与策略解析器**

```java
package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.ReviewProblemType;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

import java.util.LinkedHashSet;
import java.util.Set;

public class PostDraftReviewProblemClassifier {

    public Set<ReviewProblemType> classify(PostDraftChunkRecord chunk) {
        Set<ReviewProblemType> result = new LinkedHashSet<>();
        if (!chunk.decisionNotes().isEmpty()) {
            result.add(ReviewProblemType.UNRESOLVED_DECISION);
        }
        if (chunk.transitionNote() != null) {
            result.add(ReviewProblemType.TRANSITION_CONTINUITY);
        }
        return Set.copyOf(result);
    }
}
```

```java
package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

public class PostDraftReviewStrategyResolver {

    public ReviewStrategy resolve(PostDraftChunkRecord chunk,
                                  int evidenceCount,
                                  boolean unresolvedDecisionExists) {
        if (unresolvedDecisionExists) {
            return ReviewStrategy.REQUIRE_HUMAN_REVIEW;
        }
        if (evidenceCount >= 2 && chunk.translatedText().length() < 40) {
            return ReviewStrategy.RETRANSLATE;
        }
        return ReviewStrategy.LIGHT_EDIT;
    }
}
```

- [ ] **Step 4: 运行测试，确认策略解析最小规则通过**

Run: `mvn -q "-Dtest=PostDraftReviewStrategyResolverTest" test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewProblemClassifier.java src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewStrategyResolver.java src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewStrategyResolverTest.java
git commit -m "feat: add minimal review problem classification"
```

### Task 4: 实现单 agent 最小 loop 与结果输出

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentWriter.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewProcessSummaryAssembler.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`
- Create: `src/main/java/io/quillloom/infrastructure/postdraft/review/PassThroughPostDraftReviewAgentWriter.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`

- [ ] **Step 1: 写失败测试，约束最小 loop 能输出正式译文与过程说明**

```java
package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.command.StartPostDraftReviewAgentCommand;
import io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.service.PostDraftReviewAgentService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostDraftReviewAgentServiceTest {

    @Test
    void shouldProduceFinalTranslationAndProcessSummary() {
        PostDraftReviewAgentService service = ReviewAgentFixtures.minimalAgentServiceForChunk("project-1", "chunk-2");

        PostDraftReviewAgentResult result = service.review(
                new StartPostDraftReviewAgentCommand("project-1", ReviewFocus.forChunk("chunk-2"), "优先检查衔接")
        );

        assertFalse(result.finalTranslatedText().isBlank());
        assertTrue(result.processSummary().strategy() != null);
    }
}
```

- [ ] **Step 2: 运行测试，确认因 service 不存在而失败**

Run: `mvn -q "-Dtest=PostDraftReviewAgentServiceTest" test`
Expected: FAIL，提示 `PostDraftReviewAgentService` / `PostDraftReviewAgentResult` 不存在

- [ ] **Step 3: 定义 writer、过程说明 assembler 与最小 loop**

```java
package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.command.StartPostDraftReviewAgentCommand;
import io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult;
import io.quillloom.application.postdraft.review.model.PostDraftReviewSession;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentReader;
import io.quillloom.application.postdraft.review.port.out.PostDraftReviewAgentWriter;
import io.quillloom.application.postdraft.review.port.out.HumanInTheLoopGateway;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;

public class PostDraftReviewAgentService {

    private final PostDraftReviewAgentReader reader;
    private final PostDraftReviewSessionFactory sessionFactory;
    private final PostDraftReviewProblemClassifier problemClassifier;
    private final PostDraftReviewStrategyResolver strategyResolver;
    private final PostDraftReviewProcessSummaryAssembler summaryAssembler;
    private final PostDraftReviewAgentWriter writer;
    private final HumanInTheLoopGateway humanGateway;

    public PostDraftReviewAgentResult review(StartPostDraftReviewAgentCommand command) {
        var context = reader.loadContinuationContext(command.projectId(), command.focus());
        PostDraftReviewSession session = sessionFactory.create(command, context.reviewPackage());
        PostDraftChunkRecord currentChunk = ReviewAgentChunkWindowSupport.findChunk(context.reviewPackage().chunks(), command.focus().chunkId());
        var problemTypes = problemClassifier.classify(currentChunk);
        ReviewStrategy strategy = strategyResolver.resolve(currentChunk, 2, !currentChunk.decisionNotes().isEmpty());
        if (strategy == ReviewStrategy.REQUIRE_HUMAN_REVIEW) {
            return writer.writeHumanRequired(summaryAssembler.assemble(currentChunk, strategy, problemTypes));
        }
        String finalText = currentChunk.translatedText().trim();
        return writer.writeCompleted(finalText, summaryAssembler.assemble(currentChunk, strategy, problemTypes));
    }
}
```

- [ ] **Step 4: 运行测试，确认最小闭环通过**

Run: `mvn -q "-Dtest=PostDraftReviewAgentServiceTest" test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/quillloom/application/postdraft/review src/main/java/io/quillloom/infrastructure/postdraft/review/PassThroughPostDraftReviewAgentWriter.java src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java
git commit -m "feat: add minimal post-draft review agent loop"
```

### Task 5: 接入最小 human-in-the-loop 并补回归验证

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/review/port/out/HumanInTheLoopGateway.java`
- Create: `src/main/java/io/quillloom/infrastructure/postdraft/review/InMemoryHumanInTheLoopGateway.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java`

- [ ] **Step 1: 写失败测试，约束存在未决 decision note 时 agent 返回人工请求而不是强行改写**

```java
@Test
void shouldReturnHumanReviewRequestWhenStrategyRequiresHuman() {
    PostDraftReviewAgentService service = ReviewAgentFixtures.agentServiceRequiringHuman("project-1", "chunk-1");

    PostDraftReviewAgentResult result = service.review(
            new StartPostDraftReviewAgentCommand("project-1", ReviewFocus.forChunk("chunk-1"), "这里要保留歧义")
    );

    assertTrue(result.humanReviewRequest().isPresent());
    assertTrue(result.finalTranslatedText().isBlank());
}
```

- [ ] **Step 2: 运行测试，确认因人审通路未接入而失败**

Run: `mvn -q "-Dtest=PostDraftReviewAgentServiceTest" test`
Expected: FAIL，提示 `humanReviewRequest()` 为空或 gateway 不存在

- [ ] **Step 3: 实现最小人审网关与结果恢复通路**

```java
package io.quillloom.application.postdraft.review.port.out;

import io.quillloom.application.postdraft.review.model.HumanReviewRequest;

public interface HumanInTheLoopGateway {

    HumanReviewRequest submit(HumanReviewRequest request);
}
```

```java
package io.quillloom.infrastructure.postdraft.review;

import io.quillloom.application.postdraft.review.model.HumanReviewRequest;
import io.quillloom.application.postdraft.review.port.out.HumanInTheLoopGateway;

public class InMemoryHumanInTheLoopGateway implements HumanInTheLoopGateway {

    @Override
    public HumanReviewRequest submit(HumanReviewRequest request) {
        return request;
    }
}
```

- [ ] **Step 4: 运行回归测试，确认“正常完成”和“请求人工”两条路径都通过**

Run: `mvn -q "-Dtest=PostDraftReviewSessionFactoryTest,RepositoryBackedPostDraftReviewAgentReaderTest,PostDraftReviewStrategyResolverTest,PostDraftReviewAgentServiceTest" test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/quillloom/application/postdraft/review/port/out/HumanInTheLoopGateway.java src/main/java/io/quillloom/infrastructure/postdraft/review/InMemoryHumanInTheLoopGateway.java src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java
git commit -m "feat: add minimal human review path for review agent"
```

## Spec Coverage Check

- 已覆盖：`projectId + 当前工作焦点` 启动、按需读取 `PostDraftReviewPackage + ProjectKnowledgeBase`、独立 session、单 agent 最小 loop、正式译文输出、过程说明输出、最小 human-in-the-loop。
- 明确保留到后续阶段：默认 web search、subagent、问题表、复杂风格建模、跨对象回改 agenda、复杂持久化恢复平台。

## Verification Commands

- `mvn -q "-Dtest=PostDraftReviewSessionFactoryTest" test`
- `mvn -q "-Dtest=RepositoryBackedPostDraftReviewAgentReaderTest" test`
- `mvn -q "-Dtest=PostDraftReviewStrategyResolverTest" test`
- `mvn -q "-Dtest=PostDraftReviewAgentServiceTest" test`
- `mvn -q "-Dtest=PostDraftReviewSessionFactoryTest,RepositoryBackedPostDraftReviewAgentReaderTest,PostDraftReviewStrategyResolverTest,PostDraftReviewAgentServiceTest" test`

## Notes

- 第一阶段不要把 session 回写到 `TranslationTaskInput`、`PostDraftReviewPackage` 或 `ProjectKnowledgeBase`。
- 第一阶段不要默认启用 web search；若后续接入，只能通过显式受控能力口。
- 第一阶段不要引入 subagent；若后续接入，只能作为受控子任务扩展。
- 新代码默认放在 `io.quillloom.application.postdraft.review` 与 `io.quillloom.infrastructure.postdraft.review` 下，避免与原初稿流水线实现混杂。
