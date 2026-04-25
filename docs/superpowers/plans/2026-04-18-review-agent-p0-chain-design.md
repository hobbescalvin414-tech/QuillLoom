# 2026-04-18 Review Agent P0 链路打通设计稿

## 1. 目标

本稿只解决一件事：在**不做 Spring 运行装配**的前提下，先把 Review Agent 的 Java 调用链打通，使其具备以下能力：

1. `complete_working_set` 完成后，修订译文增量写回 `ql_post_draft_review_package.chunks_json`
2. 项目完成后，合并后的译文写回 `ql_post_draft_review_package.merged_draft_text`
3. agent 主动调用 `request_human_review` 时，完整 `ProjectReviewRuntimeSession` 落盘到本地 JSON
4. 后续喂入人工自由文本后，从该 JSON 反序列化恢复，agent 自动继续

本稿**不做**以下内容：

1. 不做 Spring Bean 全链装配
2. 不做 D-12 崩溃恢复 checkpoint
3. 不做 D-08 工具系统解耦
4. 不做 D-09 结构化压缩摘要重写
5. 不做 LLM 重试/退避
6. 不把 `NO_PROGRESS` 伪装成 HITL

---

## 2. 当前代码真实状态

基于代码核对，当前链路存在以下真实缺口：

### 2.1 D-10：修订译文未落库

`PassThroughPostDraftReviewAgentWriter` 只透传结果，不写数据库。  
`WorkingSetCompletionHandler` 已能产出 `ProjectChunkReviewOutcome.finalTranslation`，但没有任何稳定落库路径。

### 2.2 D-11：Session 持久化仍是精简快照

`StoredReviewSession` 当前只保存：

1. `state`
2. `currentFocusChunkId`
3. `pendingChunkIds`
4. `completedChunkIds`
5. `backlogIssueIds`
6. transcript / history / processTrail

它丢失了：

1. `currentFocusSession`
2. 完整 `completedChunkOutcomes`
3. `humanReviewRequest`
4. `currentFocusRound`
5. 运行中 focus 的 transcript / evidence / toolTraces / diagnostics

因此当前 `FileReviewSessionStore.load()` 读回来的数据不足以恢复运行。

### 2.3 D-07：HITL 语义与恢复链路未打通

`HumanInTheLoopGateway.submit()` 当前只是原样返回 request。  
`PostDraftReviewAgentService` 没有正式的 `resumeProject(...)` 入口。  
`AutonomousProjectReviewAgent.resume(...)` 虽已存在，但上层没有接通完整恢复链路。

### 2.4 `NO_PROGRESS` 当前仍被当作可讨论的人机边界

当前代码里 `ReviewToolExecutor.appendAudit(...)` 会在连续 3 次同类 guardrail 拒绝后走 `failNoProgress(...)`。  
结合本轮用户确认，本稿将其定义为**bug 暴露路径**，不是正常 HITL。

---

## 3. 本轮锚定

本稿遵守以下新锚定：

1. `WAITING_HUMAN` 是唯一允许持久化和恢复的正常暂停点
2. 人工输入是证据，不是命令
3. `HumanInTheLoopGateway` 只负责**提交/发布**人工求助请求，不负责等待回答，也不负责恢复
4. 恢复入口在 `PostDraftReviewAgentService.resumeProject(...)`
5. `NO_PROGRESS`、结构化输出不可修复、网络错误、未预期异常都不持久化、不恢复

---

## 4. 方案对比

### 方案 A：把持久化副作用塞进 `ReviewToolExecutor`

做法：

1. `complete_working_set` 中直接写库
2. `request_human_review` 中直接写 session 文件
3. `complete_project` 中直接写 `merged_draft_text`

优点：

1. 改动路径短
2. 很快能拼出结果

缺点：

1. `ReviewToolExecutor` 同时承担工具分发、领域推进、外部副作用，职责过重
2. 后续做 D-08 解耦时还得拆一次
3. session 落盘和 DB 写回都被绑死在工具实现细节里

结论：不采用。

### 方案 B：只在 `PostDraftReviewAgentService` 结束时统一写库/落盘

做法：

1. `reviewProject(...)` 返回后统一检查结果
2. 再决定是否写库或落盘

优点：

1. service 层容易理解
2. 不侵入 loop

缺点：

1. 看不到 loop 中间状态跃迁
2. 无法精确处理“每完成一个 working set 就写回 chunk”
3. 无法抽象“进入 `WAITING_HUMAN` 就落盘”这种运行中暂停点

结论：不采用。

### 方案 C：新增 Runtime Persistence Hook

做法：

