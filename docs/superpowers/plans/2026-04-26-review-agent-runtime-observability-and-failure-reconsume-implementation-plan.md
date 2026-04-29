# Review Agent Runtime Observability And Failure Reconsume Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: use `superpowers:executing-plans` or `superpowers:subagent-driven-development` to implement this plan task by task. Steps use checkbox syntax for tracking.

**Goal:** 在不改变 review-agent 主骨架、不改变工具协议、不改变 persistence/resume schema 的前提下，修复相邻读取空转 success，补齐 ordinary round / repair / proposal / local replan / containable failure 的控制台可观测性，为 containable failure 增加一次有限尾扫式再消费，并让面向人的摘要字段默认使用当前译文目标语言。

**Architecture:** 修复点严格收敛在 4 个责任面：
1. `ReviewToolExecutor` 负责拦截无净新增的相邻读取。
2. canonical render/view 只作为展示层与 prompt 注入层的只读视图，不修改 `ReviewWorkingSet` 全局 runtime 语义。
3. `AutonomousProjectReviewAgent` 持有 agent-private runtime-only deferred tail state，只补一轮有限 endgame tail pass。
4. `ReviewRuntimeVisualizer` / `ConsoleReviewRuntimeVisualizer` 只做展示，不反向侵入 provider / executor。

**Out Of Scope:**
1. 不把 true transport retry 接入 `ReviewRuntimeVisualizer`。
2. 不修改 `RetryingReviewAgentStructuredGenerationPort`。
3. 不修改 `OpenAiCompatibleReviewAgentStructuredGenerationClient`。
4. 不新增 persistence / resume payload 字段。
5. 不修改 `ReviewWorkingSet.chunkIds()` 的全局语义。
6. 不把 deferred tail pass 演变成新的调度系统。

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5

---

## 1. 文件范围与职责

### 1.1 必改生产文件

- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
  - 拦截无净新增的 `read_previous_chunks` / `read_next_chunks`
  - 产出稳定 rejection reason 与 `local_replan_hint`
- `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
  - 发射高层 visualizer 事件
  - 持有 agent-private runtime-only deferred tail state
  - 在普通 pending 清空后执行一次有限 tail pass
- `src/main/java/io/quillloom/application/postdraft/review/service/ReviewRuntimeVisualizer.java`
  - 扩展最小 round / repair / containable failure 事件集
- `src/main/java/io/quillloom/application/postdraft/review/service/ConsoleReviewRuntimeVisualizer.java`
  - 实现 `OFF / COMPACT / TRACE`
  - 渲染 canonical view，而不是直接打印 anchor-first working set
- `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`
  - 优先复用既有 carrier 暴露 repair / proposal / replan 诊断
  - 仅在必要时补 provider-private read-only diagnostics
- `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
  - 增加人类可见摘要字段的目标语言约束
