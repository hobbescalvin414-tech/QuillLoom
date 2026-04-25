# Review Agent 端到端加固实施报告

**日期**：2026-04-17 ~ 2026-04-18
**计划文档**：`docs/superpowers/plans/2026-04-18-review-agent-e2e-hardening-plan.md`

---

## 总览

计划中 8 个问题，已完成 5 个（问题 1a、1b、3、4、5、8），半做 1 个（问题 2），未开始 3 个（问题 1c、1d、6、7）。

| 问题 | 状态 | 说明 |
|------|------|------|
| 1a. TranscriptStore 自动压缩 | ✅ 已完成 | compact + prepend + 每轮检查 |
| 1b. EvidenceBundle 去重与压缩 | ✅ 已完成 | compact + totalEntries + 与 transcript 压缩同步 |
| 1c. Token 预算追踪 | ❌ 未开始 | |
| 1d. 项目级记忆压缩 | ❌ 未开始 | |
| 2. HITL 恢复路径 | ⚠️ 半做 | 底层 resume() + resumeFromHumanReview() 已实现，上层未接通，设计方向已变更为求助式 HITL |
| 3. Reader 全量加载 | ✅ 已完成 | ConcurrentHashMap 缓存 + 装饰器 |
| 4. System Prompt 分离 | ✅ 已完成 | ReviewAgentSystemPromptBuilder + 端口签名变更 |
| 5. Per-tool JSON Schema | ✅ 已完成 | ToolArgumentSchema + 动态渲染 + repair prompt 增强 |
| 6. LLM 重试/退避 | ❌ 未开始 | |
| 7. Legacy 构造器清理 | ❌ 未开始 | |
| 8. lookup_knowledge_cards 向量检索 | ✅ 已完成 | KnowledgeRetrievalService 集成 |

---

## 问题 1a：TranscriptStore 自动压缩

### 改动文件

| 文件 | 改动 |
|------|------|
| `application/postdraft/review/model/ReviewAgentConfig.java` | 新增 `compactKeepLast` 字段（默认 8） |
| `application/postdraft/review/model/TranscriptStore.java` | 新增 `prepend(String entry)` 方法 |
| `application/postdraft/review/model/PostDraftReviewSession.java` | 新增 `withTranscriptStore(TranscriptStore)` 方法 |
| `application/postdraft/review/service/AutonomousProjectReviewAgent.java` | 新增 `compactFocusTranscriptIfNeeded()` 和 `buildCompactSummary()`；构造器新增 `ReviewAgentConfig` 参数 |

### 实现逻辑

1. `AutonomousProjectReviewAgent.run()` 每轮工具执行后调用 `compactFocusTranscriptIfNeeded()`
2. 当 `transcriptStore.replay().size() > config.compactAfterTurns()` 时触发压缩
3. 压缩步骤：`compact(compactKeepLast)` 保留最近 N 条 → `prepend(compactSummary)` 在头部插入摘要
4. 摘要格式：`[compact] 已完成 N 轮取证，当前策略=LIGHT_EDIT，已读chunk=[chunk-1,chunk-2]，关键发现=...`

---

## 问题 1b：EvidenceBundle 去重与压缩

### 改动文件

| 文件 | 改动 |
|------|------|
| `application/postdraft/review/model/ReviewEvidenceBundle.java` | 新增 `compact(int maxEntries)` 和 `totalEntries()` 方法 |
| `application/postdraft/review/model/ReviewAgentConfig.java` | 新增 `compactKeepLastEvidence` 字段（默认 12） |
| `application/postdraft/review/service/AutonomousProjectReviewAgent.java` | `compactFocusTranscriptIfNeeded()` 同时检查并压缩 EvidenceBundle |

### 实现逻辑

1. `ReviewEvidenceBundle.compact(maxEntries)`：对 5 个列表分别压缩，超过 maxEntries 时保留最近 N 条并在头部插入 `[compact:label]` 摘要
2. `ReviewEvidenceBundle.totalEntries()`：返回 5 个列表的总条目数，用于判断是否需要压缩
3. `compactFocusTranscriptIfNeeded()` 改为同时检查 transcript 和 evidence：
   - 两者都未超阈值 → 不压缩
   - transcript 超阈值 → 压缩 transcript（保留最近 compactKeepLast 条 + prepend 摘要）
   - evidence 超阈值 → 压缩 evidence（保留最近 compactKeepLastEvidence 条）
   - 两者可独立触发，互不影响

### 效果

130 chunk × 5 轮场景下，EvidenceBundle 不会无限膨胀，每个列表最多保留 compactKeepLastEvidence + 1 条（含摘要）。

---

## 问题 2：HITL 恢复路径

### 改动文件

