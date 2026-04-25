# Review Agent 上下文与记忆策略诊断文档

重申一下agent的产品定位，这对于prompt的重新编排非常重要。也就是这个agent的职责和功能。这个agent作为文学翻译专家处理前面产生的小说翻译初稿和其他有关数据，主要工作为： 1 审核译文，对照原文和译文，参考流水线产出的其他数据，给初稿找问题，找出错译 漏译 保留原文未译 翻译明显不符合逻辑的问题。2对于全文需要保持一致的人名地名代号术语，检查其是否存在于现有译名表内（通过工具调用），若出现，核对是否一致，若没有，调用工具补充译名表。3.调用工具阅读上下相邻或多个chunk，检查各chunk衔接是否良好，若不好，则调用工具修改，若可以，则调用工具提交chunk，提交必须包括本轮的focuschunk，表示本轮任务完成，也可以一并提交其他已经阅读到的，确认没问题的chunk。

然而目前项目存在非常严重的问题：





## 一、结论先行

当前 Review Agent 的问题，不只是 prompt 冗长，而是三层一起存在偏差：

1. **workingSet 语义和 prompt 注入策略不一致**
   - 运行时语义上，workingSet 表示“当前 focus 已读入、与判断相关的 chunk 集合”。
   - 但 prompt 注入上，只有 anchor 往往是高保真；其他通过 `read_previous_chunks` / `read_next_chunks` 读入的 chunk 常被降级为摘要。

2. **文本型上下文被错误当成摘要型记忆处理**
   - transcript / evidence summary 适合做工具去重、repair、阶段提示。
   - 但 continuity judgment、reference resolution、narration flow、局部修订协调依赖的是 chunk 全文，不是摘要。

3. **prompt 建立在失真的上下文之上**
   - system prompt 虽然要求“短句、承接句、上下文依赖句应读邻接 chunk”。
   - 但当模型实际拿到的只是邻接 chunk 摘要时，这条规则在推理层并没有真正落地。

一句话概括：**当前 agent 把应当全文保真的局部篇章上下文，错误地当成了可摘要化记忆来处理。**

---

## 二、当前上下文与记忆策略

### 2.1 当前系统的上下文核心单位

当前 Review Agent 不是以“整个项目全文”为上下文单位，而是以 **当前 focus session** 为单位。

一个 focus session 主要由这些东西构成：

1. 当前 anchor chunk
2. 当前 workingSet
3. 当前 session 累积出的 evidence
4. 当前 session 的 transcript
5. 当前 session 的 local failures / diagnostics
6. 当前 focus 的 tool traces

所以它是一个**局部上下文策略**，不是全项目全量上下文策略。

### 2.2 anchor chunk 如何进入上下文

当前 focus 被选中后，系统会把 anchor chunk 的完整快照作为 seed evidence 注入 session。

这份快照通常包含：

1. `sourceText`
2. `translatedText`
3. `translatorCommentary`
4. `decisionNotes`
5. `confirmedTermUpdates`
6. `transitionNote`

因此，**anchor 通常是高保真进入 prompt 的。**

### 2.3 workingSet 其他 chunk 如何进入上下文

当前 workingSet 里除了 anchor 之外的 chunk，主要通过调查类工具进入：

1. `read_previous_chunks`
2. `read_next_chunks`
3. `expand_block_context`

但它们进入 prompt 时，当前实现通常不是以“全文快照”方式进入，而是以**摘要化 evidence** 形式进入。

也就是说：

1. agent 运行时知道 workingSet 已经扩大了
2. **但 prompt 注入层并不保证这些新读入 chunk 的原文和译文全文持续保留**，这个问题非常严重。

这就是当前上下文策略的第一个核心偏差。

### 2.4 当前“记忆”实际上分成哪几类

当前 session 中，真正被 prompt 消费的“记忆”大致可分为四类：

1. **Evidence Summaries**
   - 当前已经读到或查到的证据摘要
   - 包括 anchor 快照、邻接 chunk 摘要、confirmed term 命中/未命中、知识卡摘要等

