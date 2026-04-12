# 当前架构

本文档只描述当前真实架构，不记录历史方案。

## 主链

1. Agent A 负责全书级分析与 coarse block 规划。
2. Agent B 在 coarse block 内做 chunk 切分与结构化标注。
3. Agent C0 基于 chunk 标注做知识增强，并沉淀项目级知识库。
4. 装配层为当前 chunk 选择首批知识卡。
5. Agent D 执行 chunk 翻译；如知识不足，只允许做本地知识库补卡。
6. 后续产物进入草稿汇总与可读 trace。

## 稳定边界

1. A/B 不回头重做。
2. 不退回大 orchestrator。
3. C0 负责主检索与项目级知识沉淀。
4. 装配层只负责筛卡，不负责联网搜索与建库。
5. D 默认消费装配层首批知识卡。
6. D 的 loop 只允许本地补卡，不承担主检索职责。
7. D 不联网。
8. `TranslationTaskInput` 仍是稳定执行输入契约，不是巨型状态对象。

## A 的当前状态

1. `globalConstraints` 已有 prompt 约束与执行层双重治理。
2. 非法全局约束会在进入下游前被过滤并写入 trace。

## C0 的当前状态

1. 外部知识卡链路保留。
2. 内生人物卡分支已挂到 C0 主链，与外部知识卡共存。
3. 内生卡当前是最小实现，重点承载书内证据、alias 状态和首见 chunk。
4. alias 归一当前默认保守，不做激进合并。

## D 的当前状态

1. D 仍是两轮执行模式。
2. 第一轮产出可用草稿与问题。
3. 第二轮开始按 issue 清单定向修订，不应退化成重翻。
4. 当前已接入正文边界污染检测与目标语言纯度检测。
5. glossary 正文合规与 revision 收口仍在继续完善。

## 产物与观测

1. workflow trace 会输出 machine-readable JSON 与人可读文本。
2. preprocess trace 已能看到 rejected global constraints。
3. translation trace 已能看到 draft、input、knowledge 等阶段产物。