| 文件 | 改动 |
|------|------|
| `application/postdraft/review/model/ProjectReviewRuntimeSession.java` | 新增 `resumeFromHumanReview(String humanReviewNote)` |
| `application/postdraft/review/service/AutonomousProjectReviewAgent.java` | 新增 `resume(ProjectReviewRuntimeSession, String humanReviewNote)` |

### 实现逻辑

1. `resumeFromHumanReview()` 验证当前状态为 `WAITING_HUMAN`，否则抛异常
2. 将状态恢复为 `ACTIVE`，focus session 设为 `INVESTIGATING`
3. 人工审阅意见写入 transcript 作为证据：`[human_review] 人工意见: ...`
4. **人工输入是证据，不是命令**：agent 恢复后自主决策下一步，不按人工指定的状态走
5. `resume()` 调用 `resumeFromHumanReview()` 后重新进入 `run()` 主循环

### 修复的 bug

`resumeFromHumanReview()` 构造器参数顺序错误：`currentFocusRound` 放在了 `transcriptStore` 前面，导致类型不匹配。已修正为正确的 canonical 构造器参数顺序。同时 `currentFocusChunkId` 修正为 `selectedFocusChunkId`。

---

## 问题 3：Reader 全量加载

### 改动文件

| 文件 | 改动 |
|------|------|
| `infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java` | 内部加 `ConcurrentHashMap` 缓存，`loadReviewPackage()` / `loadKnowledgeBase()` 改为 `computeIfAbsent`；新增 `invalidateCache(projectId)` |
| `infrastructure/postdraft/review/CachingPostDraftReviewAgentReader.java` | 新增装饰器类，委托给 delegate |
| `infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentTermWriter.java` | 构造器新增 `RepositoryBackedPostDraftReviewAgentReader` 参数，写回后调用 `reader.invalidateCache()` |

### 效果

130 chunk × 5 轮 × 2-3 次 reader 调用，repository 访问从 ~1950 次降到每个 projectId 1 次（直到缓存失效）。

---

## 问题 4：System Prompt 分离

### 改动文件

| 文件 | 改动 |
|------|------|
| `application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java` | **新增**：构建稳定 system prompt |
| `application/postdraft/review/port/out/ReviewAgentStructuredGenerationPort.java` | 4 个方法签名从 `(String prompt)` 改为 `(String systemPrompt, String userPrompt)` |
| `infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java` | `invoke()` 改为接收 systemPrompt + userPrompt，构造 `SystemMessage` + `UserMessage` |
| `application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java` | 注入 `ReviewAgentSystemPromptBuilder`，`decide()` 分别构建 systemPrompt 和 userPrompt |
| `application/postdraft/review/service/PromptBackedStrategyEvaluationService.java` | 新增 `EVALUATION_SYSTEM_PROMPT` 常量 |
| `application/postdraft/review/service/PromptBackedRevisionDraftProvider.java` | 新增 `REVISION_SYSTEM_PROMPT` 常量 |
| `application/postdraft/review/service/LlmBackedRevisionSelfCheckService.java` | 新增 `SELF_CHECK_SYSTEM_PROMPT` 常量 |
| `application/postdraft/review/prompt/InvestigationPromptBuilder.java` | 移除角色定义、关键语义、决策规则（已移至 system prompt） |
| `application/postdraft/review/prompt/EvaluationPromptBuilder.java` | 移除评估要求（已移至 EVALUATION_SYSTEM_PROMPT） |
| `application/postdraft/review/prompt/RevisionPromptBuilder.java` | 移除角色定义 |
| `application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java` | 移除角色定义 |

### 效果

- 每轮减少重复发送约 500-800 token 的规则文本
- LLM 有稳定的身份锚定，减少长对话漂移

---

## 问题 5：Per-tool JSON Schema

### 改动文件

| 文件 | 改动 |
|------|------|
| `application/postdraft/review/model/ToolArgumentSchema.java` | **新增**：工具参数的结构化描述 record |
| `application/postdraft/review/model/ReviewToolDefinition.java` | 新增 `argumentSchemas` 字段和 `renderArgumentRequirements()` 方法；保留 3 参数兼容构造器 |
| `application/postdraft/review/service/ReviewToolRegistry.java` | `defaultRegistry()` 为每个工具注册 `argumentSchemas` |
| `application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java` | 工具列表渲染增加参数描述行 |
| `application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java` | repair prompt 动态注入工具参数要求 |

### 实现逻辑

1. `ToolArgumentSchema`：描述工具参数的 name、type、required、description，提供 `render()` 方法输出人类可读格式
2. `ReviewToolDefinition.argumentSchemas`：每个工具注册结构化参数描述，例如：
   - `read_previous_chunks`: `count (integer, 必填): 读取的前文 chunk 数量，必须为正整数`
   - `complete_working_set`: `chunkIds (string[], 必填): 本轮确认完成的 chunkId 列表，必须包含 anchor，只能从 workingSet 中选择`
