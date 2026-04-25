# Review Agent 127 Chunk Smoke 前置改动设计

**日期**：2026-04-18  
**目标**：在不破坏方向锚定文档红线的前提下，为第一次真实 127 chunk 项目冒烟测试补齐前置改动，重点解决 D 初稿保护、reset 能力、可观察性、CLI 运行入口和 wall clock 超时保护。

---

## 1. 约束与结论

### 1.1 红线约束

本设计受 [2026-04-18-review-agent-direction-anchor.md](E:/projects/QuillLoom/docs/superpowers/plans/2026-04-18-review-agent-direction-anchor.md) 约束，尤其保持：

1. `NO_PROGRESS` 保持 `FAILED`，不转 HITL，不生成可恢复 session。
2. 不新增工具，不往 `ReviewToolExecutor` 增加新的 switch case。
3. 人工回答只进入 transcript / history，不进入 `TranslationTaskInput`。
4. 不做 D-12 checkpoint 恢复。
5. 不做 D-13 流式接口改造。

### 1.2 本轮设计结论

1. **P0 是真实 127 chunk 冒烟的硬前置。**
   - 当前 Review Agent 写回会覆盖 `translatedText`，导致 D 初稿不可逆丢失。
   - 在第一次真实长跑前，必须先把“D 初稿”和“Review 修订稿”分离。
2. **P1/P2/P3 都应做，但它们属于运行可见性和可控性增强，不改变最核心的数据安全边界。**
3. **`revisedTranslatedText` 应作为 `PostDraftChunkRecord` 的领域字段，而不是运行期临时状态。**
   - 它是稳定产物，符合 R-06。
   - 它不应回写到 `TranslationTaskInput` 或其他执行输入契约。
4. **本轮不建议为了这一项改动引入 Flyway。**
   - 技术事实是：`revisedTranslatedText` 位于 `chunks_json` 内，不需要 `ql_post_draft_review_package` 顶层加列。
   - 当前 schema 由 `PostgresKnowledgeBaseSchemaInitializer` 初始化，而不是 Flyway 管理。
   - 如果后续要全面引入 Flyway，应作为独立 schema 治理任务，不应混入本轮 127 chunk smoke 前置改动。

---

## 2. 推荐实施顺序

1. **P0：D 初稿保护 + reset 能力**
2. **P1：控制台可视化 + FAILED 落盘 + 重试日志**
3. **P2：CLI start / resume / reset**
4. **P3：wall clock 超时保护**
5. **真实 smoke 分层执行**
   - 先 1 chunk
   - 再 5-10 chunk
   - 最后 127 chunk

原因：

1. 没有 P0，长跑结果不可回退，测试基线会被污染。
2. 没有 P1/P2，长跑虽可执行，但不可诊断、不可重置、不可恢复。
3. 没有 P3，127 chunk 长跑一旦卡住，缺少统一失败语义。

---

## 3. P0：D 初稿保护 + reset 能力

## 3.1 设计目标

1. `translatedText` 永远表示 D 初稿，不被任何后续流程覆盖。
2. Review Agent 修订结果写入新字段 `revisedTranslatedText`。
3. `mergedDraftText` 由“`revisedTranslatedText` 优先，`translatedText` 回退”拼接。
4. Review Agent 在读取 chunk 译文时，也遵循相同 fallback 规则。
5. 支持 `resetProjectRevisions(projectId)`，把项目恢复到“只有 D 初稿”的状态。

## 3.2 核心设计

### 3.2.1 领域对象改动

修改文件：

- [PostDraftChunkRecord.java](E:/projects/QuillLoom/src/main/java/io/quillloom/domain/postdraft/PostDraftChunkRecord.java)

改动：

1. 新增字段 `String revisedTranslatedText`
2. 推荐放在 `translatedText` 后面，形成语义配对：
   - `translatedText`：D 初稿
   - `revisedTranslatedText`：Review 修订稿，可为 `null`
