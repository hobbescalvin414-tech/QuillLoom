# SearXNG Knowledge Search Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 C0 外部搜索从 Tavily 切换为 SearXNG + OkHttp，并保持现有 Gate、QueryPlanner、Condenser、NetworkBackedKnowledgeSearchTool 主链不变。

**Architecture:** 新增 `KnowledgeSearchSearxngProperties` 与 `SearxngKnowledgeSearchClient`，由 `KnowledgeSearchToolConfiguration` 装配 OkHttpClient 并在启用时接入 SearXNG。外部搜索结果仍统一映射为 `KnowledgeSearchHit`，后续建卡链路不变。

**Tech Stack:** Spring Boot, OkHttp3, JUnit 5, PowerShell

---