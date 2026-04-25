# Minimal PostDraftReviewPackage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为初稿完成后的后续 agent 提供正式的最小启动包持久化、加载与恢复入口，并保持 `ProjectKnowledgeBase` 继续作为独立知识底座。

**Architecture:** 新增 `PostDraftReviewPackage` 领域对象和独立 repository，按 `projectId` 保存/加载初稿后启动包；workflow/application 层负责把初稿结果收敛成结果包，并在恢复时联动 `ProjectKnowledgeBase` 组装 continuation context。严格不把 `ExecutionContextView`、`TranslationTaskInput`、`PreprocessDossier` 全量和 loop 临时状态塞进正式契约。

**Tech Stack:** Java 21, Spring Boot, JUnit 5, PostgreSQL/JdbcTemplate, in-memory repository

---

### Task 1: 定义结果包领域对象与恢复上下文

**Files:**
- Create: `src/main/java/io/quillloom/domain/postdraft/PostDraftReviewPackage.java`
- Create: `src/main/java/io/quillloom/domain/postdraft/PostDraftChunkRecord.java`
- Create: `src/main/java/io/quillloom/domain/postdraft/PostDraftBlockIndex.java`
- Create: `src/main/java/io/quillloom/domain/postdraft/PostDraftTermState.java`
- Create: `src/main/java/io/quillloom/domain/postdraft/PostDraftContinuationContext.java`
- Test: `src/test/java/io/quillloom/domain/postdraft/PostDraftReviewPackageContractTest.java`

- [ ] **Step 1: 写失败测试，约束最小结果包字段与 chunk 导航语义**

```java
package io.quillloom.domain.postdraft;

import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.translation.TranslationDecisionNote;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PostDraftReviewPackageContractTest {

    @Test
    void shouldPreserveChunkNavigationAndTermState() {
        PostDraftChunkRecord chunk = new PostDraftChunkRecord(
                "chunk-2",
                2,
                "block-1",
                "source text",
                "translated text",
                "commentary",
                List.of(new TranslationDecisionNote("risk", "focus", "issue", "action")),
                Map.of("Louki", "露姬"),
                List.of(new TranslationCandidateUpdate("Black Maria", "黑色马车", "候选", true)),
                new ChunkTransitionNote("before", "after", false)
        );
        PostDraftTermState termState = new PostDraftTermState(
                Map.of("Louki", "露姬"),
                List.of(new TranslationCandidateUpdate("Black Maria", "黑色马车", "候选", true))
        );

        PostDraftReviewPackage resultPackage = new PostDraftReviewPackage(
                "project-1",
                "v1",
                "fr",
                "zh",
                "digest-1",
                Instant.parse("2026-04-14T10:15:30Z"),
                List.of(chunk),
                List.of(new PostDraftBlockIndex("block-1", "街道夜行", List.of("chunk-2"))),
                termState,
                DraftStageGlobalGlossary.empty(),
                GlobalAliasConsistencyTable.empty(),
                "merged text"
        );

        assertEquals("project-1", resultPackage.projectId());
        assertEquals("chunk-2", resultPackage.chunks().get(0).chunkId());
        assertEquals(2, resultPackage.chunks().get(0).sequence());
        assertEquals("block-1", resultPackage.chunks().get(0).blockId());
        assertEquals("source text", resultPackage.chunks().get(0).sourceText());
        assertSame(termState, resultPackage.termState());
    }
}
```

- [ ] **Step 2: 运行测试，确认因缺少对象定义而失败**

Run: `mvn -q "-Dtest=PostDraftReviewPackageContractTest" test`
Expected: FAIL，提示 `io.quillloom.domain.postdraft` 下对象不存在

- [ ] **Step 3: 写最小领域对象实现**