2. **Evidence Gaps**
   - 当前还缺什么证据

3. **Recent Transcript**
   - 最近几轮工具调用、阶段提示、局部 replan 提示

4. **Recent Local Failures**
   - 最近的工具 rejection 或结构化输出失败原因

此外还有一类半隐式记忆：

5. **Tool Trace / Sentinel Memory**
   - 例如：
     - `confirmedTerm=A->B`
     - `confirmedTermLookupMiss=[...]`
     - `revision_ready_for_completion`
     - `project_ready_for_completion`
     - `selfCheckPassed=true`
   - 这类文本哨兵既被存入 transcript/evidence，也会被 provider 逻辑二次消费

### 2.5 当前不同 LLM 阶段分别会拿到什么

#### A. Next-Step 决策

Next-step 是当前上下文**最全的一层之一**。它会拿到：

1. 全局 system rules
2. 当前 focus / state / strategy / workingSet
3. 当前 evidence summaries
4. evidence gaps
5. recent transcript
6. recent local failures

但关键问题是：

1. anchor 往往以高保真快照形式出现
2. **workingSet 其他 chunk 大多已经被摘要化**

所以 Next-step 对“anchor 很清楚”，对“已读邻接 chunk”往往只是概览，而不是全文。

#### B. `record_confirmed_terms` proposal 子阶段

这一层继承的是 Next-step investigation prompt，再追加：

1. stable pair signals
2. proposal 子任务约束

因此它吃到的上下文仍主要是：

1. anchor 高保真信息
2. workingSet 其他 chunk 的摘要化信息
3. transcript / failure memory

#### C. `evaluate_focus`

这一层主要吃的是“评估压缩包”，包括：

1. key evidence
2. conflicting evidence
3. evidence gaps
4. candidate strategies

它不以“全文对读”为主要结构，而是以“证据摘要判断”作为主结构。这个位置明显不合理。在evaluate_focus时，却不让agent读到在评估的上下文和阅读到的其他前后chunk的上下文，使得agent丧失了自己思考的能力，而是被前面流水线产出的可能可靠性不太高的东西左右。

#### D. `draft_revision`

这一层主要吃：

1. 当前 chunk 的原文
2. 当前 chunk 的当前译文
3. confirmedTermUpdates
4. target strategy
5. key rationales
6. residual risks

也就是说，revision 是**当前 chunk 局部高保真**，但并不天然携带整个 workingSet 全文。这里依然非常严重，修改时竟然只能看到要修改的那一个chunk，agent的工作能力约等于瞎子。此外，agent在设计时允许focus为18的时候修改在workingset里的其他chunk，所以与这个有关的所有部分，agent必须拥有充足的上下文！

#### E. `revision self-check`

这一层主要吃：

1. 当前 chunk 的原文
2. 当前 chunk 的旧译文
3. 新 draft
4. confirmedTermUpdates
5. previous findings

它同样是当前 chunk 局部高保真，而不是 workingSet 全文自检。问题同上，都看不到上下chunk，review个鸡毛

#### F. repair / replan

repair 通常不是抛弃原 prompt，而是：

1. 保留原阶段 prompt
2. 再追加错误输出、错误原因、修复约束

所以 repair 阶段的上下文并没有完全丢失；但它的注意力中心会转移到“修这次输出错误”，而不是重新完整理解场景。不理解场景怎么调用工具，搞笑！

---

## 三、当前策略何时会出现信息提供不足

### 3.1 continuity / handoff / reference resolution 判断

这是当前最明确的信息不足场景。三个问题：agent会被prompt误导，对于阅读上下文有非常大的惰性。第二：阅读上下文时工具也不返回足够的信息，读了等于没读。三：上下文策略非常垃圾，不给agent充足信息。

如果 agent 要判断：

1. 上下 chunk 之间的译文衔接是否自然
2. 指代链是否接得上
3. 回答句是否真正在回应上一句
4. 时间顺序 / 空间切换是否顺
5. 当前句子是否依赖上一句才能成立

那么摘要不够，原因如下：

