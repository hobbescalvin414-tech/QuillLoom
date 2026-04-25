# C0 Rejection Is Business Branch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 C0 中 `shouldCreateCard=false` 的 organizer 拒绝从异常改成正常业务分支，让 workflow 跳过建卡继续运行。

**Architecture:** 在 C0 内新增显式 decision/outcome 对象。`KnowledgeSearchResultOrganizer` 返回组织决策而不是裸 evidence；`NetworkBackedKnowledgeSearchTool` 返回每个 need 的搜索结果或拒绝结果；`ToolDrivenKnowledgeEnricher` 对拒绝结果只记 trace、不抛异常、不建卡。只有真正的技术失败仍然抛异常。

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Maven

---

### Task 1: 显式建模 organizer 决策

**Files:**
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeSearchOrganizationDecision.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeSearchResultOrganizer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/LlmKnowledgeSearchResultOrganizer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeSearchResultCondenser.java`

- [ ] 新增 organizer decision，区分 accepted / rejectionKind / rejectionReason / evidence。
- [ ] LLM organizer 在 `shouldCreateCard=false` 时返回拒绝 decision，不再抛异常。
- [ ] rule-based condenser 保持 accepted decision 输出。

### Task 2: 让搜索链路返回 outcome 而不是直接 evidence

**Files:**
- Create: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeSearchOutcome.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeSearchTool.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/NetworkBackedKnowledgeSearchTool.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/HeuristicKnowledgeSearchTool.java`

- [ ] 新增 search outcome，记录 need、rawHits、accepted、rejectionKind、rejectionReason、evidence。
- [ ] `NetworkBackedKnowledgeSearchTool` 对每个 need 返回一条 outcome。
- [ ] 技术失败继续抛异常，不做隐藏 fallback。

### Task 3: enrich 只跳过拒绝并记 trace

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/ToolDrivenKnowledgeEnricher.java`
- Modify: `src/main/java/io/quillloom/application/workflow/progress/WorkflowConsoleProgressReporter.java`

- [ ] enrich 遍历 outcome：accepted 才建卡，rejected 只记 trace 并继续。
- [ ] 新增 `knowledge_card_rejected` trace 事件，带 query、hit 数、rejectionKind、rejectionReason。
- [ ] 控制台输出新增一行简洁 rejected 提示，避免用户只能看到顶层失败。

### Task 4: 补测试并验证

**Files:**
- Modify: `src/test/java/io/quillloom/infrastructure/preprocess/LlmKnowledgeSearchResultOrganizerTest.java`
- Modify: `src/test/java/io/quillloom/infrastructure/preprocess/NetworkBackedKnowledgeSearchToolTest.java`
- Modify: `src/test/java/io/quillloom/infrastructure/preprocess/ToolDrivenKnowledgeEnricherTraceTest.java`

- [ ] 增加 organizer reject 返回 decision 的测试。
- [ ] 增加 search tool 遇到 reject 不抛异常、而是返回 rejected outcome 的测试。
- [ ] 增加 enricher 会记录 `knowledge_card_rejected` 且仍继续 candidate term 的测试。
- [ ] 跑 `mvn -q "-Dtest=LlmKnowledgeSearchResultOrganizerTest,NetworkBackedKnowledgeSearchToolTest,ToolDrivenKnowledgeEnricherTraceTest" test`。