1. 在 `AutonomousProjectReviewAgent.run()` 中观察每轮 `previousRuntime -> currentRuntime`
2. 把“写库 / 落 session / 清 session”统一放到一个运行时副作用边界里
3. hook 不参与决策，只消费状态跃迁

优点：

1. 把领域推进与外部副作用分开
2. D-10、D-11、D-07 通过同一边界收口
3. 后续做 D-08 时无需再回头拆持久化逻辑
4. 明确表达“agent loop 是主逻辑，hook 是副作用边界”

缺点：

1. 需要引入新概念并同步修正文档
2. 需要在 loop 中增加状态跃迁观察点

结论：采用。

---

## 5. 选定方案总览

采用**方案 C：Runtime Persistence Hook**。

核心结构如下：

1. `AutonomousProjectReviewAgent` 负责 agent loop、自主决策、工具执行
2. `ProjectReviewRuntimePersistenceHook` 负责观察运行态跃迁后的外部副作用
3. `HumanInTheLoopGateway` 只负责提交人工求助请求
4. `ReviewSessionStore` 只负责在 `WAITING_HUMAN` 正常暂停点保存/加载完整 runtime
5. `PostgresPostDraftReviewAgentWriter` 只负责稳定产物写回：
   - chunk 级 `finalTranslation`
   - project 级 `mergedDraftText`

---

## 6. 组件拆解

### 6.1 新增组件

#### 1. `ProjectReviewRuntimePersistenceHook`

建议路径：

`src/main/java/io/quillloom/application/postdraft/review/service/ProjectReviewRuntimePersistenceHook.java`

职责：

1. 识别新增 `ProjectChunkReviewOutcome`
2. 识别 `ACTIVE -> WAITING_HUMAN`
3. 识别 `ACTIVE/WAITING_HUMAN -> COMPLETED`
4. 触发对应外部副作用

接口建议：

```java
public interface ProjectReviewRuntimePersistenceHook {
    static ProjectReviewRuntimePersistenceHook noop() { ... }

    void afterTransition(ProjectReviewRuntimeSession previousRuntime,
                         ProjectReviewRuntimeSession currentRuntime);
}
```

其中 `noop()` 是**静态工厂方法**，用于提供无副作用实现，不是实例方法。

#### 2. `DefaultProjectReviewRuntimePersistenceHook`

建议路径：

`src/main/java/io/quillloom/application/postdraft/review/service/DefaultProjectReviewRuntimePersistenceHook.java`

职责：

1. 通过对比 `previous.completedChunkOutcomes` 与 `current.completedChunkOutcomes` 找到新增 outcome
2. 调 writer 增量写回新增 chunk 译文
3. 当 `current.status == WAITING_HUMAN` 时调用 sessionStore.save(current)
4. 当 `current.status == COMPLETED` 时写回 `mergedDraftText` 并删除 session 文件

#### 3. `PostgresPostDraftReviewAgentWriter`

建议路径：

`src/main/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriter.java`

职责：

1. 用 `PostDraftReviewPackageRepository.load(projectId)` 读取当前 package
2. 仅更新稳定产物：
   - `chunks[].translatedText`
   - `mergedDraftText`
3. 重新保存 package

实现前提：

1. 当前按**单 agent / 单 projectId 串行运行**假设设计
2. 因此本轮允许采用 `load -> modify -> save` 的 read-modify-write 模式
3. 该实现不处理多进程并发更新同一 `projectId` 的冲突；后续若进入多实例部署，需要补版本号/乐观锁/行级锁方案

注意：

1. 不写 transcript
2. 不写 diagnostics
3. 不写 tool trace
4. 不写 `humanReviewRequest`
5. 代码中应补一行注释，明确说明当前 read-modify-write 依赖“单 agent / 单 projectId 串行运行”前提

#### 4. 完整 runtime 持久化版 `StoredReviewSession`

当前 record 需要重构为“完整 runtime 包装”。

建议结构：

```java
public record StoredReviewSession(
    String projectId,
    ProjectReviewRuntimeSession runtime
) {
    public static StoredReviewSession from(ProjectReviewRuntimeSession runtime) { ... }
}
```

这能让本地 JSON 成为完整恢复基座，而不是摘要。

---

### 6.2 重点修改组件

#### 1. `AutonomousProjectReviewAgent`

新增依赖：

1. `ProjectReviewRuntimePersistenceHook persistenceHook`

修改点：

1. 每轮 tool execute 前记录 `previousRuntime`
2. 每轮拿到 `currentRuntime` 后，调用 `persistenceHook.afterTransition(previousRuntime, currentRuntime)`
3. `resume(...)` 保持“人工输入是证据，不是命令”

#### 2. `PostDraftReviewAgentService`

新增能力：

1. `resumeProject(String projectId, String humanReviewNote)`