3. 在 record 上直接新增实例方法 `effectiveTranslatedText()`
   - 语义：`revisedTranslatedText != null && !revisedTranslatedText.isBlank()` 时优先返回修订稿
   - 否则回退到 `translatedText`

为什么改：

1. 这是稳定领域事实，不是运行期临时状态。
2. reset、diff、冒烟回退、人工复核都需要显式区分这两层译文。
3. `effectiveTranslatedText()` 作为领域对象实例方法，比额外 helper 更内聚，也避免在 review 层再造一套“伪领域文本解析器”。
4. 不改变 `translatedText` 语义，符合你给定的硬约束。

### 3.2.2 Writer 写回规则

修改文件：

- [PostDraftReviewAgentWriter.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentWriter.java)
- [PostgresPostDraftReviewAgentWriter.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriter.java)

改动：

1. `writeCompletedChunks(projectId, outcomes)` 只更新对应 chunk 的 `revisedTranslatedText`
2. 不再覆盖 `translatedText`
3. 新增 `resetProjectRevisions(String projectId)`
4. 新增一个 writer 侧的全量合并稿写回能力，例如：
   - `writeMergedDraftFromProjectChunks(String projectId)`
   - 或等价命名的方法，由 writer 自己读取当前 package 并按 fallback 规则组装后写回

`resetProjectRevisions(String projectId)` 语义：

1. 所有 chunk 的 `revisedTranslatedText = null`
2. `mergedDraftText = ""`
3. 不动 `translatedText`
4. 不清理 `confirmedTermUpdates`

为什么改：

1. Writer 是稳定产物写出边界，reset 也属于稳定产物回退，应该留在 writer port。
2. session 文件清理不是 writer 职责，仍由 service / runner 复用 `ReviewSessionStore.delete(projectId)` 完成。
3. `confirmedTermUpdates` 属于稳定术语产物，不是本轮 Review 修订层的可逆覆盖物；reset 的目标是回到 D 初稿译文基线，而不是抹除术语事实。
4. merged draft 的全量组装如果放在 writer 侧，可以避免让 persistence hook 再额外依赖 reader / assembler，保持 hook 继续只关心“何时触发副作用”，不扩散职责。

### 3.2.3 merged draft 组装规则

修改文件：

- [DefaultProjectReviewRuntimePersistenceHook.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/DefaultProjectReviewRuntimePersistenceHook.java)
- [PostDraftReviewAgentWriter.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentWriter.java)
- [PostgresPostDraftReviewAgentWriter.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriter.java)

新规则：

项目完成时，按 chunk 顺序拼接全文：

1. 若 `revisedTranslatedText` 非空，取它
2. 否则 fallback 到 `translatedText`

为什么不能继续用当前实现：

1. 当前 `assembleMergedDraft(...)` 只从 `completedChunkOutcomes.finalTranslation` 拼接。
2. 它无法覆盖“部分 chunk 未被 agent 实质修订，但仍要参与最终合并稿”的场景。
3. 127 chunk 真实冒烟时，必须允许“局部修订 + 全量合并”。
4. 本轮不建议把 `PostDraftMergedDraftAssembler` 作为新依赖注入到 hook。更稳妥的方式是扩展 writer，让 writer 在自己的 repository 上下文里完成“读取当前 package -> 按 fallback 规则组装 -> 写回 mergedDraftText”，这样 hook 仍只依赖 `PostDraftReviewAgentWriter` 和 `ReviewSessionStore`。

### 3.2.4 Reader 读取规则

修改文件：

- [RepositoryBackedPostDraftReviewAgentReader.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java)
- [ReviewChunkSnapshotFormatter.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/ReviewChunkSnapshotFormatter.java)
- [RevisionSelfCheckPromptBuilder.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java)
- [PostDraftReviewStrategyResolver.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewStrategyResolver.java)
- [WorkingSetCompletionHandler.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/WorkingSetCompletionHandler.java)

推荐方案：

