# Review-Agent Prompt Only Hardening Implementation Plan

> **For agentic workers:** 按本计划实施时，只允许修改 prompt 文案承载点；不允许顺手改 validator、executor、schema、repair、runtime 主循环或其他实现层。

**Goal:** 在不回退到臃肿 prompt、不中断 agent 自主性的前提下，按已审定的主责层分工，对 review-agent 做一轮最小 prompt 补强，修复三类已确认问题：
1. `read_confirmed_terms` 命中后不收敛
2. `read_previous_chunks` / `read_next_chunks` 单次读取量过大
3. `REQUIRE_HUMAN_REVIEW` 出口过宽

**Architecture:** 本轮只改 prompt 文案，不改行为实现层。生产代码只允许动 3 个 prompt 槽位：
1. `ReviewAgentSystemPromptBuilder.[Global Working Discipline]`
2. `ReviewToolRegistry` 中 `read_previous_chunks` / `read_next_chunks` 的 `whenToUse` / `nextStepGuidance`
3. `PromptBackedStrategyEvaluationService.EVALUATION_SYSTEM_PROMPT`

**Non-Goals:** 本轮明确不做以下事情：
1. 不回退到旧版臃肿 prompt
2. 不把 agent 改造成固定三阶段流水线
3. 不在多层重复补同一业务语义
4. 不修改 `InvestigationPromptBuilder`
5. 不修改 `EvaluationPromptBuilder`
6. 不修改 `OpenAiCompatibleReviewAgentStructuredGenerationClient`
7. 不修改 `PromptBackedNextStepDecisionProvider`
8. 不修改 `ReviewToolDecisionContractValidator`
9. 不修改 `ReviewToolExecutor`
10. 不修改 `AutonomousProjectReviewAgent`

**Design Source:** `docs/superpowers/plans/2026-04-30-review-agent-prompt-root-cause-reanalysis.md`

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Maven

---

## 1. 文件范围

### 1.1 本轮允许修改的生产文件

- `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
  - 只改 `[Global Working Discipline]`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
  - 只改 `read_previous_chunks` / `read_next_chunks`
  - 只改 `whenToUse` / `nextStepGuidance`
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedStrategyEvaluationService.java`
  - 只改 `EVALUATION_SYSTEM_PROMPT`

### 1.2 本轮明确不改的文件

- `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- `src/main/java/io/quillloom/application/postdraft/review/prompt/EvaluationPromptBuilder.java`
- `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolDecisionContractValidator.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`

### 1.3 测试策略

1. 本轮主目标是改 prompt 文案，不是补测试框架。
2. 实施后先跑现有 prompt / tool / evaluation 相关测试做回归验证。
3. 若仅因旧 prompt 字面断言失配导致测试失败，再单独评估是否需要最小化对齐测试文案；该动作不默认包含在本轮实施范围内。

---

## 2. 修改原则

### 2.1 单问题单主责层

1. naming 收敛只在 `ReviewAgentSystemPromptBuilder.[Global Working Discipline]` 定义一次。
2. adjacent read 单次读取粒度只在 `ReviewToolRegistry` 的 adjacent-read tool guidance 定义。
3. human escalation 门槛只在 `PromptBackedStrategyEvaluationService.EVALUATION_SYSTEM_PROMPT` 定义。

### 2.2 不允许跨层回流

1. 不把 naming 收敛语义补进 tool guidance。
2. 不把 naming 收敛语义补进 evaluation prompt。
3. 不把 adjacent read 粒度补进 `InvestigationPromptBuilder.continuity gate`。
4. 不把 human escalation 门槛补回 system prompt 或 investigation prompt。

### 2.3 不把优先原则写成固定流程

1. `naming-first` 只能写成优先判别原则，不能写成“先查 term，再做别的”的硬步骤。
2. adjacent read 只能约束“单次怎么读”，不能约束“总共最多读几轮”。
3. human escalation 只能收紧“何时合理升级”，不能写成“只要还有任何动作就绝不升级”。

---

## 3. 实施顺序

1. 先改 Layer A 的 naming 收敛语义
2. 再改 tool guidance 的 adjacent read 单次粒度
3. 最后改 evaluation 的 human escalation 门槛
4. 完成后统一跑回归验证

---

## 4. 任务拆分

### Task 1: 在 Layer A 补回 naming 收敛语义

**File:**
- `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`

**Slot:**
- `[Global Working Discipline]`

**修改目标：**
1. 补回“当当前未闭合问题主要是 naming / term consistency 时，优先考虑 `read_confirmed_terms`”这条工作规则。
2. 明确这是一条优先判别原则，不是固定步骤顺序。
3. 明确如果判断仍明显依赖必要局部上下文，可以先补该上下文。
4. 明确 confirmed-term 命中后，要立即检查当前译文是否已服从该 authority。
5. 明确若已一致，应关闭 naming 不确定性，而不是继续因 naming 不安感发散调查。

**本任务明确不做：**
1. 不新增新 section。
2. 不改 `[Authority Rules]`。
3. 不改 `[Global Completion / Escalation Rules]`。
4. 不把这条语义同步写到 tool guidance 或 evaluation。

**完成标志：**
1. system prompt 中存在一条紧凑的 naming 收敛规则。
2. 该规则被表述为优先判别原则，而非硬步骤。
3. 该规则没有把 agent 写成三阶段流程机。

---

### Task 2: 在 adjacent-read tool guidance 限定单次读取粒度

**File:**
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`

