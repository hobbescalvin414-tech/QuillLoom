# Review-Agent Prompt Root-Cause Reanalysis

**Goal:** 在不回退到臃肿 prompt、不把 agent 改造成流程机的前提下，重新分析当前 review-agent 的异常行为，并把后续 prompt 补强落实到明确的固定槽位、明确的文案、明确的不改范围。

**Scope:** 本文是根因重审与 prompt 修改指引，不是代码实施计划。

---

## 1. 硬约束

1. 不能回退到 prompt 重构之前那种极度臃肿、agent 无法正常工作的状态。
2. 不能把 agent 改造成僵硬的流程机；agent 仍应保持较强自主性。
3. 一个问题优先只在它的主责层修，不接受跨所有 prompt 层散弹式补丁。
4. 本轮只修三类问题：
   - confirmed-term 命中后不收敛
   - 一次性读太多 chunk
   - 人工升级出口过宽

---

## 2. 主责层结论

### 2.1 confirmed-term 命中后不收敛

主责层：`workflow / working-discipline`

### 2.2 一次性读太多 chunk

主责层：`tool-specific guidance`

说明：

1. 这里限制的是单次读取量，不是总读取轮数。
2. 允许 agent 多次继续读取。
3. 不允许 agent 一次性读百十个 chunk。

### 2.3 人工升级出口过宽

主责层：`evaluation prompt`

---

## 3. 与当前固定 prompt 结构的对齐要求

本轮修改必须对齐当前已锁定结构：

1. Layer A：`ReviewAgentSystemPromptBuilder`
   - 保持全局角色、硬规则、authority、working discipline、completion/escalation、output contract
2. Layer B：`InvestigationPromptBuilder`
   - 保持固定五段结构
   - 本轮不作为主修改战场
3. Tool layer：`ReviewToolRegistry`
   - 承担 tool-specific `whenToUse` / `nextStepGuidance`
4. Stage-local evaluation：
   - 只用 `PromptBackedStrategyEvaluationService.EVALUATION_SYSTEM_PROMPT`
   - 本轮不再保留第二个 evaluation 补强槽位

本轮修改目标不是“哪里都补一句”，而是：

1. naming 收敛只进 Layer A 的 `working discipline`
2. adjacent read 粒度只进 `ReviewToolRegistry` 的 adjacent-read tool guidance
3. human escalation 只进 `EVALUATION_SYSTEM_PROMPT`

---

## 4. 具体修改清单

## 4.1 Naming 收敛：只改 Layer A 的 `working discipline`

### 固定槽位

1. 文件：`src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
2. 区块：`[Global Working Discipline]`
3. 修改方式：只补 1 条紧凑规则；不新增 section；不扩写成说明书

### 建议新增文案

```text
4. If the current unresolved issue is primarily naming / term consistency, prefer closing that uncertainty through read_confirmed_terms first. This is a priority rule, not a fixed step order: if the judgment still clearly depends on necessary local context, read that context first. After a confirmed-term hit, immediately check whether the current translation already follows that authority. If it does, close the naming uncertainty instead of continuing investigation only from naming discomfort.
```

### 这条规则只表达什么

1. `naming-first` 是优先判别原则，不是固定步骤。
2. 若当前判断明显仍依赖必要上下文，仍允许先补少量上下文。
3. confirmed-term 命中后要立刻比较当前译文是否服从 authority。
4. 一致则关闭 naming 不确定性，不再因命名不安感继续调查。

### 本轮明确不允许同步改的地方

1. `InvestigationPromptBuilder.renderDecisionGateTemplates()` 的 `term gate`
2. `ReviewToolRegistry` 的 adjacent-read tool guidance
3. `EvaluationPromptBuilder`
4. `PromptBackedStrategyEvaluationService.EVALUATION_SYSTEM_PROMPT`

---

## 4.2 Adjacent read 粒度：只改 adjacent-read tool guidance

### 固定槽位

1. 文件：`src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
2. 工具：`read_previous_chunks`
3. 字段：`whenToUse`、`nextStepGuidance`
4. 工具：`read_next_chunks`
5. 字段：`whenToUse`、`nextStepGuidance`

### 修改方式

1. `whenToUse` 定义单次默认读取量和扩读门槛。
2. `nextStepGuidance` 定义每次读取后的下一步判断。
3. 不在这里承接 naming 收敛语义。
4. 不在这里定义人工升级门槛。

