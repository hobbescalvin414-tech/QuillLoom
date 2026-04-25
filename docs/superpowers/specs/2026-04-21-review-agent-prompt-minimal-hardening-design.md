# 2026-04-21 Review Agent Prompt 最小硬化设计

## 1. 目标与边界

### 1.1 目标
在不做协议重构、不加新工具、不改整体架构的前提下，解决 review-agent 当前 prompt 的三类问题：
1. 运行链路仍有乱码残留。
2. investigation prompt 质量不稳定。
3. 参数落位约束表达不够集中、不够硬。

### 1.2 非目标
1. 不改 Agent A/B。
2. 不回退大 orchestrator。
3. 不改 `TranslationTaskInput` 稳定契约。
4. 不做跨层协议重构（仅限 prompt 文本与测试口径补强）。

## 2. 现状证据（带文件与行号）

### 2.1 乱码问题的历史背景与当前风险
1. `InvestigationPromptBuilder` 曾经存在直接进入运行链路的 mojibake；本设计最初立项时，这里仍被视为重点风险背景。
2. 当前代码状态下，`InvestigationPromptBuilder` 主模板已恢复为正常中文，因此本轮最小修正方向不再包含对该文件乱码证据的逐行追认。
3. 该 prompt 仍然是首轮生成入口，因此它的输出提醒与测试口径仍应作为本轮最小修正重点：
   - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:76`
   - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:84`
4. `ReviewAgentSystemPromptBuilder` 仍有局部残留：
   - `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java:33`
5. `ReviewToolRegistry` 文案也有局部残留，会被 system prompt 拼接输出：
   - `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java:58`
   - `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java:76`

### 2.2 参数落位约束已存在，但 investigation 层缺“首轮硬提醒”
1. Registry 已声明：
   - `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java:95`
   - `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java:100`
2. Structured schema 已声明：
   - `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:277`
   - `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:287`
   - `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java:289`
3. Repair prompt 已强约束：
   - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:269`
   - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:277`
   - `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java:278`

### 2.3 测试口径缺口
1. `ReviewPromptBuilderTest` 对 system prompt 有反乱码断言：
   - `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java:98`
   - `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java:114`
2. 但 investigation prompt 目前没有 `assertNoMojibake(...)`：
   - `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java:35`
   - `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java:168`
3. schema/repair 层对 entries 落位已有验证：
   - `src/test/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClientTest.java:300`
   - `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java:247`

## 3. 设计决策（最小改动）

### 3.1 最小修正方向
本轮设计收敛为两条，其他项先不进入实施：
1. 在 `InvestigationPromptBuilder` 的输出提醒里补一句硬规则：
   - `When toolName=record_confirmed_terms, candidate pairs must be written in arguments.entries, not only in reason.`
2. 在 `ReviewPromptBuilderTest` 里补两条：
   - investigation prompt 也跑 `assertNoMojibake(...)`
   - investigation prompt 断言包含 `arguments.entries` 和 `not only in reason`

### 3.2 为什么只收这两条
1. 参数落位规则目前已经存在于 schema / registry / repair 层，缺的是首轮 investigation 入口再强调一次。
2. 测试目前锁住了 system / revision 的反乱码，但还没锁 investigation。
3. 这两条能直接补上“首轮弱约束”和“验证口径缺口”，且不会扩散到协议重构、工具新增、架构调整。

### 3.3 当前不进入本轮实施的项
1. 不做 system / investigation / repair 全层语言统一。
2. 不做 investigation prompt 大重组。
3. 不做 `ReviewAgentSystemPromptBuilder` 与 `ReviewToolRegistry` 的额外文本清理，除非后续单独确认要收乱码残留。

## 4. 最小测试补点设计

### 4.1 必补项
1. 在 `ReviewPromptBuilderTest` 的 investigation 用例增加 `assertNoMojibake(prompt)`。
2. 在 `ReviewPromptBuilderTest` 增加 investigation 文本断言，锁定：
   - `arguments.entries`
   - `not only in reason`

### 4.2 测试类型划分
1. 仅同步文本的测试：
   - investigation 输出提醒新增硬规则句，导致 contains 文本更新。
2. 补验证口径漏洞的测试：
   - investigation prompt 反乱码断言（此前缺失）。
   - investigation 层 entries 落位约束断言（此前只在 schema/repair 层锁定）。

## 5. 拟改文件清单（实施阶段使用）
1. `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
2. `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

## 6. 风险与回归关注
1. 风险：提示词文字变化引起原有 contains 断言失配（可控、预期内）。
2. 回归焦点：entries 落位规则在 investigation/schema/repair 三层语义一致性。

## 7. 验收标准（设计层）
1. investigation prompt 的输出提醒显式出现 entries 落位硬规则。
2. `ReviewPromptBuilderTest` 的 investigation 用例显式覆盖 `arguments.entries` 与 `not only in reason`。
3. `ReviewPromptBuilderTest` 对 investigation 增加反乱码与落位规则断言。
4. 变更范围不扩散到协议重构、工具新增、架构调整。