1. 不新增 `PostDraftChunkTextResolver` 一类 helper。
2. 直接在 [PostDraftChunkRecord.java](E:/projects/QuillLoom/src/main/java/io/quillloom/domain/postdraft/PostDraftChunkRecord.java) 上增加 `effectiveTranslatedText()`，所有 Review Agent 相关读取点统一走这个实例方法。
3. 不在 repository 层“偷偷改写 chunk.translatedText”，避免隐藏语义。

为什么推荐 record 实例方法，而不是 reader 里改写 `translatedText` 或额外建 helper：

1. 更透明，避免把“有效视图”伪装成领域真值。
2. 比独立 helper 更内聚，调用方直接 `chunk.effectiveTranslatedText()`，不会再在 review 层分散一堆静态工具入口。
3. 便于在代码审查时区分：
   - 谁在看 D 初稿
   - 谁在看 Review 当前有效译文
4. 更符合“不要兜底掩盖问题”。

---

## 3.3 P0 对下游的影响分析

这是本轮改动面最大的部分。`PostDraftChunkRecord` 是领域 record，加字段会影响所有构造点和所有直接依赖其字段语义的消费者。

### 3.3.1 必须适配的构造点

这些文件直接 `new PostDraftChunkRecord(...)`，record 字段增加后会全部受影响：

1. [PostDraftReviewPackageAssembler.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/assembler/PostDraftReviewPackageAssembler.java)
   - D 初稿进入 review package 的主装配点
   - 这里必须明确：
     - `translatedText = draft.translatedText()`
     - `revisedTranslatedText = null`
2. [PostgresPostDraftReviewAgentWriter.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriter.java)
   - 复制 chunk 时必须保留 D 初稿，并只写修订稿
3. [RepositoryBackedPostDraftReviewAgentTermWriter.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentTermWriter.java)
   - 复制 package 时必须原样保留 `revisedTranslatedText`

测试构造点也必须全部适配：

4. [PostDraftReviewPackageContractTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/domain/postdraft/PostDraftReviewPackageContractTest.java)
5. [PostDraftContinuationAssemblyTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/PostDraftContinuationAssemblyTest.java)
6. [PostDraftReviewSessionFactoryTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewSessionFactoryTest.java)
7. [PostDraftReviewAgentServiceTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java)
8. [PostDraftReviewAgentEndToEndSmokeTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentEndToEndSmokeTest.java)
9. [AutonomousProjectReviewAgentTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java)
10. [PostDraftReviewProcessSummaryAssemblerTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewProcessSummaryAssemblerTest.java)
11. [PostDraftRetranslationServiceTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftRetranslationServiceTest.java)
12. [WorkingSetCompletionHandlerTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/WorkingSetCompletionHandlerTest.java)
13. [ReviewAgentFixtures.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/ReviewAgentFixtures.java)
14. [ReviewToolExecutorGuardrailTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java)
15. [PostDraftReviewStrategyResolverTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewStrategyResolverTest.java)
16. [PostDraftRevisionServiceTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftRevisionServiceTest.java)
17. [RepositoryBackedPostDraftReviewAgentReaderTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReaderTest.java)
18. [RepositoryBackedPostDraftReviewAgentTermWriterTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentTermWriterTest.java)
19. [PostgresPostDraftReviewAgentWriterTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriterTest.java)
20. [PostgresPostDraftReviewPackageRepositoryTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/infrastructure/postdraft/PostgresPostDraftReviewPackageRepositoryTest.java)

### 3.3.2 必须适配的 Review Agent 读取点

这部分不再做泛泛而谈的“可能会受影响”罗列，而是按当前代码中 review 链路的直接调用点做审计。凡是需要让 agent 看见“当前有效译文”的地方，都应改为 `chunk.effectiveTranslatedText()`；必须保留 D 初稿语义的调用点则保持不动。

**必须改为 effective text 的：**

1. [WorkingSetCompletionHandler.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/WorkingSetCompletionHandler.java)
   - fallback 最终译文时必须优先看修订稿
