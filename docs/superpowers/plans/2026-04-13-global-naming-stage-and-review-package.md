# Global Naming Stage And Review Package Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变现有检索层职责与稳定长期 memory 契约的前提下，为 D 初稿前新增“全局命名阶段”，产出 `DraftStageGlobalGlossary` 与 `GlobalAliasConsistencyTable` 两张执行表，并让 D 初稿优先按两张表执行，只对表外项做 confirmed/candidate 增量补充。

**Architecture:** 复用现有 `confirmedTerms`、`candidateTermUpdates`、`CandidateTerm`、知识卡与 `personAliasHints` 作为原料，在 `TranslationTaskInputAssembler` 内引入独立装配器生成两张“执行视图表”，并挂到 `ExecutionContextView`。D prompt、validator、detector 与审校输出只消费这两张表，不允许 D 回写 alias；`ProjectMemorySnapshot` 仍只保留稳定 confirmed 与候选增量，不承载本轮临时全局命名状态。

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Maven

---

## File Map

### 新增对象

- Create: `src/main/java/io/quillloom/domain/memory/DraftStageGlobalGlossary.java`
- Create: `src/main/java/io/quillloom/domain/memory/GlossaryEntry.java`
- Create: `src/main/java/io/quillloom/domain/memory/GlossaryEntryStrength.java`
- Create: `src/main/java/io/quillloom/domain/memory/GlossaryEntrySourceKind.java`
- Create: `src/main/java/io/quillloom/domain/memory/GlobalAliasConsistencyTable.java`
- Create: `src/main/java/io/quillloom/domain/memory/AliasCluster.java`
- Create: `src/main/java/io/quillloom/domain/memory/AliasClusterState.java`
- Create: `src/main/java/io/quillloom/application/translation/assembler/GlobalNamingStageAssembler.java`

### 现有装配与翻译入口

- Modify: `src/main/java/io/quillloom/domain/memory/ExecutionContextView.java`
- Modify: `src/main/java/io/quillloom/application/translation/assembler/TranslationTaskInputAssembler.java`
- Modify: `src/main/java/io/quillloom/application/translation/service/TranslationApplicationService.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/TranslationPromptRenderer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/ChunkTranslationResultValidator.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/GlossaryComplianceIssueDetector.java`
- Modify: `src/main/java/io/quillloom/application/translation/assembler/DraftCompilationAssembler.java`

### 文档

- Modify: `docs/handoff.md`

### 测试

- Create: `src/test/java/io/quillloom/application/translation/assembler/GlobalNamingStageAssemblerTest.java`
- Modify: `src/test/java/io/quillloom/application/translation/assembler/TranslationTaskInputAssemblerTest.java`
- Modify: `src/test/java/io/quillloom/application/translation/service/TranslationApplicationServiceTest.java`
- Modify: `src/test/java/io/quillloom/infrastructure/translation/TranslationPromptRendererTest.java`
- Modify: `src/test/java/io/quillloom/infrastructure/translation/GlossaryComplianceIssueDetectorTest.java`

---

### Task 1: 定义两张表的领域对象

**Files:**
- Create: `src/main/java/io/quillloom/domain/memory/DraftStageGlobalGlossary.java`
- Create: `src/main/java/io/quillloom/domain/memory/GlossaryEntry.java`
- Create: `src/main/java/io/quillloom/domain/memory/GlossaryEntryStrength.java`
- Create: `src/main/java/io/quillloom/domain/memory/GlossaryEntrySourceKind.java`
- Create: `src/main/java/io/quillloom/domain/memory/GlobalAliasConsistencyTable.java`
- Create: `src/main/java/io/quillloom/domain/memory/AliasCluster.java`
- Create: `src/main/java/io/quillloom/domain/memory/AliasClusterState.java`
- Test: `src/test/java/io/quillloom/application/translation/assembler/GlobalNamingStageAssemblerTest.java`

- [ ] 先写失败测试，定义两张表最小字段与不可变边界
- [ ] 运行 `mvn -q "-Dtest=GlobalNamingStageAssemblerTest" test`，确认因对象不存在而失败
- [ ] 实现最小 record/enum，字段覆盖：
  - `DraftStageGlobalGlossary.hardEntries / softEntries / coverageSummary`
  - `GlossaryEntry.sourceTerm / targetTerm / entryStrength / sourceKind / evidenceRefs / notes`
  - `GlobalAliasConsistencyTable.clusters / unresolvedClusters / coverageSummary`
  - `AliasCluster.clusterId / surfaceForms / canonicalSourceNameOptional / aliasState / confidence / evidenceRefs / recommendedRenderingFamily`
- [ ] 再跑 `mvn -q "-Dtest=GlobalNamingStageAssemblerTest" test`，确认进入下一类失败

### Task 2: 实现 D 前全局命名阶段产表

**Files:**
- Create: `src/main/java/io/quillloom/application/translation/assembler/GlobalNamingStageAssembler.java`
- Test: `src/test/java/io/quillloom/application/translation/assembler/GlobalNamingStageAssemblerTest.java`

