# 2026-04-21 `record_confirmed_terms` Two-Phase Routing Correction Design

## 1. 一句话结论

当前实现的主要问题不是 `entries:{}`，也不是 proposal DTO 本身，而是 **把 `record_confirmed_terms` 两阶段错误地前移成了 agent 决策行为变更**。

正确边界应该是：

1. 先走普通 `generateNextToolDecision` 调查链
2. 需要时先 `read_confirmed_terms`
3. 只有当普通 next-step 已经决定要调用 `record_confirmed_terms` 时，才进入 proposal phase
4. proposal 只负责 `record_confirmed_terms` 的参数成形，不负责提前改写 agent 的工具选择顺序

---

## 2. 现状问题

### 2.1 日志表现

当前真实运行日志已经证明：

1. agent 在 `chunk-1` 上连续调用 `generateRecordConfirmedTermsProposal`
2. proposal 每次都产出同一对：`Patrick Modiano -> 帕特里克·莫迪亚诺`
3. tool executor 每次都成功执行 `record_confirmed_terms`
4. 但 agent 没有继续调查，也没有转入 `complete_working_set`
5. 同一 focus 被反复登记同一个 term pair

这说明当前问题已经从：

- “`entries:{}` 导致结构化失败”

转成：

- “proposal 路径抢在正常调查顺序之前执行，导致同一 pair 被重复登记”

### 2.2 当前错误行为是什么

当前 provider 逻辑是：

1. `decide()` 一开始先尝试 `decideFromRecordConfirmedTermsProposal(session)`
2. 只要本地 `stablePairSignals` 非空，就直接进入 proposal
3. proposal 返回 `RECORD_CONFIRMED_TERMS` 后，立刻本地组装成 `record_confirmed_terms`
4. 普通 next-step 决策被绕开

这会导致：

1. 还没走正常调查链，就抢先进入登记路径
2. 即使全局确认流程还没完成，也会尝试直接 `record_confirmed_terms`
3. 一旦当前 focus 的 `confirmedTermUpdates` / `confirmedTerm=` 持续可见，每轮都可能再次进 proposal

---

## 3. 为什么这是边界错误

### 3.1 两阶段本来只该影响什么

`record_confirmed_terms` 两阶段设计的原始目标是：

1. 解决模型知道 pair，但不稳定写入 `arguments.entries`
2. 把“pair 提取”与“map 组装”拆开
3. 让本地代码负责最终 `entries` 组装

也就是说，两阶段本来只该影响：

- `record_confirmed_terms` 的参数成形

而不该影响：

- agent 什么时候先调查
- agent 什么时候先查全局 confirmed terms
- agent 的普通 next-step 决策顺序

### 3.2 当前实现为什么越界

当前实现把 proposal 提前到了普通 next-step 之前，相当于把两阶段从：

- “工具参数成形机制”

变成了：

- “工具选择前置分流机制”

这就改了 agent 的行为顺序。

换句话说：

1. 原来：发现术语迹象 -> 普通决策 -> 可能先查 `read_confirmed_terms` -> 再决定是否登记
2. 现在：发现稳定 pair 迹象 -> 直接 proposal -> 直接 `record_confirmed_terms`

这已经不是“只影响工具参数”了，而是 **把 agent 的调查行为改掉了**。

---

## 4. 正确的业务顺序

对于“看起来像术语”的情况，正确顺序应当写死为：

1. 先走普通 `generateNextToolDecision`
2. 如果还缺项目级确认，优先 `read_confirmed_terms`
3. 拿到全局结果后，再判断：
   - 是否已存在项目级 confirmed term
   - 当前译文是否符合项目级译名
   - 当前 workingSet 是否具备登记新 pair 的充分前提
4. 只有当普通 next-step 已经明确决定：
   - 这一步就是 `record_confirmed_terms`
   才进入 proposal phase

因此：

- proposal 不是“看到稳定 pair 就进”
- proposal 必须是“普通 next-step 已经决定要登记时才进”

---

## 5. 当前代码中的错误落点

### 5.1 provider 把 proposal 放到了 `decide()` 最前面

文件：`src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`

关键位置：

- `decide(...)` 先调用 `decideFromRecordConfirmedTermsProposal(session)`
- 然后才回退 `decideWithStandardToolDecision(session)`

含义：proposal 成了“前置分流器”。

### 5.2 proposal 的进入条件是本地 pair 信号，而不是普通 next-step 决策结果

当前进入条件本质上是：

- 只要 `collectStablePairSignals(session)` 非空，就进 proposal

这会让：

- `confirmedTermUpdates`
- `confirmedTerm=`
- 已读 workingSet 里的 pair 证据

直接触发登记路径，而不是先进入正常调查链。