职责变化：

1. `review(...)` / `reviewProject(...)` 继续负责启动 agent
2. `submitHumanRequestIfPresent(...)` 只负责向外部提交求助请求，不再承担恢复语义
3. 恢复时从 `ReviewSessionStore.load(projectId)` 读取完整 runtime，再调用 `autonomousAgent.resume(...)`

#### 3. `ReviewSessionStore`

需要补充：

```java
void delete(String projectId);
```

原因：

1. 恢复完成后，如果不删旧 JSON，后续会产生脏恢复入口
2. 项目完成后也应清理 session 文件

#### 4. `FileReviewSessionStore`

职责变化：

1. `save(...)` 写完整 runtime JSON
2. `load(...)` 还原完整 runtime
3. `delete(...)` 删除对应文件

---

### 6.3 保持不动的组件

本轮不改以下边界：

1. `ReviewToolRegistry` 不新增新工具
2. `ReviewToolExecutor` 不新增新 switch case
3. `WorkingSetCompletionHandler` 继续负责产出 `ProjectChunkReviewOutcome`
4. `ProjectReviewOutputAssembler` 继续负责组装最终结果
5. `HumanInTheLoopGateway` 仍保持 `submit(request)` 这一最小接口形态，只改语义，不改成交互阻塞器

---

## 7. 数据流设计

### 7.1 正常运行：working set 完成后增量写库

```text
LLM 决策 -> complete_working_set
       -> WorkingSetCompletionHandler.complete(...)
       -> 生成新的 ProjectChunkReviewOutcome
       -> runtime 进入新的 ACTIVE 状态
       -> persistenceHook 对比 previous/current
       -> 提取新增 outcomes
       -> PostgresPostDraftReviewAgentWriter.updateChunkTranslations(...)
       -> 更新 ql_post_draft_review_package.chunks_json
```

关键点：

1. 只写新增 outcome，不重写全部 completed outcomes
2. 只更新 `translatedText`
3. 不动 package 中其他稳定资产

### 7.2 主动求助：进入 `WAITING_HUMAN` 后完整落盘

```text
LLM 决策 -> request_human_review
       -> runtime.status = WAITING_HUMAN
       -> service 调 HumanInTheLoopGateway.submit(request)
       -> persistenceHook 检测 WAITING_HUMAN
       -> ReviewSessionStore.save(currentRuntime)
       -> 返回 humanReviewRequest
       -> 进程可退出
```

关键点：

1. 求助请求的“对外发布”和“本地完整落盘”是两件独立动作
2. 正常暂停点只有 `WAITING_HUMAN`

### 7.3 恢复运行：人工输入作为证据

```text
外部喂入 projectId + humanReviewNote
    -> PostDraftReviewAgentService.resumeProject(...)
    -> sessionStore.load(projectId)
    -> autonomousAgent.resume(runtime, humanReviewNote)
    -> humanReviewNote 追加到 transcript/history
    -> agent 自主决定下一步
```

关键点：

1. 人工输入是证据，不是命令
2. 不让人指定 `continue_investigation` / `retranslate` 之类动作
3. agent 依然拥有最终决策权

### 7.4 项目完成：写 merged draft 并清理 session

```text
runtime.status = COMPLETED
    -> persistenceHook 检测完成
    -> writer.updateMergedDraftText(...)
    -> sessionStore.delete(projectId)
```

---

## 8. `HumanInTheLoopGateway` 角色重定义

本轮明确把 `HumanInTheLoopGateway` 定义为：

**人工求助请求发布口**

它负责：

1. 把 `HumanReviewRequest` 提交给外部系统
2. 允许基础实现继续原样返回 request，方便测试

它**不再负责**：

1. 等待人工回答
2. 存储人工回答
3. 触发 agent 恢复
4. 作为恢复入口

恢复入口改为：

`PostDraftReviewAgentService.resumeProject(projectId, humanReviewNote)`

---

## 9. `R-10` 改写方案

旧语义：

`NO_PROGRESS 直接 FAILED 而不请求人工帮助`

本轮确认后，改写为：

| 红线编号 | 禁止行为 | 验证方法 |
|---------|---------|---------|
| R-10 | 把 `NO_PROGRESS` 伪装成正常 HITL 暂停或可恢复人工求助路径 | 代码审查：`NO_PROGRESS` 必须保持 FAILED；不得生成 `WAITING_HUMAN` session；不得落可恢复 session 文件 |

原因：

1. `NO_PROGRESS` 的本质是程序 bug 暴露，不是正常求助
2. 人工输入无法修复 prompt/schema/guardrail 层 bug
3. 把 bug 暂停点伪装成人工边界，只会保存脏状态

---

## 10. 文件级改动草案