2. [ReviewChunkSnapshotFormatter.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/ReviewChunkSnapshotFormatter.java)
   - prompt 中给 agent 看的 chunk 摘要必须反映当前有效译文
3. [RevisionSelfCheckPromptBuilder.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java)
   - self-check 的 `currentTranslatedText` 必须走 fallback
4. [PostDraftReviewStrategyResolver.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewStrategyResolver.java)
   - 字数/空值判断要基于当前有效译文
5. [RepositoryBackedPostDraftReviewAgentReader.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java)
   - `searchChunksByKeyword(...)` 中对译文的关键词匹配要走 effective text

**测试断言与测试辅助中需要同步改语义的：**

6. [PostDraftReviewAgentEndToEndSmokeTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentEndToEndSmokeTest.java)
   - 现有断言直接看 `translatedText()`，P0 后应改为同时断言：
     - `translatedText()` 仍是 D 初稿
     - `revisedTranslatedText()` 才是 review 结果
7. [PostgresPostDraftReviewAgentWriterTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriterTest.java)
   - 从“断言覆盖 translatedText”改为“断言只写 revisedTranslatedText”
8. [ReviewToolExecutorGuardrailTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java)
   - 若测试描述的是 review 运行时看到的译文长度，应改为基于 `effectiveTranslatedText()`

**建议继续保留“只看 D 初稿”的：**

这些不是 review 模块主链，不应被顺手改成修订稿语义：

1. [TranslationTaskInputAssembler.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/translation/assembler/TranslationTaskInputAssembler.java)
2. [DraftCompilationAssembler.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/translation/assembler/DraftCompilationAssembler.java)
3. 所有仍然服务于 D 层初稿语义的调试、导出、workflow trace 代码

### 3.3.3 JSON 序列化与 repository 影响

受影响文件：

1. [PostgresPostDraftReviewPackageRepository.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/PostgresPostDraftReviewPackageRepository.java)
2. [InMemoryPostDraftReviewPackageRepository.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/InMemoryPostDraftReviewPackageRepository.java)
3. [PostDraftReviewPackage.java](E:/projects/QuillLoom/src/main/java/io/quillloom/domain/postdraft/PostDraftReviewPackage.java)

影响结论：

1. `PostDraftReviewPackage` 顶层结构不需要新增字段。
2. `chunks_json` 中的 chunk JSON 会自然扩展出 `revisedTranslatedText`。
3. 旧 JSON 中没有该字段时，Jackson 应反序列化为 `null`，需要 contract test 明确锁定。

### 3.3.4 数据库 schema / migration 结论

受影响文件：

- [PostgresKnowledgeBaseSchemaInitializer.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/preprocess/PostgresKnowledgeBaseSchemaInitializer.java)

结论：

1. 这次改动**不需要**给 `ql_post_draft_review_package` 顶层表加列，因为 `revisedTranslatedText` 在 `chunks_json` 内。
2. 当前系统并没有 Flyway 迁移链，schema 由 initializer 创建。
3. 因此，本轮不建议为了这一项变化临时引入 Flyway。
4. 若你坚持把“schema 演进”统一纳入 Flyway，应作为独立任务整体替换当前 initializer 机制，而不是在 P0 里只为这个 JSON 字段做半套迁移。

---

## 3.4 P0 需要修改的文件清单

### 核心领域与写回链

- Modify: [PostDraftChunkRecord.java](E:/projects/QuillLoom/src/main/java/io/quillloom/domain/postdraft/PostDraftChunkRecord.java)
- Modify: [PostDraftReviewPackageAssembler.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/assembler/PostDraftReviewPackageAssembler.java)
- Modify: [PostDraftReviewAgentWriter.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/port/out/PostDraftReviewAgentWriter.java)
- Modify: [PostgresPostDraftReviewAgentWriter.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriter.java)
- Modify: [DefaultProjectReviewRuntimePersistenceHook.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/DefaultProjectReviewRuntimePersistenceHook.java)

### Reader / prompt / completion 侧

