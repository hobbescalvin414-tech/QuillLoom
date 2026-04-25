# 2026-04-17 review agent 终端可视化最小实现计划

## 目标

为方向 C 的项目级 smoke 运行补一层只读终端进度输出，方便跑全链路时实时观察 agent 在做什么。

本次只做最小版本，不做交互式 TUI，不引入 TypeScript，不改变 agent 决策逻辑。

## 范围

1. 只覆盖 `PostDraftProjectReviewAgentSmokeTest` 这条项目级 smoke 链路。
2. 输出固定前缀的关键进度行，内容只包含：
   - project 开始
   - focus anchor 选择
   - 当前 working set
   - 工具调用
   - 工具结果
   - chunk 完成
   - human review / project 完成
3. 默认正式服务不打印；未显式接入可视化时走 no-op。

## 设计

1. 新增一个极薄的 `ReviewRuntimeVisualizer` 接口，默认 no-op。
2. 新增 `ConsoleReviewRuntimeVisualizer`，负责把关键运行节点打印到终端。
3. `AutonomousProjectReviewAgent` 在 loop 的关键节点调用 visualizer。
4. `PostDraftReviewAgentService` 新增一个可注入 visualizer 的构造入口。
5. `PostDraftProjectReviewAgentSmokeTest` 显式接入 `ConsoleReviewRuntimeVisualizer`。

## 验证

1. 跑相关单测，确保服务层与自主 loop 编译通过。
2. 手动执行 project smoke 时，应能在终端看到带 `[review-agent]` 前缀的进度输出。