### 新增文件

1. `src/main/java/io/quillloom/application/postdraft/review/service/ProjectReviewRuntimePersistenceHook.java`
2. `src/main/java/io/quillloom/application/postdraft/review/service/DefaultProjectReviewRuntimePersistenceHook.java`
3. `src/main/java/io/quillloom/infrastructure/postdraft/review/PostgresPostDraftReviewAgentWriter.java`

### 修改文件

1. `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
2. `src/main/java/io/quillloom/application/postdraft/review/service/PostDraftReviewAgentService.java`
3. `src/main/java/io/quillloom/application/postdraft/review/port/out/ReviewSessionStore.java`
4. `src/main/java/io/quillloom/infrastructure/postdraft/review/FileReviewSessionStore.java`
5. `src/main/java/io/quillloom/application/postdraft/review/model/StoredReviewSession.java`
6. `src/main/java/io/quillloom/application/postdraft/review/port/out/HumanInTheLoopGateway.java`
7. `src/main/java/io/quillloom/infrastructure/postdraft/review/InMemoryHumanInTheLoopGateway.java`

### 本轮明确不改

1. `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
2. `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java` 的工具注册表结构
3. `src/main/java/io/quillloom/infrastructure/postdraft/review/PostDraftReviewAgentRuntimeConfiguration.java`

---

## 11. 验证设计

### 单元测试

1. `PostgresPostDraftReviewAgentWriter`
   - 新增 outcome 后只更新对应 chunk 的 `translatedText`
   - 项目完成后更新 `mergedDraftText`

2. `FileReviewSessionStore`
   - 能保存完整 `ProjectReviewRuntimeSession`
   - 能完整读取并恢复
   - `delete(projectId)` 生效

3. `PostDraftReviewAgentService`
   - `WAITING_HUMAN` 时会保留 session 文件
   - `resumeProject(...)` 能读取 JSON 并继续运行

4. `NO_PROGRESS`
   - 不生成 session 文件
   - 不进入 `WAITING_HUMAN`

### Smoke 范围

仅验证 Java 调用链：

1. 跑到 `request_human_review`
2. 本地生成完整 session JSON
3. 再喂人工自由文本
4. 能继续推进，直到再次暂停或完成

---

## 12. 红线自检

### R-06：不把运行期状态写回稳定领域对象

结果：**不触碰**

原因：

1. 写回数据库的只有 `finalTranslation` 和 `mergedDraftText`
2. transcript / diagnostics / tool trace / human note 只存在于本地 session JSON
3. 不把运行态写回 `ProjectKnowledgeBase`

### R-09：HITL 不能退化成排障式

结果：**不触碰**

原因：

1. 正常 HITL 仍由 agent 主动选择 `request_human_review`
2. 恢复入口消费的是正常暂停点 session，而不是 bug 排障

### R-10：`NO_PROGRESS` 不得伪装成正常 HITL

结果：**按新定义落地**

原因：

1. `NO_PROGRESS` 维持失败路径
2. 不落 session，不恢复

### R-11：不继续扩张 `ReviewToolExecutor` switch case

结果：**不触碰**

原因：

1. 本轮不新增工具
2. 只在 loop 边界增加 hook，不往 executor 塞新 case

### R-12：压缩摘要不能硬拼 4 字段

结果：**本轮不涉及**

### R-13：不允许 LLM 自由联网

结果：**本轮不涉及**

### R-14：不把 loop 临时状态塞进稳定输入契约

结果：**不触碰**

原因：

1. 人工回答只写 runtime transcript/history
2. 不进入 `TranslationTaskInput`

---

## 13. 文档同步要求

代码实施后，必须同步更新：

1. `docs/superpowers/plans/2026-04-18-review-agent-direction-anchor.md`
   - 改写 `R-10`
   - 修正 D-07 对 `HumanInTheLoopGateway` 和恢复入口的描述

2. `docs/superpowers/plans/2026-04-18-review-agent-e2e-run-gap-analysis.md`
   - 写明 persistence hook 是新的副作用边界
   - 写明只有 `WAITING_HUMAN` 正常落盘

3. `docs/handoff.md`
   - 同步 `HumanInTheLoopGateway` 角色变化
   - 同步 persistence hook 新概念
   - 同步 `NO_PROGRESS` 的新边界

---

## 14. 本稿结论

本轮采用：

1. **Runtime Persistence Hook**
2. **稳定产物写库**
3. **`WAITING_HUMAN` 完整落盘**
4. **service 级恢复入口**
5. **`NO_PROGRESS` 保持失败，不转 HITL**

这能在不做 Spring 装配、不做 D-12 的前提下，把 Review Agent 的 P0 链路先打通。