- Modify: [RepositoryBackedPostDraftReviewAgentReader.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java)
- Modify: [ReviewChunkSnapshotFormatter.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/ReviewChunkSnapshotFormatter.java)
- Modify: [RevisionSelfCheckPromptBuilder.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java)
- Modify: [PostDraftReviewStrategyResolver.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewStrategyResolver.java)
- Modify: [WorkingSetCompletionHandler.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/WorkingSetCompletionHandler.java)

### reset 入口

- Modify: [PostDraftReviewAgentService.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java)
- Modify: [PostDraftReviewAgentCommandLineRunner.java](E:/projects/QuillLoom/src/main/java/io/quillloom/interfaces/runner/PostDraftReviewAgentCommandLineRunner.java)
- Modify: [ReviewAgentRuntimeProperties.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/ReviewAgentRuntimeProperties.java)

### 测试

- Modify: [PostDraftReviewPackageContractTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/domain/postdraft/PostDraftReviewPackageContractTest.java)
- Modify: [PostDraftContinuationAssemblyTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/PostDraftContinuationAssemblyTest.java)
- Modify: [AutonomousProjectReviewAgentTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java)
- Modify: [PostDraftReviewSessionFactoryTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewSessionFactoryTest.java)
- Modify: [PostDraftReviewProcessSummaryAssemblerTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewProcessSummaryAssemblerTest.java)
- Modify: [PostDraftReviewAgentServiceTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentServiceTest.java)
- Modify: [PostDraftReviewAgentEndToEndSmokeTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewAgentEndToEndSmokeTest.java)
- Modify: [PostDraftRetranslationServiceTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftRetranslationServiceTest.java)
- Modify: [WorkingSetCompletionHandlerTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/WorkingSetCompletionHandlerTest.java)
- Modify: [ReviewAgentFixtures.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/ReviewAgentFixtures.java)
- Modify: [ReviewToolExecutorGuardrailTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java)
- Modify: [PostDraftReviewStrategyResolverTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftReviewStrategyResolverTest.java)
- Modify: [PostDraftRevisionServiceTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/application/postdraft/review/PostDraftRevisionServiceTest.java)
- Modify: [RepositoryBackedPostDraftReviewAgentReaderTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReaderTest.java)
- Modify: [RepositoryBackedPostDraftReviewAgentTermWriterTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentTermWriterTest.java)
- Modify: [PostgresPostDraftReviewAgentWriterTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriterTest.java)
- Modify: [PostgresPostDraftReviewPackageRepositoryTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/infrastructure/postdraft/PostgresPostDraftReviewPackageRepositoryTest.java)
- Modify: [PostDraftReviewAgentCommandLineRunnerTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/interfaces/runner/PostDraftReviewAgentCommandLineRunnerTest.java)

---

## 3.5 P0 验证方案

1. **writer 写回测试**
   - `translatedText` 保持不变
   - `revisedTranslatedText` 被正确更新
2. **merged draft fallback 测试**
   - 部分 chunk 只有 D 初稿时，合并稿仍能完整产出
3. **reader fallback 测试**
   - 有修订稿时优先读修订稿
   - 无修订稿时读 D 初稿
4. **reset 测试**
   - 所有 chunk 的 `revisedTranslatedText` 被清空
   - `mergedDraftText` 被清空
   - session 文件被删除
5. **contract 测试**
   - 旧 `chunks_json` 无 `revisedTranslatedText` 时可兼容读取
   - 新 JSON round-trip 正确

---

## 4. P1：控制台可视化 + FAILED 落盘 + 重试日志

## 4.1 控制台可视化

修改文件：

- [PostDraftReviewAgentRuntimeConfiguration.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfiguration.java)

改动：

1. `ReviewRuntimeVisualizer` Bean 从 `ReviewRuntimeVisualizer.noop()` 改为 `new ConsoleReviewRuntimeVisualizer()`

为什么改：

1. 127 chunk 冒烟时必须知道 agent 跑到哪一个 focus、调用了什么工具、在哪里结束。
2. 这是现成能力，不引入新的观测体系。