```java
package io.quillloom.domain.postdraft;

import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;

import java.time.Instant;
import java.util.List;

public record PostDraftReviewPackage(
        String projectId,
        String packageVersion,
        String sourceLanguage,
        String targetLanguage,
        String sourceDocumentDigest,
        Instant createdAt,
        List<PostDraftChunkRecord> chunks,
        List<PostDraftBlockIndex> blockIndexes,
        PostDraftTermState termState,
        DraftStageGlobalGlossary glossarySnapshot,
        GlobalAliasConsistencyTable aliasSnapshot,
        String mergedDraftText
) {

    public PostDraftReviewPackage {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        blockIndexes = blockIndexes == null ? List.of() : List.copyOf(blockIndexes);
        glossarySnapshot = glossarySnapshot == null ? DraftStageGlobalGlossary.empty() : glossarySnapshot;
        aliasSnapshot = aliasSnapshot == null ? GlobalAliasConsistencyTable.empty() : aliasSnapshot;
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q "-Dtest=PostDraftReviewPackageContractTest" test`
Expected: PASS

### Task 2: 定义 repository 端口与内存实现

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/port/out/PostDraftReviewPackageRepository.java`
- Create: `src/main/java/io/quillloom/infrastructure/postdraft/InMemoryPostDraftReviewPackageRepository.java`
- Test: `src/test/java/io/quillloom/infrastructure/postdraft/InMemoryPostDraftReviewPackageRepositoryTest.java`

- [ ] **Step 1: 写失败测试，约束按 projectId 保存/加载**

```java
package io.quillloom.infrastructure.postdraft;

import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import io.quillloom.domain.postdraft.PostDraftTermState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryPostDraftReviewPackageRepositoryTest {

    @Test
    void shouldSaveAndLoadByProjectId() {
        InMemoryPostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
        PostDraftReviewPackage expected = new PostDraftReviewPackage(
                "project-1",
                "v1",
                "fr",
                "zh",
                "digest-1",
                Instant.parse("2026-04-14T10:15:30Z"),
                List.of(),
                List.of(),
                new PostDraftTermState(Map.of("Louki", "露姬"), List.of()),
                DraftStageGlobalGlossary.empty(),
                GlobalAliasConsistencyTable.empty(),
                "merged"
        );

        repository.save(expected);

        PostDraftReviewPackage actual = repository.load("project-1").orElseThrow();
        assertEquals("project-1", actual.projectId());
        assertEquals("露姬", actual.termState().effectiveConfirmedTerms().get("Louki"));
    }
}
```

- [ ] **Step 2: 运行测试，确认因缺少 repository 端口/实现而失败**

Run: `mvn -q "-Dtest=InMemoryPostDraftReviewPackageRepositoryTest" test`
Expected: FAIL，提示 repository 不存在

- [ ] **Step 3: 写最小 repository 端口与内存实现**

```java
package io.quillloom.application.postdraft.port.out;

import io.quillloom.domain.postdraft.PostDraftReviewPackage;

import java.util.Optional;

public interface PostDraftReviewPackageRepository {

    Optional<PostDraftReviewPackage> load(String projectId);

    void save(PostDraftReviewPackage reviewPackage);
}
```

```java
package io.quillloom.infrastructure.postdraft;

import io.quillloom.application.postdraft.port.out.PostDraftReviewPackageRepository;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "quillloom.post-draft-review-package", name = "storage", havingValue = "memory", matchIfMissing = true)
public class InMemoryPostDraftReviewPackageRepository implements PostDraftReviewPackageRepository {

    private final Map<String, PostDraftReviewPackage> storage = new ConcurrentHashMap<>();

    @Override
    public Optional<PostDraftReviewPackage> load(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(storage.get(projectId));
    }

