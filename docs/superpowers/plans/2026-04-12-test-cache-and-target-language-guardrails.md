# Test Cache And Target Language Guardrails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为冒烟测试新增可丢弃的 A/B/C0 完成态缓存，并收紧当前链路的目标语言约束，避免非目标语言内容混入初稿或 C0 检索规划。

**Architecture:** 测试缓存只存在于 `src/test`，通过 `PreprocessDossier` 边界读写，不进入正式 workflow/service 主路径；目标语言约束只修最小必要的 prompt、schema 与规则校验，不重做整条翻译链路。正式持久化本次只落文档，不落生产代码。

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Jackson, Maven

---

### Task 1: 写出正式持久化设计文档

**Files:**
- Create: `docs/superpowers/specs/2026-04-12-stage-persistence-design.md`
- Modify: `docs/handoff.md`

- [ ] **Step 1: 写设计文档**

文档必须覆盖：
- `A/B/C0/D` 完成态持久化
- 同书历史运行发现与用户选择恢复
- 只允许从完成阶段恢复
- 从某完成阶段恢复时，后续阶段逻辑失效并重跑
- 知识卡按书隔离，不跨书共享
- 使用 `postgres`
- 使用阶段快照 DTO，不直接持久化运行时内部对象

- [ ] **Step 2: 在 handoff 中登记设计结论**

在 `docs/handoff.md` 增加简短条目，说明：
- 正式持久化方案已落文档
- 当前实现仍只做测试夹具级缓存，不进入正式链路

### Task 2: 为测试夹具缓存写失败测试

**Files:**
- Create: `src/test/java/io/quillloom/support/PreprocessSmokeCacheSupportTest.java`
- Modify: `src/test/java/io/quillloom/BookWorkflowSampleSmokeTest.java`

- [ ] **Step 1: 写缓存命中行为测试**

新增测试覆盖：
- 首次无缓存时会执行预处理并写入缓存
- 再次运行时可直接命中缓存并跳过 A/B/C0
- 只接受完成态缓存，缺文件或损坏缓存时回退为重新预处理

- [ ] **Step 2: 先运行测试，确认失败**

Run:
```powershell
mvn -q "-Dtest=PreprocessSmokeCacheSupportTest" test
```

Expected:
- FAIL，因为缓存支持类尚不存在

### Task 3: 为目标语言约束写失败测试

**Files:**
- Modify: `src/test/java/io/quillloom/infrastructure/translation/TranslationPromptRendererTest.java`
- Create: `src/test/java/io/quillloom/infrastructure/preprocess/KnowledgeNeedPlanningPromptRendererTest.java`
- Modify: `src/test/java/io/quillloom/infrastructure/translation/OpenAiCompatibleLlmChunkTranslationClientTest.java`

- [ ] **Step 1: 写 D prompt / schema 的目标语言测试**

新增断言：
- 当 `targetLanguage = "en"` 时，`TranslationPromptRenderer` 不应写死“中文初稿”
- `OpenAiCompatibleLlmChunkTranslationClient` 的 schema 描述不应写死“中文翻译草稿”

- [ ] **Step 2: 写 C0 planner 的目标语言测试**

新增断言：
- `KnowledgeNeedPlanningPromptRenderer` 接收目标语言信息
- 当 `targetLanguage = "zh"` 时，prompt 明确要求查询优先服务中文翻译任务
- 当 `targetLanguage = "en"` 时，prompt 明确要求查询优先服务英文翻译任务

- [ ] **Step 3: 先运行测试，确认失败**

Run:
```powershell
mvn -q "-Dtest=TranslationPromptRendererTest,KnowledgeNeedPlanningPromptRendererTest,OpenAiCompatibleLlmChunkTranslationClientTest" test
```

Expected:
- FAIL，因为当前仍有“中文”写死，且 C0 planner 尚未吃到目标语言

### Task 4: 实现测试夹具级缓存

