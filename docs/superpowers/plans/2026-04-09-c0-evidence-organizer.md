# C0 Search Evidence Organizer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 C0 中增加一个受控的 LLM 搜索证据整理器，让 SearXNG 命中先被整理为翻译可用的知识卡素材，再进入既有知识卡链路。

**Architecture:** 新增 `KnowledgeSearchResultOrganizer` 抽象。规则式 `KnowledgeSearchResultCondenser` 继续作为默认 organizer；新增 `LlmKnowledgeSearchResultOrganizer` 及其 `PromptRenderer / Client / Parser / Configuration`。`NetworkBackedKnowledgeSearchTool` 改为依赖 organizer，不再直接依赖 condenser。

**Tech Stack:** Spring Boot, LangChain4j OpenAI-compatible chat model, Jackson, JUnit 5

---