### 5.3 executor 并没有负责阻止这个越界行为

executor 当前只做：

1. basis 校验
2. termWriter 执行
3. evidence 回写

它并不负责：

- 决定是否该先调查
- 决定是否该先查全局 confirmed terms

因此当前重复登记的根因不在 executor，而在 provider 的 proposal 入口时机。

---

## 6. 主推荐修复方案

### 6.1 方案原则

把 proposal 从“前置决策分流器”降级回“`record_confirmed_terms` 的参数成形器”。

### 6.2 具体行为改法

将 `PromptBackedNextStepDecisionProvider.decide(...)` 改为：

1. 先走普通 `decideWithStandardToolDecision(session)`
2. 如果普通决策结果的 `toolName != record_confirmed_terms`
   - 直接返回普通决策结果
3. 如果普通决策结果的 `toolName == record_confirmed_terms`
   - 再进入 proposal phase
4. proposal phase 只负责：
   - 生成 `RecordConfirmedTermsProposal`
   - 本地组装 `arguments.entries`
5. 如果 proposal 返回 `NOT_APPLICABLE`
   - 不能重新抢回普通 next-step 再绕一圈
   - 应进入受控失败 / repair / replan 语义，避免同一轮 decision cycle 打转

### 6.3 修复后的职责边界

修复后边界应恢复为：

- 普通 next-step 负责：
  - 是否先调查
  - 是否先查 `read_confirmed_terms`
  - 是否真的该调用 `record_confirmed_terms`

- proposal 负责：
  - 既然已经确定要调用 `record_confirmed_terms`
  - 那么如何稳定提取 pair
  - 如何稳定组装 `arguments.entries`

---

## 7. 为什么主推荐方案是对的

### 7.1 它恢复了正确调查顺序

它保证：

1. “看起来像术语”不会直接跳到登记
2. 普通 next-step 仍有机会先选 `read_confirmed_terms`
3. proposal 不再抢在调查链前面

### 7.2 它不推翻两阶段根治方向

它保留：

1. proposal DTO
2. 本地 assembly
3. `entries` 不再依赖模型直接稳定成形

所以不是回滚两阶段，而是把两阶段放回正确位置。

### 7.3 它避免继续扩大行为改动面

这次修复只需要收缩 provider 的调用时机，不需要：

1. 推翻 runtime containment
2. 推翻 proposal DTO
3. 改 executor 协议
4. 重做 tool registry

---

## 8. 不推荐方案

### 8.1 不推荐继续补“重复登记去重”作为主修复

仅仅补：

- “已登记 pair 不再重复登记”

只能止住当前重复现象，但不能修复更根本的问题：

- proposal 抢在正常调查顺序之前执行

所以它只能当辅助防线，不能当主修复。

### 8.2 不推荐让 executor 承担“先调查还是先登记”的职责

executor 是工具执行层，不该反过来决定：

- agent 当前该先查全局还是先写回

这会继续放大职责边界。

### 8.3 不推荐把 proposal 再扩展成通用前置治理器

这会把当前问题从：

- `record_confirmed_terms` 的专项参数成形

扩成：

- 全工具通用 pre-routing framework

这与本轮边界相冲突。

---

## 9. 测试修正方向

至少补这些回归：

1. **术语迹象存在，但普通 next-step 应先选 `read_confirmed_terms` 时**
   - provider 不应先进入 proposal

2. **普通 next-step 选中的不是 `record_confirmed_terms` 时**
   - proposal 不应触发

3. **普通 next-step 已选中 `record_confirmed_terms` 时**
   - proposal 才触发
   - 并且由本地组装 `entries`

4. **已成功登记一个 pair 后**
   - 普通 next-step 不应再因为 proposal 抢跑而重复登记同一 pair

5. **proposal 返回 `NOT_APPLICABLE` 时**
   - 不能在同一轮 provider 内形成 decision loop

---

## 10. 最小落地建议

下一轮代码修正应只做：

1. 改 `PromptBackedNextStepDecisionProvider.decide(...)` 的调用顺序
2. 把 proposal 触发条件从“本地 stablePairSignals 非空”改成“普通 next-step 已选择 `record_confirmed_terms`”
3. 补 provider 定向测试
4. 保持 runtime containment 不动
5. 保持 DTO / client / assembly 机制不动

---

## 11. 最终判断

这次暴露的问题不是：

- 两阶段思想错了

而是：

- 两阶段落点放错了

正确落点应当是：

- **只改 `record_confirmed_terms` 的参数成形**
- **不改 agent 发现问题后“先调查、再决定是否登记”的主行为顺序**

因此，修复方向不是回滚两阶段，而是把 proposal 从“前置行为改写器”收回成“被 `record_confirmed_terms` 调用后才生效的参数成形器”。