验证：

1. 配置装配测试验证 bean 类型
2. 真实 CLI 跑时看到 `project_started / focus_selected / tool_called / tool_completed / project_finished`

## 4.2 FAILED 也落盘

修改文件：

- [DefaultProjectReviewRuntimePersistenceHook.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/DefaultProjectReviewRuntimePersistenceHook.java)

改动：

1. 当 `currentRuntime.status() == FAILED` 时，也调用 `reviewSessionStore.save(currentRuntime)`

边界说明：

1. 这不是 D-12 checkpoint 恢复。
2. FAILED session 只用于排查，不用于 resume。
3. `NO_PROGRESS` 仍然是 `FAILED`，不会转 `WAITING_HUMAN`。

验证：

1. 定向测试：FAILED 后 session 文件存在
2. `resumeProject(...)` 仍然只接受 `WAITING_HUMAN`

## 4.3 重试日志

修改文件：

- [RetryingReviewAgentStructuredGenerationPort.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/RetryingReviewAgentStructuredGenerationPort.java)

改动：

1. 每次重试 `warn`
2. 重试耗尽 `error`
3. 日志字段最小集合：
   - `attempt`
   - `backoff_ms`
   - `reason`
   - `operation`

为什么改：

1. 127 chunk 长跑里必须区分“agent 自主多轮决策”和“同一次 LLM 调用在重试”

验证：

1. 保持现有 retry 定向测试
2. 不对日志格式做脆弱断言

---

## 5. P2：CommandLineRunner 启动入口

当前 [PostDraftReviewAgentCommandLineRunner.java](E:/projects/QuillLoom/src/main/java/io/quillloom/interfaces/runner/PostDraftReviewAgentCommandLineRunner.java) 已存在，但目前只覆盖 `start / resume` 的最小路径；本节设计是**扩展现有 runner**，不是另起一套入口。

## 5.1 配置扩展

修改文件：

- [ReviewAgentRuntimeProperties.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/ReviewAgentRuntimeProperties.java)

新增字段：

1. 保留已有 `cliAction`
2. 保留已有 `cliProjectId`
3. 新增 `cliHumanReviewNote`
4. 新增 `maxWallClockMinutes`（供 P3 使用）

## 5.2 runner 行为

修改文件：

- [PostDraftReviewAgentCommandLineRunner.java](E:/projects/QuillLoom/src/main/java/io/quillloom/interfaces/runner/PostDraftReviewAgentCommandLineRunner.java)

支持三种 action：

1. `start`
   - `service.reviewProject(new StartProjectPostDraftReviewAgentCommand(projectId, ""))`
2. `resume`
   - `service.resumeProject(projectId, humanReviewNote)`
3. `reset`
   - 推荐通过 `service.resetProject(projectId)` 封装
   - service 内部调用：
     - `writer.resetProjectRevisions(projectId)`
     - `reviewSessionStore.delete(projectId)`

为什么推荐把 reset 封装进 service，而不是 runner 直连 writer + sessionStore：

1. reset 是应用用例，不是接口层编排细节。
2. 能保持 runner 只依赖 service。

## 5.3 参数优先级

命令行参数优先于 properties：

1. `cliProjectId`
2. `cliHumanReviewNote`

`resume` 时的 `humanReviewNote` 读取顺序：

1. 命令行 `--humanReviewNote=...`
2. `properties.cliHumanReviewNote`
3. 都没有则报错

## 5.4 验证

修改测试：

- [PostDraftReviewAgentCommandLineRunnerTest.java](E:/projects/QuillLoom/src/test/java/io/quillloom/interfaces/runner/PostDraftReviewAgentCommandLineRunnerTest.java)

覆盖：

1. `start`
2. `resume`
3. `reset`
4. 命令行参数优先级

---

## 6. P3：wall clock 超时保护

## 6.1 设计目标

在 127 chunk 长跑中，必须给 agent 一个统一的 wall clock 终止语义，避免：

