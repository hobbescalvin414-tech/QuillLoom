# Review Agent Consistency And Bridging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 post-draft review agent 显式审查 chunk 衔接、局部逻辑一致性与专名一致性，并为 review agent 增加“按原名读取已确认译名 / 记录新确认译名”的窄工具。

**Architecture:** 复用现有 `PostDraftReviewPackage.termState.effectiveConfirmedTerms` 作为项目级稳定译名资产，不新建独立表。review agent 新增一个只读查询工具 `read_confirmed_terms` 和一个显式写回工具 `record_confirmed_terms`，同时收紧 evaluation / investigation prompt，把 `KEEP` 从“快速放行”改成“经过衔接、逻辑、专名检查后才可放行”。

**Tech Stack:** Java, Spring, Jackson, Maven, JUnit 5

---

### Task 1: 补充计划内的失败测试

**Files:**
- Modify: `src/test/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReaderTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

- [ ] 为 `read_confirmed_terms` 写失败测试，要求只按 `sourceTerms` 过滤返回，不允许把全量 confirmed terms 全塞回去。
- [ ] 为 `record_confirmed_terms` 写失败测试，要求冲突写回被显式拒绝。
- [ ] 为 investigation / evaluation prompt 写失败测试，要求出现“衔接、逻辑、自相矛盾、专名一致性、已确认译名优先沿用”这些规则。
- [ ] 运行定向测试，确认当前实现尚未满足新要求。

Run: `mvn -q "-Dtest=RepositoryBackedPostDraftReviewAgentReaderTest,ReviewToolExecutorGuardrailTest,PromptBackedNextStepDecisionProviderTest,ReviewPromptBuilderTest" test`

### Task 2: 加入已确认译名读取与写回端口

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentReader.java`
- Create: `src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentTermWriter.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java`
- Create: `src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentTermWriter.java`
- Modify: `src/test/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReaderTest.java`

- [ ] 给 reader 增加按 `sourceTerms` 读取 confirmed terms 的查询方法。
- [ ] 新建 term writer，只负责把新 confirmed terms 写回 `PostDraftReviewPackage`。
- [ ] 写回逻辑只允许追加或同值确认；遇到不同值冲突必须抛显式错误。
- [ ] 写回后同步刷新 `termState` 与 `glossarySnapshot`，不碰 alias snapshot。
- [ ] 跑 reader / writer 定向测试。

Run: `mvn -q "-Dtest=RepositoryBackedPostDraftReviewAgentReaderTest,PostgresPostDraftReviewPackageRepositoryTest,InMemoryPostDraftReviewPackageRepositoryTest" test`

### Task 3: 将新工具接入 review agent

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`
- Modify: `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java`

- [ ] 注册 `read_confirmed_terms` 和 `record_confirmed_terms` 两个工具。
- [ ] 让 schema / contract validator 支持：
  - `read_confirmed_terms.sourceTerms`
  - `record_confirmed_terms.entries`
- [ ] 在 executor 中实现两种工具：
  - 查询 confirmed terms 并写入 evidence
  - 写回 confirmed terms 并写入 transcript/history/tool trace
- [ ] 让 `PostDraftReviewAgentService` 给 executor 注入新的 term writer。
- [ ] 跑 executor / client / service 定向测试。

Run: `mvn -q "-Dtest=OpenAiCompatibleReviewAgentStructuredGenerationClientTest,ReviewToolExecutorGuardrailTest,PostDraftReviewAgentServiceTest" test`

### Task 4: 强化 evaluation / investigation prompt

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/EvaluationPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`

- [ ] 在 evaluation prompt 中明确：
  - `KEEP` 不等于跳过衔接、逻辑、自相矛盾、专名检查
  - 对短句、承接句、回应句、转场句优先检查上下文
  - 专名残留未译或与已确认译名不一致时，不应轻易 `KEEP`
  - 局部衔接与专名统一优先走 `LIGHT_EDIT`
- [ ] 在 investigation prompt 中明确：
  - 需要时优先 `read_previous_chunks/read_next_chunks`
  - 涉及专名时优先 `read_confirmed_terms`
  - 没有 confirmed term 但证据足够时，可以 `record_confirmed_terms`
  - 未做必要衔接/一致性检查前，不应轻易 `complete_working_set`
- [ ] 跑 prompt / provider 定向测试。

Run: `mvn -q "-Dtest=ReviewPromptBuilderTest,PromptBackedNextStepDecisionProviderTest" test`

### Task 5: 回归验证与文档同步

**Files:**
- Modify: `docs/handoff-2026-04-17-review-agent-refactor.md`

- [ ] 跑 review agent 核心回归测试。
- [ ] 更新补充 handoff，写明 confirmed term 工具与 prompt 行为变化。
- [ ] 留待用户重跑真实 smoke 验证：
  - 是否开始主动检查衔接
  - 是否开始读取 confirmed terms
  - 是否在必要时写回新 confirmed terms

Run: `mvn -q "-Dtest=OpenAiCompatibleReviewAgentStructuredGenerationClientTest,PromptBackedNextStepDecisionProviderTest,ReviewPromptBuilderTest,ReviewToolExecutorGuardrailTest,AutonomousProjectReviewAgentTest,PostDraftReviewAgentServiceTest" test`