- `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
  - 使用 canonical view 注入上下文
  - 增加“无净新增相邻读取时禁止重复调用”的规则
- `src/main/java/io/quillloom/infrastructure/postdraft/review/ReviewAgentRuntimeProperties.java`
  - 新增 console mode 配置
- `src/main/java/io/quillloom/infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfiguration.java`
  - wiring visualizer mode

### 1.2 必改测试文件

- `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ConsoleReviewRuntimeVisualizerTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
- `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

### 1.3 不改文件

- `src/main/java/io/quillloom/infrastructure/postdraft/review/RetryingReviewAgentStructuredGenerationPort.java`
- `src/main/java/io/quillloom/infrastructure/postdraft/review/OpenAiCompatibleReviewAgentStructuredGenerationClient.java`
- persistence / resume schema 相关文件
- tool protocol DTO / enum shape
- `src/main/java/io/quillloom/application/postdraft/review/model/ReviewWorkingSet.java`

---

## 2. 实施顺序

1. 先锁测试：无净新增相邻读取、canonical view 顺序、一次有限 tail pass、`OFF / COMPACT / TRACE`、中文摘要语言策略。
2. 再修 executor 与 canonical view 承载/消费链路。
3. 再修 agent-private deferred tail pass。
4. 再落 visualizer 事件与 console mode。
5. 最后补 prompt 语言策略与 provider 诊断暴露。

---

## 3. 承载位置约定

### 3.1 canonical render/view 的承载位置

这轮不改 `ReviewWorkingSet`。canonical render/view 必须满足：

1. 承载位置是 review service 层的只读 helper / renderer，不进入领域模型。
2. 输入保留 `ReviewWorkingSet` 与 `anchorChunkId`。
3. 输出只提供 canonical chunk order 视图，供 console visualizer 与 prompt 注入使用。
4. 首个消费点必须明确落在：
   - `ConsoleReviewRuntimeVisualizer`
   - `InvestigationPromptBuilder`
5. 如果后续还有其他 prompt 需要 canonical view，只能复用同一 helper，不允许各自再做一套排序。

### 3.2 runtime-only deferred state 的断言对象

这轮不新增 persistence / resume schema。所谓“runtime-only deferred state 不进入 persistence / resume”，验收时必须落到可验证对象：

1. 不新增 persistence payload / resume payload 类型字段。
2. 不新增 runtime session 对外持久化字段映射。
3. `AutonomousProjectReviewAgentTest` 里的兼容性断言，不是只看运行结果，而是要显式断言：
   - 当前用于持久化/恢复的 payload 类型与字段集合未变
   - deferred tail state 仅存在于 agent 局部运行期对象中
4. 如果实现阶段发现无法在现有测试中可信断言以上 2 点，必须补最小专门 compatibility test；不能用口头说明替代。

---

## 4. 任务拆分

### Task 1: 锁定相邻读取与 canonical view 回归

**Files:**
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

- [ ] **Step 1: 新增“相邻读取无净新增必须 rejected”测试**

覆盖两种路径：
1. 已到左/右边界，reader 只返回边界自身或空窗口。
2. reader 返回的 chunk 已全部存在于当前 boundary / working set。

断言：
1. `success=false`
2. rejection reason 固定为稳定错误码，例如 `redundant_adjacent_read`
3. transcript 中出现明确 `local_replan_hint`

- [ ] **Step 2: 新增“canonical view 顺序必须按 sequence 输出”测试**

场景：
1. focus=`chunk-7`
2. 先读入 `chunk-8`
3. 再读入 `chunk-6`

断言：
1. canonical view 输出为 `[chunk-6, chunk-7, chunk-8]`
2. `anchorChunkId` 仍然是 `chunk-7`
3. 不要求修改 `ReviewWorkingSet.chunkIds()` 全局语义

- [ ] **Step 3: 锁定 prompt 注入层也必须吃 canonical view**

断言对象放在 `ReviewPromptBuilderTest`：
1. `InvestigationPromptBuilder` 注入的 working set / context 按 canonical 顺序出现
2. 不是只在 visualizer 里重排
3. 这一步只锁顺序，不锁中文语言策略

- [ ] **Step 4: 跑定向测试**

Run:
```powershell
mvn -q "-Dtest=ReviewToolExecutorGuardrailTest,ReviewPromptBuilderTest" test
```

Expected:
- 先失败，暴露当前 executor 与 prompt 注入层还未满足新断言

### Task 2: 实现无净新增相邻读取拦截与 canonical view 消费链路

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ConsoleReviewRuntimeVisualizer.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`

- [ ] **Step 1: 在 executor 中引入“净新增 chunk”判定**

实现要求：
1. 比较读取前后的 chunk id 集合
2. 若净新增为 0，则 rejected
3. rejection detail 固定化，便于 visualizer 与 prompt 识别

- [ ] **Step 2: 在 `buildLocalCorrectionHint(...)` 中补相邻读取重复提示**

提示内容必须明确：
1. 当前方向已无新增 chunk
2. 不要重复相同方向的相邻读取
3. 如仍需证据则改读另一侧，否则转 `evaluate_focus` / `complete_working_set`

- [ ] **Step 3: 引入 canonical render/view helper，并接到首个消费点**

