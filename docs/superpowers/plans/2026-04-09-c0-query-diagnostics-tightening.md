# C0 Query And Diagnostics Tightening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 收紧 C0 planner 生成的搜索词格式，并让 organizer 拒绝建卡时输出足够可诊断的信息。

**Architecture:** 只修改 C0 内部三处边界。`KnowledgeNeedPlanningPromptRenderer` 负责把 planner 明确约束成短搜索词；`KnowledgeNeedPlanningResultParser` 负责在 LLM 输出越界时做本地格式收紧；`LlmKnowledgeSearchResultOrganizer` 负责把 query、命中数和 rejection reason 拼进异常信息。测试只覆盖这两项行为，不扩散到 A/B/D。

**Tech Stack:** Java 21, Spring Boot, Jackson, JUnit 5, Maven

---

### Task 1: 锁定 planner 搜索词约束

**Files:**
- Modify: `src/test/java/io/quillloom/infrastructure/preprocess/LlmKnowledgeNeedPlannerTest.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeNeedPlanningPromptRenderer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeNeedPlanningResultParser.java`

- [ ] 为 planner 增加失败测试，断言会把分析句收紧为短搜索词。
- [ ] 更新 planner prompt，明确禁止输出完整分析句、问句、解释句。
- [ ] 在 parser 中增加本地 queryText 规范化，确保最终 query 是短搜索词。
- [ ] 跑 `mvn -q "-Dtest=LlmKnowledgeNeedPlannerTest" test` 验证通过。

### Task 2: 锁定 organizer 拒绝诊断

**Files:**
- Create: `src/test/java/io/quillloom/infrastructure/preprocess/LlmKnowledgeSearchResultOrganizerTest.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/LlmKnowledgeSearchResultOrganizer.java`

- [ ] 增加失败测试，断言 organizer 拒绝时异常信息包含 query、过滤后命中数、原始命中数、rejectionReason。
- [ ] 只在 organizer 异常信息里补全诊断字段，不改现有接口和回退策略。
- [ ] 跑 `mvn -q "-Dtest=LlmKnowledgeSearchResultOrganizerTest" test` 验证通过。

### Task 3: 汇总验证

**Files:**
- 无新增实现文件

- [ ] 跑 `mvn -q "-Dtest=LlmKnowledgeNeedPlannerTest,LlmKnowledgeSearchResultOrganizerTest" test`。
- [ ] 若通过，再汇报实际命令和结果，不宣称未验证的结论。