3. `ReviewAgentSystemPromptBuilder` 在工具列表中渲染参数描述：
   ```
   - complete_working_set: 提交当前 anchor 轮次下已确认完成的 chunkIds...; requiredArguments=[chunkIds]
     参数: chunkIds (string[], 必填): 本轮确认完成的 chunkId 列表，必须包含 anchor，只能从 workingSet 中选择
   ```
4. `PromptBackedNextStepDecisionProvider` 的 repair prompt 动态注入：
   - `buildDecisionRepairPrompt()`：注入 `该工具的参数要求: <renderArgumentRequirements()>`
   - `buildStructuredOutputRepairPrompt()`：注入 `各工具参数要求: <所有有参数工具的汇总>`

### 效果

- LLM 在 system prompt 中就能看到每个工具的参数类型和含义，减少参数缺失
- repair prompt 不再是硬编码的参数要求，而是从 `ReviewToolRegistry` 动态生成
- 新增工具时只需在 registry 注册 `argumentSchemas`，prompt 自动更新

---

## 问题 8：lookup_knowledge_cards 接入向量检索

### 改动文件

| 文件 | 改动 |
|------|------|
| `application/translation/model/KnowledgeRetrievalUseCase.java` | 新增 `REVIEW_AGENT_LOOKUP` 枚举值 |
| `application/postdraft/review/port/out/PostDraftReviewAgentReader.java` | `lookupKnowledgeCards` 签名从 `(projectId, chunkId)` 改为 `(projectId, chunkId, queryTerms)` |
| `infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java` | 注入 `KnowledgeRetrievalService`；有 queryTerms 时走向量检索，无 queryTerms 时 fallback 到 applicableChunkIds 过滤 |
| `infrastructure/postdraft/review/CachingPostDraftReviewAgentReader.java` | 更新 `lookupKnowledgeCards` 签名 |
| `application/postdraft/review/service/ReviewToolExecutor.java` | `executeKnowledgeLookup()` 从 call.arguments 提取 `queryTerms` 传给 reader |
| `application/postdraft/review/service/ReviewToolRegistry.java` | `lookup_knowledge_cards` 注册 `queryTerms` 可选参数 |

### 测试覆盖

- `shouldLookupKnowledgeCardsByApplicableChunkId`：无 queryTerms 时走 fallback
- `shouldLookupKnowledgeCardsByVectorSearchWhenQueryTermsProvided`：有 queryTerms 时走向量检索
- `shouldReturnEmptyKnowledgeCardsWhenNoChunkMappingExists`：无匹配返回空

---

## 测试验证

### 编译

```
mvn clean compile -q       → 通过
mvn clean test-compile -q  → 通过
```

### 测试结果

```
mvn -q "-Dtest=AutonomousProjectReviewAgentTest,
  ReviewToolExecutorGuardrailTest,
  PostDraftReviewAgentServiceTest,
  WorkingSetCompletionHandlerTest,
  RepositoryBackedPostDraftReviewAgentReaderTest,
  RepositoryBackedPostDraftReviewAgentTermWriterTest,
  PromptBackedNextStepDecisionProviderTest,
  ReviewPromptBuilderTest" test

Tests run: 56, Failures: 0, Errors: 0, Skipped: 0
```

```
mvn -q "-Dtest=ReviewToolRegistryTest,TranscriptStoreModelTest,
  ReviewEvidenceBundleTest,ReviewWorkingSetModelTest,
  PostDraftReviewSessionModelTest,PostDraftProjectRuntimeSessionModelTest,
  FocusHumanStopPolicyTest,PostDraftRevisionServiceTest" test

Tests run: (全部通过)
```

### 已知预存问题

`ProjectFocusSelectorTest` 有 2 个预存失败（`expected: INVESTIGATING but was: SELECTING_FOCUS`），与本次改动无关。

### 修复的附带问题

- `resumeFromHumanReview()` 构造器参数顺序错误（`currentFocusRound` 位置不对）和字段名错误（`currentFocusChunkId` → `selectedFocusChunkId`）
- 18 个测试文件被 PowerShell 脚本写入了 UTF-8 BOM，导致 `mvn clean` 后编译失败。已批量去除 BOM。
- `PostDraftProjectReviewAgentSmokeTest` 的 `RepositoryBackedPostDraftReviewAgentTermWriter` 构造器缺少 `reader` 参数
- `RepositoryBackedPostDraftReviewAgentTermWriterTest` 缺少 `Optional` import

---

## 修改文件清单

### 新增文件

| 文件 |
|------|
| `application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java` |
| `infrastructure/postdraft/review/CachingPostDraftReviewAgentReader.java` |
| `application/postdraft/review/model/ToolArgumentSchema.java` |

### 修改文件（主代码）