要求：
1. helper 只提供 canonical 顺序视图，不回写 runtime 模型
2. `ConsoleReviewRuntimeVisualizer` 使用它渲染 working set
3. `InvestigationPromptBuilder` 使用它注入上下文
4. 不修改 `ReviewWorkingSet.chunkIds()` 语义

- [ ] **Step 4: 跑定向测试**

Run:
```powershell
mvn -q "-Dtest=ReviewToolExecutorGuardrailTest,ReviewPromptBuilderTest" test
```

Expected:
- PASS

### Task 3: 锁定 containable failure 的一次有限尾扫行为

**Files:**
- Modify: `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`

- [ ] **Step 1: 新增“失败 chunk 进入 deferred tail pass”测试**

场景：
1. `chunk-1` 首次触发 containable structured failure
2. `chunk-2` 正常完成
3. 普通 pending 清空后再次尝试 `chunk-1`

断言：
1. `chunk-1` 不会首次失败后永久丢失
2. 存在 tail-pass 再消费痕迹
3. tail pass 只在普通 pending 清空后触发

- [ ] **Step 2: 新增“超过 deferred 次数上限后仍阻止 complete_project”测试**

断言：
1. 超上限后仍保留 backlog
2. 项目不能 `PROJECT_COMPLETED`
3. 不存在第二层 tail pass 或新的通用优先级机制

- [ ] **Step 3: 新增“runtime-only deferred state 不进入 persistence / resume 契约”测试**

注意：这里不是泛泛断言运行结果，而是显式断言契约对象。
优先在 `AutonomousProjectReviewAgentTest` 中锁最小契约断言；如果为表达该契约而开始引入过多 persistence/resume 细节、fixture 或跨层搭桥，则必须立刻拆出最小 compatibility test，不能把所有兼容性断言硬塞进 agent test。

断言：
1. 当前 persistence / resume payload 类型不新增字段
2. 当前 persistence / resume 字段集合不因 deferred tail 改变
3. deferred tail state 仅存在于 `AutonomousProjectReviewAgent` 的运行期局部对象中

- [ ] **Step 4: 跑定向测试**

Run:
```powershell
mvn -q "-Dtest=AutonomousProjectReviewAgentTest" test
```

Expected:
- 先失败，暴露当前只有 contain，没有 tail pass

### Task 4: 实现 agent-private runtime-only deferred tail pass

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`

- [ ] **Step 1: 在 agent 内增加最小 deferred carrier**

要求：
1. 只表达失败 chunk 尾扫再消费所需状态
2. 不引入新的通用 orchestration state machine
3. 默认是 agent-private runtime-only state，不进入 persistence / resume

- [ ] **Step 2: containable failure 后把失败 chunk 放入 deferred tail**

要求：
1. 保留 `issueBacklog`
2. 保留 `processTrail`
3. 失败 chunk 不再首次失败后永久失联

- [ ] **Step 3: 在 endgame 中补一次有限 tail pass**

规则：
1. 普通 pending 优先
2. 普通 pending 清空后，只允许一次有限 tail pass
3. 达到单 chunk 上限后，不再无限重试
4. 不允许 tail 中再生成新的 tail 分层或新优先级规则

- [ ] **Step 4: 跑定向测试**

Run:
```powershell
mvn -q "-Dtest=AutonomousProjectReviewAgentTest" test
```

Expected:
- PASS

### Task 5: 锁定 console visualization 新结构

**Files:**
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ConsoleReviewRuntimeVisualizerTest.java`

- [ ] **Step 1: 新增 TRACE round / action / result / repair / failure 输出测试**

至少覆盖：
1. round start
2. decision / action
3. tool result
4. repair trigger
5. containable failure
6. round finish

- [ ] **Step 2: 新增 COMPACT 输出测试**

断言：
1. COMPACT 保留项目级和关键 action / result
2. 不展开 repair / proposal / round 子块细节

- [ ] **Step 3: 新增 OFF 完全静默测试**

断言：
1. OFF 不输出 runtime trace
2. OFF 不保留 legacy 单行噪音

- [ ] **Step 4: 跑定向测试**

Run:
```powershell
mvn -q "-Dtest=ConsoleReviewRuntimeVisualizerTest" test
```