**Slots:**
- `read_previous_chunks.whenToUse`
- `read_previous_chunks.nextStepGuidance`
- `read_next_chunks.whenToUse`
- `read_next_chunks.nextStepGuidance`

**修改目标：**
1. 明确单次默认 `count=1`。
2. 明确单次 `count=2` 只在一块相邻 chunk 明显不够时使用。
3. 明确如果仍未闭合，可以继续多轮小步读取。
4. 明确应优先多轮 `1-2` chunk 渐进扩读，而不是一轮大范围预取。
5. 明确要避免的是“一次性大读”，不是“多轮继续读”。

**本任务明确不做：**
1. 不在 prompt 里限制总读取轮数。
2. 不在 prompt 里引入固定总量上限。
3. 不在 tool guidance 中承接 naming 收敛语义。
4. 不在 tool guidance 中承接 human escalation 门槛。
5. 不修改 `expand_block_context`。

**完成标志：**
1. adjacent-read 工具文案明确表达“单次默认 1、必要时 2、可多轮继续”。
2. 工具文案不再给模型“一次性大读”留下默认合理性。
3. 这套限制仍保留 agent 对多轮扩读的自主性。

---

### Task 3: 在 evaluation system prompt 收紧 human escalation 门槛

**File:**
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedStrategyEvaluationService.java`

**Slot:**
- `EVALUATION_SYSTEM_PROMPT`

**修改目标：**
1. 明确还有“明显高价值、非机械性”的本地下一步动作时，通常不应给 `REQUIRE_HUMAN_REVIEW`。
2. 明确低价值扩读、重复取证、机械再评估，不能无限阻止人工升级。
3. 明确只有当本地路径已大体闭合，但仍存在真实 unresolved semantic issue 时，才应给 `REQUIRE_HUMAN_REVIEW`。

**本任务明确不做：**
1. 不在 evaluation prompt 中补 naming 收敛语义。
2. 不在 evaluation prompt 中补 adjacent read 粒度教育。
3. 不把人审门槛写成“只要还有任何动作就绝不升级”的死门槛。
4. 不修改 `EvaluationPromptBuilder`。

**完成标志：**
1. evaluation prompt 明确区分高价值本地动作与低价值机械动作。
2. 人工升级出口被收紧，但没有被封死。
3. 这条规则仍保留 agent 在真实无解场景中的人工升级能力。

---

## 5. 验证计划

### 5.1 首轮回归命令

```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest,ReviewToolRegistryTest,PromptBackedStrategyEvaluationServiceTest" test
```

**目的：**
1. 验证 system prompt 文案仍满足 Layer A 结构约束。
2. 验证 adjacent-read tool definition 文案仍满足工具契约测试。
3. 验证 evaluation system prompt 文案仍满足现有评估测试约束。

### 5.2 若首轮失败，先判定失败类型

1. 若失败来自旧 prompt 字面断言失配，记录为“测试文案与新 prompt 不一致”。
2. 若失败来自行为层或契约层，立即停下，不继续扩改。
3. 若失败暴露出实现者试图把语义补回其他层，回滚到本计划定义的 3 个固定槽位。

### 5.3 人工核对项

实施后人工核对以下 6 点：

1. system prompt 是否只在 `[Global Working Discipline]` 新增 naming 收敛语义
2. `InvestigationPromptBuilder` 是否保持不动
3. adjacent-read tool guidance 是否表达“单次 1-2，可多轮继续”
4. evaluation system prompt 是否只讨论升级门槛，不夹带 naming 语义
5. 整体 prompt 是否没有明显回膨胀
6. 整体 prompt 是否仍保留 agent 自主性，没有滑成固定流程机

---

## 6. 交付标准

满足以下条件，才算本轮 prompt-only 实施完成：

1. 只修改了计划允许的 3 个生产文件
2. 未修改 investigation / schema / validator / executor / runtime 主循环
3. naming 收敛、adjacent read 粒度、human escalation 门槛分别只落在各自主责槽位
4. 回归命令已实际执行
5. 验证结果已记录，且没有通过跨层补丁掩盖问题

---

## 7. 风险与停机条件

### 7.1 主要风险

1. naming 规则写得过强，滑成固定步骤机
2. adjacent read 写得过死，误伤正常多轮扩读
3. human escalation 写得过死，导致 agent 在低价值动作里空转
4. 实施者顺手把语义补回其他层，导致 prompt 再次膨胀

### 7.2 停机条件

出现以下任一情况，应停止实施并回到设计审查：

1. 需要改第 4 个生产文件才能让设计成立
2. 需要修改 `InvestigationPromptBuilder` 才能表达本轮目标
3. 需要修改 schema / validator / executor 才能让本轮目标成立
4. 为了通过验证，开始在多层重复补同一业务语义
