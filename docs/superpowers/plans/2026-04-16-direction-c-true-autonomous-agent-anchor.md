# [OUTDATED - 已被 2026-04-18-review-agent-direction-anchor.md 取代] QuillLoom Post-Draft Review Agent 方向 C：自主 Agent 升级设计锚定

> ⚠️ 本文档已被 [04-18 方向锚定文档](./2026-04-18-review-agent-direction-anchor.md) 取代。本文档中描述的 allowedActions / legacyFallback / FocusWorkingMemory / ProjectRollingMemory / CompletedChunkMemorySummary 等概念已全部消除，仅作历史参考。
>
> 本文档是方向 C（升级为真正自主 Agent）的设计锚定文件。后续所有实现工作严格以此为准，不再回头走"弱自治半受控"路线。
>
> Codex 做详细设计时，必须先阅读本文档，再在其基础上展开。

---

## 1. 目标定义

将 QuillLoom 的 post-draft review agent 从**当前半受控的"提示词枚举选择器"**升级为**真正自主的 agent**——即 agent 能够自主决定下一步动作（读什么、做什么），而不被预先枚举的有限动作集合所限制。

参考标杆：`E:\learnAgent\cc\claw-code` 的 agent 模式。

---

## 2. 当前真实问题（不是"审校能力不存在"，而是"自主能力没建起来"）

### 2.1 问题一：AllowedActionPlanner 把 LLM 的决策空间锁死了