1. 长时间卡住但没有明确 stop reason
2. runner/调用方只能看到“线程没返回”，却不知道是 agent 级失败

## 6.2 领域改动

修改文件：

- [ReviewProjectStopReason.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/model/ReviewProjectStopReason.java)
- [ProjectReviewRuntimeSession.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/model/ProjectReviewRuntimeSession.java)

新增：

- `WALL_CLOCK_TIMEOUT`

需要同步：

1. `FAILED` 状态对 stop reason 的合法性校验
2. `agentStopReason()` 映射

## 6.3 主循环改动

修改文件：

- [AutonomousProjectReviewAgent.java](E:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java)

改法：

1. `run(...)` 开头记录 `startedAt`
2. 每轮 loop 开头检查已运行时间
3. 超过 `maxWallClockMinutes` 后：
   - runtime 进入 `FAILED`
   - `stopReason = WALL_CLOCK_TIMEOUT`
   - transcript / history 追加 timeout 诊断
   - 返回失败 runtime

为什么不放到 CLI runner：

1. wall clock timeout 是 agent 级 stop reason，应在 runtime 层有统一语义。
2. 放在外层只会得到“线程中断”，不会有一致的领域状态。

## 6.4 配置来源

修改文件：

- [ReviewAgentRuntimeProperties.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/ReviewAgentRuntimeProperties.java)
- [PostDraftReviewAgentRuntimeConfiguration.java](E:/projects/QuillLoom/src/main/java/io/quillloom/infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfiguration.java)

配置项：

- `quillloom.postdraft.review.runtime.max-wall-clock-minutes`

默认值建议：

- `300`

## 6.5 验证

1. 单测配置极小 timeout，断言：
   - `status == FAILED`
   - `stopReason == WALL_CLOCK_TIMEOUT`
2. FAILED session 已在 P1 落盘
3. console visualizer 输出结束事件

---

## 7. 本轮不做的事

1. 不做 D-12 checkpoint 持久化
2. 不做 D-13 流式接口改造
3. 不做工具系统解耦
4. 不做异步 REST API
5. 不做完整 Flyway 迁移体系引入

---

## 8. 最终建议

若这份设计通过，实施时应分三段推进：

### 第一段：先把数据边界做干净

1. `revisedTranslatedText`
2. writer / reader / merged draft fallback
3. reset
4. P0 全套测试

### 第二段：把真实 smoke 的运行性补齐

1. console visualizer
2. FAILED 落盘
3. CLI `reset`
4. 重试日志

### 第三段：再上 127 chunk

1. wall clock timeout
2. 1 chunk smoke
3. 5-10 chunk smoke
4. 127 chunk smoke

这样可以保证：

1. 第一次真实长跑不会污染 D 初稿基线
2. 跑挂后能看到现场
3. 跑完后能 reset 回干净状态
4. 超时有统一 stop reason，而不是无声挂起

---

## 9. 实施状态（2026-04-18）

已落地：

1. `revisedTranslatedText` + `effectiveTranslatedText()`
2. writer 只写 `revisedTranslatedText`
3. writer 侧按 `sequence` 排序组装 `mergedDraftText`
4. `resetProjectRevisions(projectId)` + `service.resetProject(projectId)` + CLI `reset`
5. review 主链读取点切到 `effectiveTranslatedText()`
6. `ConsoleReviewRuntimeVisualizer`
7. `FAILED` 也落 session
8. `cliHumanReviewNote`
9. `WALL_CLOCK_TIMEOUT` + `maxWallClockMinutes`（默认 300，dev 配置已显式写）

仍未做：

1. 真实 1 chunk / 5-10 chunk / 127 chunk smoke 执行
2. baseline 使用说明文档化到独立 smoke runbook

新增落地：

10. `PostDraftReviewBaselineStore` + `FilePostDraftReviewBaselineStore`
11. `createProjectReviewBaseline(projectId)` / `resetProjectFromBaseline(projectId)`
12. CLI `create-baseline` / `reset-from-baseline`