| 文件 | 涉及问题 |
|------|---------|
| `application/postdraft/review/model/ReviewAgentConfig.java` | 1a, 1b |
| `application/postdraft/review/model/TranscriptStore.java` | 1a |
| `application/postdraft/review/model/PostDraftReviewSession.java` | 1a |
| `application/postdraft/review/model/ProjectReviewRuntimeSession.java` | 2 |
| `application/postdraft/review/model/ReviewEvidenceBundle.java` | 1b |
| `application/postdraft/review/model/ReviewToolDefinition.java` | 5 |
| `application/postdraft/review/service/AutonomousProjectReviewAgent.java` | 1a, 1b, 2 |
| `application/postdraft/review/port/out/PostDraftReviewAgentReader.java` | 8 |
| `application/postdraft/review/port/out/ReviewAgentStructuredGenerationPort.java` | 4 |
| `application/postdraft/review/service/ReviewToolExecutor.java` | 8 |
| `application/postdraft/review/service/ReviewToolRegistry.java` | 5, 8 |
| `application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java` | 4, 5 |
| `application/postdraft/review/service/PromptBackedStrategyEvaluationService.java` | 4 |
| `application/postdraft/review/service/PromptBackedRevisionDraftProvider.java` | 4 |
| `application/postdraft/review/service/LlmBackedRevisionSelfCheckService.java` | 4 |
| `application/postdraft/review/prompt/InvestigationPromptBuilder.java` | 4 |
| `application/postdraft/review/prompt/EvaluationPromptBuilder.java` | 4 |
| `application/postdraft/review/prompt/RevisionPromptBuilder.java` | 4 |
| `application/postdraft/review/prompt/RevisionSelfCheckPromptBuilder.java` | 4 |
| `application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java` | 5 |
| `application/translation/model/KnowledgeRetrievalUseCase.java` | 8 |
| `infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentReader.java` | 3, 8 |
| `infrastructure/postdraft/review/RepositoryBackedPostDraftReviewAgentTermWriter.java` | 3 |
| `infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java` | 4 |
| `infrastructure/postdraft/review/CachingPostDraftReviewAgentReader.java` | 8 |

### 修改文件（测试代码）

| 文件 | 涉及问题 |
|------|---------|
| `RepositoryBackedPostDraftReviewAgentReaderTest.java` | 3, 8 |
| `RepositoryBackedPostDraftReviewAgentTermWriterTest.java` | 3, 8 |
| `AutonomousProjectReviewAgentTest.java` | 4, 8 |
| `ReviewToolExecutorGuardrailTest.java` | 4, 8 |
| `PostDraftReviewAgentServiceTest.java` | 4, 8 |
| `WorkingSetCompletionHandlerTest.java` | 8 |
| `PostDraftProjectReviewAgentSmokeTest.java` | 3, 8 |
| `PostDraftReviewAgentSmokeTest.java` | 8 |
| `PromptBackedNextStepDecisionProviderTest.java` | 4 |
| `ReviewPromptBuilderTest.java` | 4 |
| 18 个测试文件 BOM 修复 | 附带 |

---

## 后续工作建议

按优先级排序（详见 `2026-04-18-review-agent-e2e-run-gap-analysis.md`）：

1. **1.1 修订译文写回数据库**：最关键，没有这个跑完也白跑。`PassThroughPostDraftReviewAgentWriter` 需替换为 `PostgresPostDraftReviewAgentWriter`。
2. **1.3 Session 持久化可恢复**：HITL 的前提。`StoredReviewSession` 需保存完整的 `ProjectReviewRuntimeSession`。
3. **1.2 求助式 HITL**：依赖 1.3。设计方向已从排障式变更为求助式（Codex 风格），agent 主动问人，人回答后自动继续。
4. **2.1 工具系统解耦**：`ReviewToolExecutor` 550+ 行 switch-case，需改为 `ReviewTool` 接口 + 独立实现类 + 自动注册。
5. **2.2 结构化压缩摘要**：`buildCompactSummary()` 硬拼 4 个字段质量太差，需从 session 各记忆项提取信息。
6. **2.5 Spring Bean 装配**：`PostDraftReviewAgentRuntimeConfiguration` 只注册了一个 Bean，需补全。
7. **2.6 LLM 重试/退避**：429/503 直接崩，需加重试。
8. **问题 1c/1d**：Token 预算追踪 + 项目级记忆压缩。`UsageBudget` 和 `UsageSummary` 仍是空壳。
9. **问题 7**：Legacy 构造器清理。技术债。
10. **`withCurrentFocusSession` 脆弱点修复**：硬编码 ACTIVE + 清空 humanReviewRequest，存在静默吞掉 WAITING_HUMAN 的风险。
11. **`ProjectFocusSelectorTest` 预存失败修复**：2 个测试期望 INVESTIGATING/FINALIZING 但代码返回 SELECTING_FOCUS。