[PostDraftReviewAllowedActionPlanner.java](file:///e:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAllowedActionPlanner.java) 基于 problemTypes + actionBudget 静态计算允许动作集合，然后把这个集合扔给 LLM，让 LLM "从中选一个"。

这不是自主 agent。真正的自主 agent 应该能自己判断"我需要读决策笔记"，而不是等系统告诉它"你可以读决策笔记"。

**锚定**：AllowedActionPlanner 的静态枚举过滤机制必须被拆除或降级为 guardrail（边界检查），不再是动作生成的唯一入口。

### 2.2 问题二：legacyFallback 分支造成双轨决策

`PromptBackedInvestigationDecisionProvider` 和 `PromptBackedEvaluationDecisionProvider` 都有 `legacyFallback=true` 时的启发式兜底逻辑。这两套路径并存，意味着系统行为不可预测——有端口注入时走 LLM，没注入时走手写启发式。

**锚定**：只保留 LLM 驱动的决策路径，消除所有 legacyFallback 分支。没有真实 LLM 端口时，显式报错，不静默走假动作。

### 2.3 问题三：self-check 是永远返回 passed=true 的 stub

[PostDraftReviewLoopRunner.java#L90-91](file:///e:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewLoopRunner.java#L90-91)：
```java
new PostDraftRevisionService(
    new PromptBackedRevisionDraftProvider(),
    (session, chunk, strategy, draft) -> new RevisionSelfCheckResult(true, "", List.of())
)
```
`selfCheckService` 第二个参数是一个 lambda，永远返回 `passed=true`。整个 revision self-check 逻辑是假的。

**锚定**：RevisionSelfCheckService 必须有真实的 LLM 驱动的 self-check 实现。Self-check 通过才输出正式译文；Self-check 失败先本地重试一次，再由 FocusHumanStopPolicy 决定是否 escalation。

### 2.4 问题四：动作类型是硬编码枚举，不是开放注册

[ReviewAgentActionType.java](file:///e:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/model/ReviewAgentActionType.java) 是 `enum`，所有可能动作在代码编写时就已经定死。LLM 只能在这些已定义选项内选择。

真正的自主 agent 应该有**开放的工具/动作注册机制**，agent 可以根据当前状态动态决定要执行什么，而不是从预设枚举中挑。

**锚定**：动作体系从硬编码 enum 演进为动态动作注册表。注册表本身可配置，但运行时由 agent 自主查询和选择。

---

## 3. 目标形态：真正自主 Agent 的关键特征

### 3.1 动作决策是开放的不是枚举选择

Agent 根据当前 session 状态（evidence、visited objects、action trail、memory）自行判断"下一步需要什么"，而不是从预设允许动作集合中挑一个。

实现路径可以是：
- LLM 直接生成下一步意图描述（如"我需要读前 3 个 chunk 的衔接段"），由执行层校验可行性后执行
- 或者用更结构化的方式：LLM 输出"我想执行 X，理由是 Y"，执行层做安全校验后执行

AllowedActionPlanner 保留为 guardrail（校验动作是否在安全边界内），不再是动作生成的唯一机制。

### 3.2 工具/动作注册是动态的不是枚举驱动

参考 claw-code 的 `PORTED_TOOLS` / `PORTED_COMMANDS` 机制：
- 工具列表集中注册，统一管理
- Agent 通过 prompt 路由匹配工具，而不是枚举硬编码
- 新增工具只需注册，不需要改 agent 核心代码

### 3.3 Session 状态是 agent 自己的运行态

参考 claw-code 的 `RuntimeSession` / `HistoryLog` / `TranscriptStore`：
- Session 包含当前 evidence、action trail、visited objects、memory
- 这些是 agent 运行时自己管理的状态，不是预先构造好扔给 LLM 的提示词参数
- Session 可以追加历史、累积 usage、压缩旧消息

### 3.4 Loop 是 agent 内部驱动的不被状态机枚举锁死

参考 claw-code 的 `QueryEnginePort.run_turn_loop()`：
- Loop 逻辑在 agent 内部，外部只触发 `submit_message`
- 每轮的结果（matched commands/tools、permission denials、stop_reason）由 agent 自己判断
- 循环终止由 agent 根据 stop_reason 决定，不被外部硬编码的轮次上限强制中断

---

## 4. 关键设计锚点

### 4.1 动作体系重构：从 Enum 演进到动态注册

**当前**：`ReviewAgentActionType` 是 `enum { READ_PREVIOUS_CHUNKS, READ_NEXT_CHUNKS, ... }`

**锚定**：
- 拆除 `ReviewAgentActionType` 的 enum 锁死机制
- 引入 `ReviewToolRegistry` 或类似的动态动作注册表
- 每个注册动作包含：动作名称、动作描述、执行条件（可空）、执行器引用
- LLM 输出的是动作名称字符串（不在编译期枚举），由 registry 解析和路由
- `PostDraftReviewAllowedActionPlanner` 降级为 guardrail：校验 LLM 输出的动作是否在安全注册表中，不在则拒绝

**后续 Codex 详细设计需要回答**：
- 动作注册表是代码配置还是外部可配置？
- 动作粒度：保持当前的"读chunk/读note/读knowledge card"级别，还是支持更细粒度的意图描述？
- 如何处理"LLM 输出一个注册表中不存在的动作名"？

### 4.2 Investigation 决策重构：从"枚举选一个"到"自主判断+生成"

**当前**：
```java
// LLM 被给定允许动作集合，从中选一个
Set<ReviewAgentActionType> allowedActions = allowedActionPlanner.plan(...);
ReviewAgentActionProposal proposal = provider.propose(session, chunk, allowedActions);
```

**锚定**：
- LLM 接收当前 session 的完整 evidence + action trail + visited objects + memory
- LLM 自主判断"我还需要什么"，输出意图描述（如"我需要读前后各 2 个 chunk 来确认衔接"）
- 执行层校验该意图是否在安全边界内（guardrail），通过则执行
- 执行结果追加到 session，更新 evidence 和 visited objects，继续下一轮判断

**后续 Codex 详细设计需要回答**：
- LLM 的输出格式：从结构化 JSON（固定字段）演进为什么？
- 如何处理 LLM 输出"我要联网搜索"这类越界意图？
- guardrail 的拒绝策略：直接报错还是给 LLM 重试机会？

### 4.3 Self-Check 链路：从 stub 到真实 LLM 驱动

**当前**：`selfCheckService` lambda 永远返回 `passed=true`

**锚定**：
- `RevisionSelfCheckPromptBuilder` 生成 self-check prompt：输入原文 + 初稿 + 修订版 + 证据
- LLM 判断修订版是否解决了问题、是否引入了新问题
- 输出结构化结果：`passed: boolean, stopReason: string, findings: List<string>`
- self-check 失败时，先本地重试一次（最多 1 次）
- 重试仍失败，再由 `FocusHumanStopPolicy` 决定是否 escalation to human review

**后续 Codex 详细设计需要回答**：
- self-check prompt 的核心判断标准是什么？
- 如何避免 self-check 本身成为另一个"假通过"？
- self-check 和 evaluation 的边界在哪里（是否合并）？

### 4.4 Memory 机制：真实自主 Agent 记忆体系（参照 claw-code）

> claw-code 的记忆体系是真正自主 agent 的核心基础设施。QuillLoom 的 memory 设计必须对标这套体系，以下逐层给出锚定，Codex 直接按此实现即可。

#### 4.4.1 claw-code 的三层记忆体系

claw-code 的记忆由三个独立组件构成，各自职责清晰：

**第一层：HistoryLog（结构化事件日志）**

源码：[history.py](file:///e:/learnAgent/cc/claw-code/src/history.py)

```python
@dataclass
class HistoryLog:
    events: list[HistoryEvent] = field(default_factory=list)

    def add(self, title: str, detail: str) -> None:
        self.events.append(HistoryEvent(title=title, detail=detail))

    def as_markdown(self) -> str: ...
```

用途：记录 agent 运行过程中的关键结构化事件（routing 结果、command/tool 执行结果、turn 结果等）。只追加，不淘汰，是审计轨迹。**不参与 prompt 构建**。

**第二层：TranscriptStore（对话 transcript + 压缩）**

源码：[transcript.py](file:///e:/learnAgent/cc/claw-code/src/transcript.py)

```python
@dataclass
class TranscriptStore:
    entries: list[str] = field(default_factory=list)
    flushed: bool = False

    def append(self, entry: str) -> None: ...
    def compact(self, keep_last: int = 10) -> None: ...   # 超出 keep_last 条时，保留最近 keep_last 条
    def replay(self) -> tuple[str, ...]: ...               # 返回所有未压缩的 entries
    def flush(self) -> None: ...                            # 标记已持久化
```

用途：保存 agent 与 LLM 的原始对话消息（prompt 本身）。当 `len(entries) > compact_after_turns`（默认值 12）时，触发压缩，只保留最近 10 条。`flush()` 后 `flushed=true`，表示已持久化到磁盘。**参与 prompt 构建**（ replay() 返回的内容作为 context 注入 LLM）。

**第三层：SessionStore（持久化存储）**

源码：[session_store.py](file:///e:/learnAgent/cc/claw-code/src/session_store.py)

```python
@dataclass(frozen=True)
class StoredSession:
    session_id: str
    messages: tuple[str, ...]
    input_tokens: int
    output_tokens: int

def save_session(session: StoredSession, directory: Path | None = None) -> Path: ...
def load_session(session_id: str, directory: Path | None = None) -> StoredSession: ...
```

用途：将完整 session 序列化为 JSON 文件（`.port_sessions/{session_id}.json`）。支持断点恢复：`from_saved_session(session_id)` 可以从磁盘加载历史 transcript 重建 QueryEnginePort。

#### 4.4.2 claw-code 的 token budget 控制

源码：[query_engine.py](file:///e:/learnAgent/cc/claw-code/src/query_engine.py)

```python
@dataclass(frozen=True)
class QueryEngineConfig:
    max_turns: int = 8
    max_budget_tokens: int = 2000
    compact_after_turns: int = 12
    structured_output: bool = False
    structured_retry_limit: int = 2
```

```python
def submit_message(self, prompt: str, ...):
    projected_usage = self.total_usage.add_turn(prompt, output)
    stop_reason = 'completed'
    if projected_usage.input_tokens + projected_usage.output_tokens > self.config.max_budget_tokens:
        stop_reason = 'max_budget_reached'
    self.mutable_messages.append(prompt)
    self.transcript_store.append(prompt)
    self.total_usage = projected_usage
    self.compact_messages_if_needed()          # 当 len > compact_after_turns 时压缩
```

Budget 控制逻辑：**每轮估算 token（用 split 近似），超过 `max_budget_tokens` 则 stop_reason 置为 `max_budget_reached`，不再继续**。这是 agent 自己判断是否停下来的机制，不是外部硬编码轮次。

#### 4.4.3 QuillLoom 端对标方案（锚定）

以下方案可直接交给 Codex 实现：

**Q1：ReviewAgentSession（替代现有 FocusWorkingMemory / ProjectRollingMemory）**

新建 `ReviewAgentSession` record，作为 agent 的统一记忆容器，内部组合三层记忆组件：

```java
public record ReviewAgentSession(
    String sessionId,                    // 对标 RuntimeSession.session_id
    // 三层记忆
    HistoryLog historyLog,               // 对标 HistoryLog：结构化事件，audit trail
    TranscriptStore transcriptStore,     // 对标 TranscriptStore：对话原始消息 + 压缩
    // 运行时状态
    UsageSummary totalUsage,             // 对标 UsageSummary：累计 token 估算
    ReviewAgentConfig config,            // 对标 QueryEngineConfig
    // focus 级上下文
    String currentFocusChunkId,
    EvidenceBundle evidence,              // 当前已收集的证据
    ActionTrail actionTrail              // 已执行动作列表
) {}
```

**Q2：HistoryLog 对标实现**

```java
public record HistoryLog(List<HistoryEvent> events) {
    public void add(String title, String detail) {
        events.add(new HistoryEvent(title, detail));
    }
    // as_markdown() 用于审计输出
}

public record HistoryEvent(String title, String detail) {}
```

用途：**记录 investigation/revision/self-check 的结构化事件**，不注入 LLM prompt，仅用于审计和可追溯性。每次 agent 做重要决策（决定读哪个 chunk、进入 revision、self-check 失败）时 `add()` 一条。

**Q3：TranscriptStore 对标实现**

```java
public record TranscriptStore(List<String> entries, boolean flushed) {
    public void append(String entry) { entries.add(entry); flushed = false; }
    public void compact(int keepLast) {
        if (entries.size() > keepLast) {
            entries.subList(0, entries.size() - keepLast).clear();
        }
    }
    public List<String> replay() { return List.copyOf(entries); }
    public void flush() { flushed = true; }
}
```

用途：**保存 LLM 对话原始消息**。Agent 每轮发送给 LLM 的 prompt 和收到的 response 都作为一条 entry 追加。当 `entries.size() > config.compactAfterTurns()` 时压缩，保留最近 `config.compactAfterTurns()` 条。`replay()` 返回的内容作为 context 注入下一轮 LLM prompt。

**Q4：SessionStore 对标实现**

```java
public interface SessionStore {
    void save(ReviewAgentSession session);                      // 序列化为 JSON 到本地文件
    Optional<ReviewAgentSession> load(String sessionId);        // 从文件恢复
}

public record StoredSession(
    String sessionId,
    List<String> messages,
    long inputTokens,
    long outputTokens
) {}
```

路径：`{workspace}/.quillloom_sessions/{sessionId}.json`。**用途**：支持 agent 断点恢复。Focus chunk 处理到一半时可以序列化当前 session，下次继续时从磁盘恢复。Session 粒度是 project 级（一个 projectId 一个 session），不是 chunk 级。

**Q5：UsageSummary + Budget 控制**

```java
public record UsageSummary(long inputTokens, long outputTokens) {
    public UsageSummary addTurn(String prompt, String output) {
        return new UsageSummary(
            inputTokens + estimateTokens(prompt),
            outputTokens + estimateTokens(output)
        );
    }
    public boolean exceeds(UsageBudget budget) {
        return (inputTokens + outputTokens) > budget.maxTokens();
    }
}

public record UsageBudget(long maxTokens) {}  // 从 config 注入
```

Budget 检查时机：**每轮 submit 结束后**。若 `usage.exceeds(budget)`，stop_reason 置为 `MAX_BUDGET_REACHED`，不再继续 loop。这是 agent 自己感知资源边界的方式，不是外部硬中断。

**Q6：compact 触发时机**

触发时机：**每轮 submit 结束后**，检查 `transcriptStore.entries().size() > config.compactAfterTurns()`，若超则 `transcriptStore.compact(config.compactAfterTurns())`。保留策略：**保留最近 N 条**（claw-code 默认 keep_last=10，compact_after_turns=12）。注意：compact 是对 transcript 压缩，HistoryLog 不压缩（HistoryLog 是事件日志，不是对话记录）。

**Q7：Prompt 注入了什么**

Agent 的 prompt 应该注入以下内容（由 Codex 设计具体 prompt 内容）：
- 当前 focus chunk 的 sourceText + translatedText + decisionNotes + transitionNote
- `transcriptStore.replay()` 的内容（即历史 LLM 对话）
- 当前 evidence（EvidenceBundle）
- `historyLog.as_markdown()`（审计轨迹，给 LLM 看自己的行动历史）
- 当前策略状态（investigation / revision / self-check 阶段）

**Q8：Memory 管理的边界约束**

- `ReviewAgentSession` 是 agent 的运行期对象，**不回写到 `PostDraftReviewPackage` / `ProjectKnowledgeBase`**
- Focus chunk 完成后，focus 级的 memory 压缩为 `ProjectChunkReviewOutcome` 存入项目级 session
- 项目级 session 持久化到 `SessionStore`，不持久化到 PostgreSQL（PostgreSQL 只存流水线产物）
- 当 agent 需要"读前文/读后文"时，从 `PostDraftReviewPackage.chunks` 按 sequence 查，不从 memory 查（memory 是行动历史，不是数据源副本）

**后续 Codex 详细设计需要回答**：
- Token 估算方式：claw-code 用 `len(prompt.split())` 近似 QuillLoom 用什么？（建议同样用 token 估算库，或暂时用字符数 / 3 近似）
- Focus 完成后 memory snapshot 的具体格式是什么？（Codex 定义 `ProjectChunkReviewOutcome` 的结构）
- `ReviewAgentConfig` 的默认值：maxTurns、maxBudgetTokens、compactAfterTurns 具体设多少？

### 4.5 Loop 控制：从外部状态机到 agent 内部 stop_reason

**当前**：[PostDraftReviewLoopRunner](file:///e:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewLoopRunner.java) 是一个外部 Java 状态机，按 `switch(runtime.state())` 推进 loop，LLM 无权改变状态转换路径。

**锚定**：
- Loop 驱动主体从 Java 状态机演进为 agent 自身（参考 claw-code 的 `QueryEnginePort.run_turn_loop`）
- 外部只负责：初始化 session、调用 agent 处理、接收最终结果
- Stop reason 由 agent 自己判断（如 `max_turns_reached`、`human_review_required`、`project_completed`）
- 不再用硬编码的 `INITIALIZING -> SELECTING_FOCUS -> ...` 状态机枚举锁死 agent 的决策路径

**后续 Codex 详细设计需要回答**：
- Java 状态机是否完全拆除，还是保留作为 guardrail 层？
- Loop 的外部接口是什么（仍是 `runProject(...)` 还是演进为别的）？
- 如何处理 agent 进入无限循环的情况？

---

## 5. 与现有实现的关系

### 5.1 保留的资产

以下现有实现经过验证，是有效资产，继续复用：

| 资产 | 保留理由 |
|------|---------|
| `PostDraftReviewPackage` + `ProjectKnowledgeBase` 读取链 | 正式数据源，不改动 |
| `PostDraftReviewSession` 的 record 结构 | Immutable session 模型保留，演进其内容 |
| `ProjectReviewRuntimeSession` | 项目级运行时边界保留 |
| `FocusHumanStopPolicy` | 真实的 escalation 策略，继续使用 |
| `PostDraftRevisionService` 的 revision draft 生成 | 实际执行翻译修订，保留 |
| `HumanReviewRequest` / `HumanReviewResolution` | 人审接口语义不变 |
| Prompt builders（`InvestigationPromptBuilder` 等）| Prompt 框架保留，prompt 内容会随架构演进调整 |

### 5.2 需要根本性改造的

| 改造对象 | 改造原因 |
|---------|---------|
| `PostDraftReviewAllowedActionPlanner` | 静态枚举过滤 -> 动态 guardrail |
| `PromptBackedInvestigationDecisionProvider` | 枚举选一个 -> 自主意图生成 |
| `PromptBackedEvaluationDecisionProvider` | 枚举选一个 -> 自主意图生成 |
| `ReviewAgentActionType` enum | 硬编码枚举 -> 动态动作注册表 |
| `PostDraftReviewLoopRunner` 的 switch-case 状态机 | 外部强制推进 -> agent 内部自主 loop |
| Self-check lambda stub | 永远 passed -> 真实 LLM self-check |
| `FocusWorkingMemory` / `ProjectRollingMemory` / `CompletedChunkMemorySummary` | 旧 memory 模型 -> 替换为三层记忆体系（ReviewAgentSession + HistoryLog + TranscriptStore + SessionStore） |

### 5.3 需要消除的

| 消除对象 | 消除原因 |
|---------|---------|
| 所有 `legacyFallback` 分支 | 双轨决策，不可靠 |
| Self-check stub lambda | 假通过，破坏 self-check 语义 |
| 硬编码 `maxLoopRounds` 作为强制中断机制 | 不应由轮次硬中断，应由 agent 自己判断 |
| 旧的 FocusWorkingMemory / ProjectRollingMemory | 替换为 ReviewAgentSession 三层记忆体系 |

---

## 6. 架构边界约束（继续遵守）

1. **不回退大 orchestrator**：仍是单一 agent，不引入多 agent 协调层
2. **不回退 A/B/C0**：主翻译工作流不受影响
3. **D 不联网**：autonomy 工作范围仍在本地 chunk + 上下文 + knowledge base
4. **不把运行期状态塞回稳定领域对象**：Memory 仍是运行期对象，不回写 `PostDraftReviewPackage` / `ProjectKnowledgeBase`
5. **不破坏 `confirmed/candidate/alias/knowledge card` 边界**：autonomy 只读不写这些稳定契约
6. **当前系统仍是受控流水线中的一个自治节点，不是自治 agent 社会**：不引入 agent 间通信、agent 生命周期管理等上层复杂度

---

## 8. 受控流水线产物持久化方案（Agent 数据基座）

> 本节描述受控流水线结束时落库的 `PostDraftReviewPackage`，这是 post-draft review agent 启动时的唯一数据入口。后续 Codex 设计 agent 的读数据逻辑时，必须以本节为准，不以前面已删除的旧文档为准。

### 8.1 持久化时机

在 `NovelTranslationWorkflowService.runDraftWorkflow()` 末尾，当 A/B/C0/D 全链跑完后，调用 `savePostDraftReviewPackage(state, projectMemory)` 同步写入 `ql_post_draft_review_package` 表。Agent 启动时通过 `RepositoryBackedPostDraftReviewAgentReader` 从同一张表读取。

### 8.2 持久化表结构

表名：`ql_post_draft_review_package`

| 数据库列名 | JSON 字段 | 类型 | 说明 |
|-----------|-----------|------|------|
| `project_id` | — | `TEXT` | 主键 |
| `package_version` | — | `TEXT` | 固定值 `v1` |
| `source_language` | — | `TEXT` | |
| `target_language` | — | `TEXT` | |
| `source_document_digest` | — | `TEXT` | 整个源文档的 SHA-256（按 chunk 顺序拼接） |
| `created_at` | — | `TIMESTAMP` | 组装时间 |
| `chunks_json` | `chunks` | `JSONB` | 全量 chunk 记录列表 |
| `block_indexes_json` | `blockIndexes` | `JSONB` | 粗分块索引列表 |
| `effective_confirmed_terms_json` | `termState.effectiveConfirmedTerms` | `JSONB` | `Map<String, String>` |
| `effective_candidate_terms_json` | `termState.effectiveCandidateTerms` | `JSONB` | `List<TranslationCandidateUpdate>` |
| `glossary_snapshot_json` | `glossarySnapshot` | `JSONB` | `DraftStageGlobalGlossary` |
| `alias_snapshot_json` | `aliasSnapshot` | `JSONB` | `GlobalAliasConsistencyTable` |
| `merged_draft_text` | `mergedDraftText` | `TEXT` | 初稿合并全文 |

### 8.3 各字段数据来源

#### 8.3.1 `chunks` — `List<PostDraftChunkRecord>`

来源：[PostDraftReviewPackageAssembler.assemble()](file:///e:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/assembler/PostDraftReviewPackageAssembler.java#L61-79) 按 `PreprocessDossier.chunkAnnotations` 顺序逐 chunk 组装。每个 `PostDraftChunkRecord` 字段来源：

| 字段 | 来源 |
|------|------|
| `chunkId` | `ChunkAnnotation.chunk().chunkId()` |
| `sequence` | `ChunkAnnotation.chunk().sequence()` |
| `blockId` | `ChunkAnnotation.chunk().coarseBlockId()` |
| `sourceText` | `ChunkAnnotation.chunk().sourceText()` |
| `translatedText` | `ChunkTranslationDraft.translatedText()`（D 的输出） |
| `translatorCommentary` | `ChunkTranslationDraft.translatorCommentary()` |
| `decisionNotes` | `ChunkTranslationDraft.decisionNotes()` — D 在翻译过程中记录的未决问题/风险点 |
| `confirmedTermUpdates` | `ChunkTranslationDraft.confirmedTermUpdates()` — D 认为已足够稳定的术语更新 |
| `candidateUpdates` | `ChunkTranslationDraft.candidateUpdates()` — D 认为仍需 review 的候选译名 |
| `transitionNote` | `ChunkTranslationDraft.transitionNote()` — 衔接提示（前后段连接描述 + 是否建议边界调整） |

#### 8.3.2 `blockIndexes` — `List<PostDraftBlockIndex>`

来源：[PostDraftReviewPackageAssembler.buildBlockIndexes()](file:///e:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/assembler/PostDraftReviewPackageAssembler.java#L124-139)。按 `PreprocessDossier.globalAnalysis().coarseChunkPlan().blocks()` 顺序，每块记录 `blockId`、`summary`、该块下所有 `chunkId` 列表。**注意**：`blockId` 与 `PostDraftChunkRecord.blockId` 是一对多关系。

#### 8.3.3 `termState` — `PostDraftTermState`

来源：[PostDraftReviewPackageAssembler.buildTermState()](file:///e:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/assembler/PostDraftReviewPackageAssembler.java#L99-122)。

- `effectiveConfirmedTerms`：从 `ProjectMemorySnapshot.confirmedTerms()` 初始化，再按各 `ChunkTranslationDraft.confirmedTermUpdates()` 追加（`putIfAbsent` 保留先到者）
- `effectiveCandidateTerms`：从 `ProjectMemorySnapshot.candidateTermUpdates()` 初始化，再按各 `ChunkTranslationDraft.candidateUpdates()` 追加（`putIfAbsent` 保留先到者）

#### 8.3.4 `glossarySnapshot` — `DraftStageGlobalGlossary`

来源：[PostDraftReviewPackageAssembler.buildGlossarySnapshot()](file:///e:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/assembler/PostDraftReviewPackageAssembler.java#L141-170)。**不是从知识库读取**，而是根据 `termState` 在组装时**重新构建**：
- `hardEntries`：`effectiveConfirmedTerms` 每条转为一个 `GlossaryEntry`（`Strength=HARD`, `SourceKind=CONFIRMED_TERM`）
- `softEntries`：`effectiveCandidateTerms` 每条转为一个 `GlossaryEntry`（`Strength=SOFT`, `SourceKind=CANDIDATE_TERM`）

#### 8.3.5 `aliasSnapshot` — `GlobalAliasConsistencyTable`

来源：[PostDraftReviewPackageAssembler.buildAliasSnapshot()](file:///e:/projects/QuillLoom/src/main/java/io/quillloom/application/postdraft/assembler/PostDraftReviewPackageAssembler.java#L173-213)。从两处聚合：
- `PreprocessDossier.chunkAnnotations` 中的 `PersonAliasHint`（每 chunk 的人名别名线索）
- `ProjectKnowledgeBase` 中的 `KnowledgeCard`（已沉淀的知识卡里的别名信息）

每条 alias cluster 记录：表面形式列表、规范化名称、状态（`OBSERVED` / `SUSPECTED_ALIAS`）、置信度、证据来源。

#### 8.3.6 `mergedDraftText` — `String`

来源：`DraftCompilation.mergedDraft()`（D-chain 的拼接阶段产物）。如果 `DraftCompilation` 为 null 则为空字符串。

### 8.4 Agent 只读不写

以上所有字段在 agent 启动时由 `RepositoryBackedPostDraftReviewAgentReader` 一次性加载，作为 `PostDraftReviewSession` 的初始化输入。Agent 对这些数据只有**读**权限，不写回 `ql_post_draft_review_package` 表。Agent 的修订结果（`ProjectChunkReviewOutcome` / `HumanReviewRequest`）有单独的写回路径，不在本持久化方案范围内。

---

## 9. Codex 后续详细设计指南

本文档是锚定，不是完整规格。后续 Codex 推进详细设计时，需要在本文档的约束下展开，并补充：

1. **动作注册表设计**：具体如何实现动态动作注册，接口什么样子
2. **LLM 输出格式设计**：从结构化 JSON 演进到什么格式，parser 怎么处理
3. **Guardrail 设计**：具体校验逻辑是什么，拒绝后如何反馈给 agent 重试
4. **Loop 外部接口**：演进后的入口还是 `runProject(...)` 吗，签名变不变
5. **Memory 压缩算法**：具体什么时候压、压什么、产物是什么
6. **Self-check prompt 设计**：具体判断标准、prompt 结构

---

## 10. 相关文档

- 项目现状：[docs/current-architecture.md](./current-architecture.md)
- Workspace 规则：[AGENTS.md](../AGENTS.md)
- claw-code 参考：`E:\learnAgent\cc\claw-code`