    @Override
    public void save(PostDraftReviewPackage reviewPackage) {
        storage.put(reviewPackage.projectId(), reviewPackage);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q "-Dtest=InMemoryPostDraftReviewPackageRepositoryTest" test`
Expected: PASS

### Task 3: 先写 workflow/application 层收敛与恢复测试

**Files:**
- Modify: `src/test/java/io/quillloom/application/workflow/service/NovelTranslationWorkflowServiceTest.java`
- Create: `src/test/java/io/quillloom/application/postdraft/PostDraftContinuationAssemblyTest.java`

- [ ] **Step 1: 写失败测试，约束初稿结果可收敛为启动包**

```java
@Test
void shouldSavePostDraftReviewPackageAfterChunkDrafting() {
    InMemoryPostDraftReviewPackageRepository repository = new InMemoryPostDraftReviewPackageRepository();
    // 构造最小 workflow service 与 draft 结果，保存后断言 repository 中存在 package
}
```

- [ ] **Step 2: 写失败测试，约束恢复时联动知识库与结果包**

```java
@Test
void shouldLoadPostDraftContinuationContextByProjectId() {
    // 预置 PostDraftReviewPackage 与 ProjectKnowledgeBase
    // 调用 workflow/application 恢复入口
    // 断言 continuation context 同时包含 chunks 与 knowledgeBase
}
```

- [ ] **Step 3: 运行测试，确认因缺少装配与恢复入口而失败**

Run: `mvn -q "-Dtest=NovelTranslationWorkflowServiceTest,PostDraftContinuationAssemblyTest" test`
Expected: FAIL，提示缺少保存/恢复 API 或 continuation context

### Task 4: 实现结果包装配器与恢复上下文装配

**Files:**
- Create: `src/main/java/io/quillloom/application/postdraft/assembler/PostDraftReviewPackageAssembler.java`
- Create: `src/main/java/io/quillloom/application/postdraft/assembler/PostDraftContinuationContextAssembler.java`
- Modify: `src/main/java/io/quillloom/application/translation/service/TranslationApplicationService.java`
- Modify: `src/main/java/io/quillloom/application/workflow/service/NovelTranslationWorkflowService.java`

- [ ] **Step 1: 写最小结果包装配器**

```java
public class PostDraftReviewPackageAssembler {

    public PostDraftReviewPackage assemble(PreprocessDossier dossier,
                                           List<ChunkTranslationDraft> drafts,
                                           ProjectMemorySnapshot initialProjectMemory,
                                           DraftCompilation compilation) {
        // 保序收敛 chunk 记录
        // 构造 blockIndexes
        // 复用与 TranslationApplicationService 一致的 confirmed/candidate 合并逻辑
        // 生成 glossary/alias 项目级快照
    }
}
```

- [ ] **Step 2: 在 `TranslationApplicationService` 中抽出可复用的术语累计逻辑**

```java
public PostDraftTermState buildPostDraftTermState(ProjectMemorySnapshot projectMemory,
                                                  List<ChunkTranslationDraft> drafts) {
    // 复用现有 evolveProjectMemory 的合并规则
}
```

- [ ] **Step 3: 写最小 continuation context 装配器**

```java
public class PostDraftContinuationContextAssembler {

    public PostDraftContinuationContext assemble(PostDraftReviewPackage reviewPackage,
                                                 ProjectKnowledgeBase knowledgeBase) {
        return new PostDraftContinuationContext(
                reviewPackage.projectId(),
                reviewPackage.chunks(),
                reviewPackage.blockIndexes(),
                reviewPackage.termState(),
                reviewPackage.glossarySnapshot(),
                reviewPackage.aliasSnapshot(),
                reviewPackage.mergedDraftText(),
                knowledgeBase
        );
    }
}
```

- [ ] **Step 4: 在 workflow service 中新增保存与恢复入口**

```java
public PostDraftReviewPackage savePostDraftReviewPackage(PreprocessDossier dossier,
                                                         List<ChunkTranslationDraft> drafts,
                                                         ProjectMemorySnapshot projectMemory,
                                                         DraftCompilation compilation) {
    PostDraftReviewPackage reviewPackage = assembler.assemble(dossier, drafts, projectMemory, compilation);
    repository.save(reviewPackage);
    return reviewPackage;
}

public PostDraftContinuationContext loadPostDraftContinuationContext(String projectId) {
    PostDraftReviewPackage reviewPackage = repository.load(projectId).orElseThrow();
    ProjectKnowledgeBase knowledgeBase = projectKnowledgeBaseRepository.load(projectId)
            .orElse(ProjectKnowledgeBase.empty(projectId));
    return continuationAssembler.assemble(reviewPackage, knowledgeBase);
}
```

- [ ] **Step 5: 运行 workflow/application 测试，确认通过**

Run: `mvn -q "-Dtest=NovelTranslationWorkflowServiceTest,PostDraftContinuationAssemblyTest" test`
Expected: PASS

### Task 5: 增加 PostgreSQL 持久化实现

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/PostgresKnowledgeBaseSchemaInitializer.java`
- Create: `src/main/java/io/quillloom/infrastructure/postdraft/PostgresPostDraftReviewPackageRepository.java`
- Test: `src/test/java/io/quillloom/infrastructure/postdraft/PostgresPostDraftReviewPackageRepositoryTest.java`

- [ ] **Step 1: 写失败测试，约束 PostgreSQL round-trip**

```java
@Test
void shouldRoundTripPostDraftReviewPackageInPostgres() {
    // 初始化 schema
    // 保存 package
    // 重新 load 并断言 chunk/sourceText/mergedDraftText/termState 均被恢复
}
```

- [ ] **Step 2: 运行测试，确认因缺少 schema/table/repository 而失败**

Run: `mvn -q "-Dtest=PostgresPostDraftReviewPackageRepositoryTest" test`
Expected: FAIL

- [ ] **Step 3: 在 schema initializer 中增加最小结果包表**

```sql
create table if not exists ql_post_draft_review_package (...);
create table if not exists ql_post_draft_chunk_record (...);
create table if not exists ql_post_draft_block_index (...);
create table if not exists ql_post_draft_term_state_confirmed (...);
create table if not exists ql_post_draft_term_state_candidate (...);
```

- [ ] **Step 4: 写最小 PostgreSQL repository 实现**

```java
@Component
@ConditionalOnProperty(prefix = "quillloom.post-draft-review-package", name = "storage", havingValue = "postgres")
public class PostgresPostDraftReviewPackageRepository implements PostDraftReviewPackageRepository {
    // 按 projectId 删除旧记录并整体重写
    // 按 projectId 加载并恢复完整 package
}
```

- [ ] **Step 5: 运行 PostgreSQL 仓储测试，确认通过**

Run: `mvn -q "-Dtest=PostgresPostDraftReviewPackageRepositoryTest" test`
Expected: PASS（若本地未启用 postgres 测试，则输出 ASSUMPTION SKIPPED）

### Task 6: 回归验证与文档对齐

**Files:**
- Modify: `docs/handoff.md`
- Modify: `docs/current-status.md`

- [ ] **Step 1: 更新文档中的实现状态**

```markdown
1. 已新增 `PostDraftReviewPackage` 作为初稿后正式启动包。
2. 后续 agent 可按 `projectId` 联动加载：
   - `PostDraftReviewPackage`
   - `ProjectKnowledgeBase`
```

- [ ] **Step 2: 运行本次相关测试集**

Run: `mvn -q "-Dtest=PostDraftReviewPackageContractTest,InMemoryPostDraftReviewPackageRepositoryTest,PostDraftContinuationAssemblyTest,NovelTranslationWorkflowServiceTest,TranslationApplicationServiceTest" test`
Expected: PASS

- [ ] **Step 3: 可选运行 PostgreSQL 测试**

Run: `mvn -q "-Dtest=PostgresPostDraftReviewPackageRepositoryTest" "-Dquillloom.test.postgres.enabled=true" test`
Expected: PASS，或在未启用环境下跳过