### 建议新增文案

对 `whenToUse` 追加：

```text
Use count=1 by default. Use count=2 only when one adjacent chunk is clearly insufficient for the unresolved judgment. Prefer another small adjacent read over a large single read.
```

对 `nextStepGuidance` 追加：

```text
After each adjacent read, first check whether the current continuity / logic uncertainty is now closed. If it is still unresolved, continue with another small adjacent read. Do not jump to a large-range adjacent read when the same judgment can be closed through incremental 1-2 chunk expansion across multiple rounds.
```

### 这条规则只表达什么

1. 默认单次读取量是 `count=1`。
2. 单次 `count=2` 只用于一块明显不够的情况。
3. 如果仍未闭合，可以继续多轮读取。
4. 要避免的是一次性大读，不是多轮渐进读取。

### 本轮不在 prompt 里放开的内容

1. 本轮不为 `count>2` 设计 prompt 侧默认路径。
2. 如果后续确有必要支持更大单次读取量，应另开设计，而不是在本轮顺手放开。

### 本轮明确不允许同步改的地方

1. `ReviewAgentSystemPromptBuilder.[Global Working Discipline]`
2. `InvestigationPromptBuilder.renderDecisionGateTemplates()` 的 `continuity gate`
3. `EvaluationPromptBuilder`
4. `PromptBackedStrategyEvaluationService.EVALUATION_SYSTEM_PROMPT`

---

## 4.3 Human escalation 门槛：只改 `EVALUATION_SYSTEM_PROMPT`

### 固定槽位

1. 文件：`src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedStrategyEvaluationService.java`
2. 区块：`EVALUATION_SYSTEM_PROMPT`

### 修改方式

1. 只补 upgrade-threshold 语义。
2. 不回头重讲 investigation 主流程。
3. 不使用“只要还能做任何动作就绝不转人工”的死门槛。
4. 本轮不再保留第二个 evaluation 补强槽位。

### 建议新增文案

```text
- Do not choose REQUIRE_HUMAN_REVIEW while a clearly high-value, non-mechanical local next action is still available.
- But do not block human escalation only because some low-value local action is still theoretically possible; repeated evidence fetching, low-yield adjacent expansion, or mechanical reevaluation are not sufficient reasons to avoid escalation.
- Choose REQUIRE_HUMAN_REVIEW only when the local path is already substantially closed and a real semantic issue still remains unresolved.
```

### 这条规则只表达什么

1. 若仍有明显高价值、非机械性的本地下一步动作，通常不应转人工。
2. 但低价值扩读、重复取证、机械再评估不能无限阻止人审。
3. 只有本地路径已基本闭合但仍 unresolved，才应给 `REQUIRE_HUMAN_REVIEW`。

### 本轮明确不允许同步改的地方

1. `ReviewAgentSystemPromptBuilder`
2. `InvestigationPromptBuilder.renderDecisionGateTemplates()`
3. `ReviewToolRegistry` 的 adjacent-read tool guidance
4. `EvaluationPromptBuilder`
5. `structured_output_repair`

---

## 5. 本轮明确不改的地方

1. `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
   - 不在 `term gate` 补 naming 收敛
   - 不在 `continuity gate` 补 adjacent-read 粒度教育
2. `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
   - 除 `[Global Working Discipline]` 外，不改 authority / whitelist / output contract
3. `src/main/java/io/quillloom/application/postdraft/review/prompt/EvaluationPromptBuilder.java`
   - 本轮不作为人审门槛补强槽位
4. `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
   - 不用 schema description 补业务工作流
5. `structured_output_repair`
   - 不补正常 next-step / evaluation 行为规则

---

## 6. 预期效果

如果按本清单补强，预期 agent 行为应变成：

1. 当 naming / term consistency 是当前主不确定性时，优先用 `read_confirmed_terms` 收敛。
2. confirmed-term 命中后的收敛语义只在 Layer A 的 `working discipline` 定义，不向 tool guidance 或 evaluation 扩散。
3. adjacent read 默认单次 `count=1`，单次最多 `count=2`；若仍未闭合，则继续多轮小步读取，而不是一次性大读。
4. evaluation 只在本地路径已基本闭合但仍 unresolved 时，才升级到 `REQUIRE_HUMAN_REVIEW`。