- [ ] 写失败测试，覆盖：
  - `confirmedTerms` 进入 `hardEntries`
  - `candidateTermUpdates`、`CandidateTerm`、知识卡证据进入 `softEntries`
  - `personAliasHints` 与知识卡 alias 线索整理成 alias clusters
  - 不把 `KnowledgeCard.title / anchorNames` 直接当稳定译名
  - 不把单条 `personAliasHints` 直接升级成稳定 alias 事实
- [ ] 运行 `mvn -q "-Dtest=GlobalNamingStageAssemblerTest" test`，确认按预期失败
- [ ] 实现最小装配器：
  - glossary 的 `hardEntries` 仅取现有 confirmed
  - glossary 的 `softEntries` 从候选更新、候选术语、已选知识卡证据和 alias 辅助结果整理
  - alias 表只做聚类与证据整理，不产出稳定译名事实
- [ ] 再跑 `mvn -q "-Dtest=GlobalNamingStageAssemblerTest" test`，确认通过

### Task 3: 接入 D 输入装配

**Files:**
- Modify: `src/main/java/io/quillloom/domain/memory/ExecutionContextView.java`
- Modify: `src/main/java/io/quillloom/application/translation/assembler/TranslationTaskInputAssembler.java`
- Modify: `src/test/java/io/quillloom/application/translation/assembler/TranslationTaskInputAssemblerTest.java`

- [ ] 写失败测试，验证 `ExecutionContextView` 已携带 `DraftStageGlobalGlossary` 与 `GlobalAliasConsistencyTable`
- [ ] 写失败测试，验证 D 输入中的 `confirmedTerms` 仍保留稳定层语义，但全局命名执行优先看两张表
- [ ] 运行 `mvn -q "-Dtest=TranslationTaskInputAssemblerTest" test`，确认失败
- [ ] 最小改动 `ExecutionContextView` 和 `TranslationTaskInputAssembler`：
  - 在不扩张 `TranslationTaskInput` 的前提下，把两张表挂进 `ExecutionContextView`
  - 由 `TranslationTaskInputAssembler` 在选卡后调用 `GlobalNamingStageAssembler`
  - trace 中输出两张表快照，便于后续定位
- [ ] 再跑 `mvn -q "-Dtest=TranslationTaskInputAssemblerTest" test`，确认通过

### Task 4: 调整 D 规则，只允许表外增量

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/translation/TranslationPromptRenderer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/ChunkTranslationResultValidator.java`
- Modify: `src/test/java/io/quillloom/infrastructure/translation/TranslationPromptRendererTest.java`
- Modify: `src/test/java/io/quillloom/application/translation/service/TranslationApplicationServiceTest.java`

- [ ] 先写失败测试，覆盖：
  - prompt 明确“先 hard，再 soft，再 alias 表，最后才处理表外项”
  - `confirmedTermUpdates / candidateUpdates` 只用于表外项补充
  - alias 不允许由 D 回写
- [ ] 运行 `mvn -q "-Dtest=TranslationPromptRendererTest,TranslationApplicationServiceTest" test`，确认失败
- [ ] 最小实现：
  - prompt 用中文明确两张表优先级与 alias 只读边界
  - validator 继续拦截覆盖 confirmed 的行为
  - validator/服务层拒绝任何 alias 写回通道
- [ ] 再跑 `mvn -q "-Dtest=TranslationPromptRendererTest,TranslationApplicationServiceTest" test`，确认通过

### Task 5: 补 detector 与审校输出包

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/translation/GlossaryComplianceIssueDetector.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/ChunkTranslationResultValidator.java`
- Modify: `src/main/java/io/quillloom/application/translation/assembler/DraftCompilationAssembler.java`
- Modify: `src/test/java/io/quillloom/infrastructure/translation/GlossaryComplianceIssueDetectorTest.java`

- [ ] 先写失败测试，覆盖：
  - `name-residue-warning`
  - `glossary-entry-not-applied`
  - 审校输出能拿到 glossary/alias 快照或等价结构化包
- [ ] 运行 `mvn -q "-Dtest=GlossaryComplianceIssueDetectorTest" test`，确认失败
- [ ] 最小实现 detector 与 review package 输出，不引入新 orchestrator
- [ ] 再跑 `mvn -q "-Dtest=GlossaryComplianceIssueDetectorTest" test`，确认通过

### Task 6: 同步交接文档并做定向验证

**Files:**
- Modify: `docs/handoff.md`

- [ ] 在 `docs/handoff.md` 记录“两张表已接入、D 只消费 alias 表、confirmed/candidate 仅表外增量”的现状
- [ ] 运行定向测试：
  - `mvn -q "-Dtest=GlobalNamingStageAssemblerTest,TranslationTaskInputAssemblerTest,TranslationApplicationServiceTest,TranslationPromptRendererTest,GlossaryComplianceIssueDetectorTest" test`
- [ ] 如 translation 链路受影响，再补跑：
  - `mvn -q "-Dtest=ChunkTranslationResultValidatorTest,C0ToDIntegrationSmokeTest" test`
- [ ] 只有在看到新鲜测试结果后，才汇报完成状态