Expected:
- 先失败，暴露当前仍是 legacy line 风格

### Task 6: 落地 visualizer 事件与 console mode

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewRuntimeVisualizer.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ConsoleReviewRuntimeVisualizer.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/ReviewAgentRuntimeProperties.java`
- Modify: `src/main/java/io/quillloom/infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfiguration.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/ConsoleReviewRuntimeVisualizerTest.java`
- Test: `src/test/java/io/quillloom/application/postdraft/review/AutonomousProjectReviewAgentTest.java`

- [ ] **Step 1: 在 visualizer 接口增加最小新事件**

事件集：
1. `focusRoundStarted`
2. `decisionProduced`
3. `repairTriggered`
4. `localReplanTriggered`
5. `containableFailureCaptured`
6. `focusRoundFinished`

- [ ] **Step 2: 在 agent 层补事件发射**

要求：
1. round 语义严格对齐 `currentFocusRound`
2. repair / proposal / local replan 都附着在当前 round
3. 下层 service 不持有 visualizer 依赖

- [ ] **Step 3: 在 console visualizer 中实现 `OFF / COMPACT / TRACE`**

要求：
1. 默认 wiring 使用 `COMPACT`
2. `TRACE` 输出块状结构
3. `OFF` 不输出 runtime trace
4. 不再无差别重复 legacy 单行事件流

- [ ] **Step 4: 跑定向测试**

Run:
```powershell
mvn -q "-Dtest=ConsoleReviewRuntimeVisualizerTest,AutonomousProjectReviewAgentTest" test
```

Expected:
- PASS

### Task 7: 锁定并实现中文摘要语言策略

**Files:**
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/PromptBackedNextStepDecisionProviderTest.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`

- [ ] **Step 1: 在 prompt tests 中增加语言策略断言**

锁定范围：
1. `reason`
2. `questionForHuman`
3. repair / proposal justification

默认跟随当前译文目标语言；当前项目中文优先。

- [ ] **Step 2: 在 system / investigation / special-path prompt 中补中文规则**

要求：
1. 只约束人类可见摘要字段
2. 不声称控制内部 chain-of-thought
3. 明确保留原文引用、术语原文、tool 名称、JSON 键名

- [ ] **Step 3: 跑定向测试**

Run:
```powershell
mvn -q "-Dtest=ReviewPromptBuilderTest,PromptBackedNextStepDecisionProviderTest" test
```

Expected:
- PASS

### Task 8: 最小相关回归验证

**Files:**
- Verify only

- [ ] **Step 1: 跑最小相关回归**

Run:
```powershell
mvn -q "-Dtest=ReviewToolExecutorGuardrailTest,AutonomousProjectReviewAgentTest,ConsoleReviewRuntimeVisualizerTest,PromptBackedNextStepDecisionProviderTest,ReviewPromptBuilderTest" test
```

Expected:
- PASS

- [ ] **Step 2: 跑一次 review-agent 烟测**

Run:
```powershell
mvn -q "-Dtest=PostDraftReviewAgentEndToEndSmokeTest" test
```

Expected:
1. 至少覆盖 duplicate-read recovery 或相近 smoke 路径
2. 日志可区分 repair / proposal / containable failure

---

## 5. 计划自检

1. 相邻读取空转：Task 1-2
2. canonical view：
   - visualizer 消费链路：Task 1-2
   - prompt 注入链路：Task 1-2
3. containable failure 再消费：Task 3-4
4. console visualization：Task 5-6
5. 中文摘要语言策略：Task 7
6. persistence / resume 兼容性：
   - 明确不改 schema
   - 通过 Task 3 的契约断言锁住

---

## 6. 风险提示

1. deferred tail pass 是本轮唯一会触碰 agent endgame 队列语义的改动，必须保持窄范围。
2. canonical view 与底层 runtime 语义并存，测试必须明确区分“展示顺序 / prompt 注入顺序”和“模型内部顺序”。
3. visualizer 改造必须避免把 provider / executor 反向耦合到展示层。
4. 若现有测试体系无法可信证明 persistence / resume payload 未变，必须补最小 compatibility test，不能用主观说明替代。