1. 代词、称谓、照应目标会在摘要中丢失
2. 句法承接、语气承接会在摘要中丢失
3. 事件揭示顺序和信息节奏会在摘要中丢失
4. 很多衔接问题不是“大意错误”，而是“接得不对”

所以：**只要是篇章级局部衔接判断，就必须看到相关 chunk 的原文和译文全文。**

### 3.2 term conflict 之后的修订决策

当前 prompt 虽然能告诉模型：

1. 查到了 project-level confirmed term
2. 当前译文如果冲突，不要直接 complete

但如果后续 evaluation / revision 仍只看到压缩后的 evidence，而没有看到相关文本上下文，它就容易停在“知道冲突存在”，却无法稳定推进到：

1. compare current translation
2. choose revision strategy
3. draft revised translation

信息不足点不一定在“术语本身”，而在“术语冲突与上下文协同修订”的判断素材不足。

### 3.3 completion / close-out 决策

这个问题就是处理完了agent不知道要完成项目的处理。

当前 completion 相关 prompt 更擅长表达：

1. 什么情况下不能完成
2. 什么情况下 prefer `complete_working_set`
3. 什么情况下 prefer `complete_project`

但它较弱地表达了：

1. 当前是否还真的存在未解决文本问题
2. 已读上下文是否已经足够
3. 当前 workingSet 是否真的已被完整验证

一旦这些判断建立在摘要化上下文上，就容易出现：

1. 其实该 finish，却继续调查
2. 其实该 `complete_project`，却重复 `complete_working_set`

### 3.4 revision 质量与局部篇章一致性

当前 revision 生成和 self-check 主要是当前 chunk 视角。

这对于“单 chunk 术语改正”够用，但对于下列任务不够：

1. 为了衔接邻接 chunk 而微调句法或语气
2. 保证当前修订后和上一 chunk / 下一 chunk 的叙述承接自然
3. 判断当前 revision 是否引入新的局部断裂

也就是说，**当 revision 目标本身包含 continuity 修复时，仅看当前 chunk 不够。**

### 	3.5长期记忆不足

 但没有强意义上的跨 focus 推理记忆：作为一个翻译家，agent不能完全遗忘自己翻译过的东西，在审校后文的时候，必须对前文有所印象和把握。目前它“每次围绕一个 chunk 重开一个局部回合”，而不是“在一个持续增长的长程 agent memory 上工作”，这很不好，需要重新设计。

上一个 focus 读过哪些 chunk 的全文
上一个 focus 形成了哪些局部篇章判断

上一个 focus 的 workingSet 文本视野
这些不会自然继承到下一个 focus



---

## 四、当前 prompt 的具体问题

### 4.1 prompt 过多强调限制，过少表达动作树

当前prompt不能完成指示agent工作的任务。

当前 system prompt 很擅长讲：

1. 什么不能做
2. 工具参数约束是什么
3. 各类 hard block 是什么

但它不够擅长讲：

1. 在什么条件下优先 `read_previous_chunks` / `read_next_chunks`
2. term conflict 后的标准动作顺序是什么
3. 没有更多证据可读时，应默认如何收尾

于是模型拿到的是一份“限制清单”，不是一棵“动作树”。

### 4.2 prompt 建立在已经失真的上下文输入上

哪怕 system prompt 再强调：

1. 短句要检查上下文
2. continuity 要读邻接 chunk
3. 不要轻易 KEEP

只要真正注入的是：

1. anchor 全文
2. 邻接 chunk 摘要

那这些规则在推理层仍然无法稳定成立。

### 4.3 repair 机制会进一步把注意力压向“修输出”

repair 机制本身是必要的，但它会让模型更聚焦：

1. 修 JSON
2. 修参数
3. 修当前错误面

如果原始上下文本来就不充分，那么 repair 不会补齐“文本证据不足”这个问题；它只会在已有不足上下文下反复修输出形式。

---

## 五、现有设计中真正应当保留的部分

当前策略并不是全部要推倒。以下部分仍然有价值：

### 5.1 状态型记忆