**Files:**
- Create: `src/test/java/io/quillloom/support/PreprocessSmokeCacheSupport.java`
- Modify: `src/test/java/io/quillloom/BookWorkflowSampleSmokeTest.java`

- [ ] **Step 1: 实现测试缓存帮助器**

要求：
- 仅放在 `src/test`
- 仅缓存 `PreprocessDossier` 完成态
- 缓存 key 至少包含：源文本 hash、源语言、目标语言、缓存版本
- 使用测试目录，例如 `target/test-cache/book-workflow-sample/`
- 缓存损坏时显式重跑，不静默伪造数据

- [ ] **Step 2: 在样例冒烟测试中接入缓存**

要求：
- 正式 workflow 代码不改
- 测试类先尝试读缓存
- 未命中时调用现有预处理链路，再写缓存
- 基于缓存内容继续跑 D 与 draft artifact 检查

- [ ] **Step 3: 运行缓存相关测试**

Run:
```powershell
mvn -q "-Dtest=PreprocessSmokeCacheSupportTest,BookWorkflowSampleSmokeTest" "-Dquillloom.test.book-workflow-sample.enabled=true" test
```

Expected:
- PASS 或至少 `PreprocessSmokeCacheSupportTest` PASS；若样例冒烟依赖外部服务不可用，需记录外部阻塞

### Task 5: 实现目标语言约束收紧

**Files:**
- Modify: `src/main/java/io/quillloom/infrastructure/translation/TranslationPromptRenderer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/TranslationPromptProperties.java`
- Modify: `src/main/java/io/quillloom/infrastructure/translation/OpenAiCompatibleLlmChunkTranslationClient.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/KnowledgeNeedPlanningPromptRenderer.java`
- Modify: `src/main/java/io/quillloom/infrastructure/preprocess/LlmKnowledgeNeedPlanner.java`

- [ ] **Step 1: 收紧 D prompt**

要求：
- 所有“中文初稿/中文翻译草稿”改为基于 `targetLanguage` 渲染
- 明确要求正文必须只使用目标语言；专名、引文、信件抬头等若必须保留原文，必须是源文真实存在的内容，不得额外混入第三语言解释

- [ ] **Step 2: 收紧 D schema**

要求：
- JSON schema 的 `translatedText` 描述改为中性目标语言描述
- 不再用“中文翻译草稿”写死输出语义

- [ ] **Step 3: 给 C0 planner 加入目标语言意识**

要求：
- prompt 渲染时纳入 `targetLanguage`
- 明确 query 语言应优先服务目标语言翻译
- 针对 `zh` 禁止默认漂到 `English translation` 一类查询风格

- [ ] **Step 4: 运行目标语言相关测试**

Run:
```powershell
mvn -q "-Dtest=TranslationPromptRendererTest,KnowledgeNeedPlanningPromptRendererTest,OpenAiCompatibleLlmChunkTranslationClientTest,LlmKnowledgeNeedPlannerTest" test
```

Expected:
- PASS

### Task 6: 做最终验证与文档同步

**Files:**
- Modify: `docs/current-status.md`
- Modify: `docs/handoff.md`

- [ ] **Step 1: 更新文档**

说明：
- 测试夹具级缓存的边界
- 正式持久化尚未实现
- target-language 约束已收紧到 D 和 C0 planner

- [ ] **Step 2: 运行最终回归**

Run:
```powershell
mvn -q "-Dtest=PreprocessSmokeCacheSupportTest,TranslationPromptRendererTest,KnowledgeNeedPlanningPromptRendererTest,OpenAiCompatibleLlmChunkTranslationClientTest,LlmKnowledgeNeedPlannerTest,BookWorkflowSampleSmokeTest" "-Dquillloom.test.book-workflow-sample.enabled=true" test
```

Expected:
- 本地逻辑测试通过
- 若全链路冒烟受外部模型/搜索服务阻塞，明确记录阻塞点，不虚报完成
