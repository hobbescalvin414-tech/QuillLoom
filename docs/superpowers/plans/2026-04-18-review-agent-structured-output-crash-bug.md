# Bug: 结构化输出修复失败杀死 Spring 上下文

## 发现时间

2026-04-18 23:13

## 严重度

🔴 高 — 127 chunk 真实冒烟测试中触发，导致整个 Spring Boot 应用崩溃，无法继续运行。

## 现象

127 chunk 真实冒烟测试跑到第 6 个 chunk 时，LLM 返回了 `record_confirmed_terms` 工具调用，但 `entries` 参数格式不合法。结构化输出修复（repair）也失败后，`LlmStructuredOutputException` 穿透了 agent 主循环，一路传播到 Spring 上下文，导致应用启动失败退出。

## 复现路径

```
chunk-6 → read_confirmed_terms(成功)
         → LLM 下一步决策 → record_confirmed_terms(entries=???)
                                        ↓
                            契约校验失败：invalid_argument:entries
                                        ↓
                            LlmStructuredOutputException 抛出
                                        ↓
                            PromptBackedNextStepDecisionProvider.decide() 内部
                            repair 尝试（MAX_REPAIR_ATTEMPTS=1）→ 也失败
                                        ↓
                            LlmStructuredOutputException 穿透 decide()
                                        ↓
                            AutonomousProjectReviewAgent 主循环无 catch
                                        ↓
                            PostDraftReviewAgentService.reviewProject() 无 catch
                                        ↓
                            CommandLineRunner.run() 无 catch
                                        ↓
                            Spring Boot 应用崩溃退出
```

## 错误日志

```
io.quillloom.application.postdraft.review.port.out.LlmStructuredOutputException:
Review agent invalid structured tool decision: invalid_argument:entries
    at io.quillloom.infrastructure.postdraft.review.OpenAiCompatibleReviewAgentStructuredGenerationClient.generateNextToolDecision(...)
    at io.quillloom.infrastructure.postdraft.review.RetryingReviewAgentStructuredGenerationPort.executeWithRetry(...)
    ...
```

## 根因分析

### 直接原因

`AutonomousProjectReviewAgent` 的 `while(true)` 主循环（L105-149）没有 catch 任何异常。当 `nextStepDecisionProvider.decide(focusSession)` 抛出 `LlmStructuredOutputException` 时，异常直接穿透。

### 设计缺陷

1. **repair 失败后没有降级路径**：`PromptBackedNextStepDecisionProvider.decide()` 在 repair 失败后直接抛异常，没有"跳过本轮、继续下一轮"的机制。

2. **主循环没有异常保护**：`AutonomousProjectReviewAgent.run()` 假设 `decide()` 永远不会抛异常，但 LLM 的行为不可控——它可能返回任何格式的输出。

3. **异常类型不匹配**：`LlmStructuredOutputException` 是"LLM 输出格式错误"，不是"基础设施瞬时故障"（`LlmTransientException`），不应该杀死应用。它应该被当作一次"无效决策"，计入 NO_PROGRESS 阈值。

### 为什么 scripted e2e 没发现

scripted e2e 用 `ScriptedReviewAgentGenerationPort` 控制 LLM 输出，永远不会产生格式错误。只有真实 LLM 调用才会触发此 bug。

## 修复方案

### 方案 A：主循环加异常保护（推荐，快速）

在 `AutonomousProjectReviewAgent.run()` 的主循环中加 catch，将未预期异常转化为 FAILED 状态：

```java
while (true) {
    if (current.status() != ProjectReviewStatus.ACTIVE) { ... }
    if (hasWallClockTimedOut(startedAtNanos)) { ... }
    if (current.currentFocusSession().isEmpty()) { ... }

    PostDraftReviewSession focusSession = current.currentFocusSession().orElseThrow();
    ReviewToolDecision decision;
    try {
        decision = nextStepDecisionProvider.decide(focusSession);
    } catch (LlmStructuredOutputException ex) {
        // 结构化输出修复失败 → 计入同类失败，可能触发 NO_PROGRESS
        current = current.appendProcessTrail(
            "structured_output_repair_failed: " + ex.getMessage());
        // 继续循环，让 LLM 重新决策
        // 如果连续失败达到阈值 → failNoProgress
        continue;
    }
    // ... 执行工具
}
```

**优点**：不杀死应用，agent 可以继续运行
**缺点**：需要把 `LlmStructuredOutputException` 的失败计入 `FocusAutonomyState` 的同类失败计数，否则可能无限循环

### 方案 B：service 层加兜底 catch（更保守）

在 `PostDraftReviewAgentService.reviewProject()` 外层加 catch，任何未预期异常都设 FAILED：

```java
public PostDraftReviewAgentResult reviewProject(StartProjectPostDraftReviewAgentCommand command) {
    ...
    try {
        ProjectReviewRuntimeSession finalRuntime = autonomousAgent.run(runtime, command.operatorNote());
        ...
    } catch (Exception ex) {
        ProjectReviewRuntimeSession failed = runtime.failProject(ex.getMessage());
        persistenceHook.afterTransition(runtime, failed);
        return projectOutputAssembler.assemble(failed);
    }
}
```

**优点**：最简单，1 分钟改完，保证不杀应用
**缺点**：不区分异常类型，所有异常都导致 FAILED；不利用 NO_PROGRESS 机制

### 建议实施顺序

1. **先做方案 B**（5 分钟）— 保证跑 127 chunk 时不再崩溃
2. **再做方案 A**（1-2 小时）— 把结构化输出修复失败纳入 NO_PROGRESS 机制，让 agent 有机会自恢复

## 附加观察

### LLM 行为问题

从日志看，LLM 在 chunk-4 上反复调用 `read_confirmed_terms`（4 次），发现了专名冲突（"孔代咖啡馆" vs "勒孔代咖啡馆"），但 strategy=KEEP 不允许 `draft_revision`，导致 `draft_revision` 被 guardrail 拒绝。最终 LLM 在 chunk-6 上选择了 `record_confirmed_terms`，但 `entries` 参数格式错误。

这暴露了两个 LLM prompt 层面的问题：
1. **strategy=KEEP 时 LLM 仍然想修订**：LLM 发现了专名冲突，但当前 strategy 是 KEEP，不允许 `draft_revision`。LLM 试图绕过限制，先 `record_confirmed_terms` 再 `draft_revision`，但 `record_confirmed_terms` 的参数格式错误。
2. **`read_confirmed_terms` 重复调用**：4 次调用返回相同结果，LLM 没有利用已有信息做决策。

这些是 prompt 优化问题，不属于 bug 范畴，但值得后续关注。

## 影响范围

- 127 chunk 真实冒烟测试被阻塞
- 任何真实 LLM 调用场景都可能触发此 bug
- scripted e2e 不会触发（mock 环境）

## 临时规避

在修复前，可以通过增加 `MAX_REPAIR_ATTEMPTS`（从 1 增加到 2-3）降低 repair 失败概率，但这不是根本解决方案。