这些记忆适合继续保留为摘要/哨兵形式：

1. transcript
2. local failures
3. tool rejection reasons
4. confirmed term lookup hit/miss
5. `revision_ready_for_completion`
6. `project_ready_for_completion`
7. `selfCheckPassed=true`

这类信息适合做：

1. 工具去重
2. repair
3. replan
4. close-out hint



---

## 

### 6.1 首要原则：区分“状态型记忆”和“文本型上下文”

后续设计必须明确分层：

1. **状态型记忆**
   - transcript
   - local failures
   - sentinel text
   - tool history / replan hints
   - 这类可以继续摘要化

2. **文本型上下文**
   - anchor chunk 全文
   - 邻接阅读引入的 chunk 全文
   - continuity / revision 需要引用的 chunk 全文
   - 这类不能被过早压缩成摘要

### 6.2 新的 workingSet 保真原则

建议明确立为硬规则：

1. anchor chunk 的原文和译文必须保留在当前 focus 的后续 LLM 上下文中
2. 任何通过 `read_previous_chunks` / `read_next_chunks` 读入 workingSet 的 chunk，其 `sourceText` 和 `translatedText` 必须持续保留在当前 focus 的后续 LLM 上下文中
3. 在当前 focus 结束前，不允许把这些 chunk 降级成只剩摘要用于 continuity judgment

这条原则的意义是：

1. 让“读取上下文”成为真实的阅读，而不是形式上的取证
2. 保证后续 next-step / evaluate / revision / self-check 真正继承已读文本

### 6.3 先改上下文供给，再改 prompt 文案

后续修改顺序建议是：

1. 先修 workingSet 全文供给策略
2. 再修 prompt 结构
3. 最后再调 repair 策略和 token 控制

不要反过来只先改 system prompt 文案。否则只是在失真的上下文上继续润色话术。

### 6.4 prompt 应重构为“动作树优先”

在上下文供给修正后，next-step prompt 应从“限制清单”改成“动作树优先”，至少显式表达：

1. 何时优先阅读相邻 chunk
2. 何时 term conflict 应推进到 evaluate/revision
3. 何时证据已足够，应默认完成 workingSet
4. 何时项目已 close-out，只应 `complete_project`

### 6.5 不建议动的东西

后续即便重写 prompt，也应谨慎保留当前这些 memory sentinel：

1. `confirmedTerm=...`
2. `confirmedTermLookupMiss=...`
3. `revision_ready_for_completion`
4. `project_ready_for_completion`
5. `selfCheckPassed=true`

原因不是它们完美，而是当前已有很多行为在消费这些哨兵文本。粗暴改名会同时破坏：

1. provider 的二次解析
2. repair 提示
3. close-out 提示

### 6.6 第一阶段建议只修最关键缺口

建议先限定第一阶段只做一条明确修正：

1. `read_previous_chunks` / `read_next_chunks` 引入 workingSet 的 chunk，必须以全文级上下文保留在当前 focus 生命周期内

原因：

1. 这是当前 continuity 判断失败的最根本缺口
2. 边界清晰
3. 不会一次性把所有证据类型都升级成全文注入
4. 能作为后续 prompt 重构的稳固基础

---

## 七、审查结论

当前 Review Agent 的上下文/记忆/prompt 问题，不应继续被表述成“prompt 太长”这么简单。

更准确的设计诊断应是：

1. 当前系统已经建立了局部 focus + workingSet 的运行时语义。
2. 但 prompt 注入策略没有忠实兑现这个 workingSet 语义。
3. 尤其是通过相邻阅读工具引入的 chunk，本应作为 continuity evidence 的全文上下文，却被降级成摘要。
4. 因此，后续很多失败不是模型不听话，而是它在不完整文本上下文上被要求做篇章级判断。

后续修复方向应明确为：

1. 先恢复文本型上下文的保真性
2. 再重构 prompt 的动作树
3. 最后再讨论 repair、token 和进一步的 memory 优化

在这个顺序之前，单独讨论“system prompt 再怎么缩写更好”意义有限。
