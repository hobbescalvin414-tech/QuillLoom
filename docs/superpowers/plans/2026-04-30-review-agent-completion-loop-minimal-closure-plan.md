# Review Agent Completion Loop Minimal Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the newly exposed review-agent completion loop by clarifying prompt-only completion semantics after `KEEP`, without changing executor or runtime behavior.

**Architecture:** Keep the fix limited to prompt surfaces that define cross-stage completion semantics and tool-specific completion guidance. Do not add new tools, do not change runtime state transitions, and do not broaden the fix into term lookup or adjacent-read policy changes.

**Tech Stack:** Java 21, JUnit 5, Maven

---

### Task 1: Lock the minimal completion semantics with tests

**Files:**
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolRegistryTest.java`

- [ ] **Step 1: Add a failing system-prompt test for KEEP-to-completion closure**

Add assertions that the system prompt explicitly says `KEEP` with closed evidence should move to completion instead of repeating `evaluate_focus`.

- [ ] **Step 2: Add a failing tool-guidance test for anchor-only completion**

Add assertions that `complete_working_set` guidance says adjacent chunks read only as context evidence do not become mandatory submission targets in the current round.

- [ ] **Step 3: Run the targeted tests to verify they fail**

Run: `mvn -q "-Dtest=ReviewPromptBuilderTest,ReviewToolRegistryTest" test`

Expected: FAIL because the new completion-closure wording is not present yet.

### Task 2: Apply the minimal prompt-only completion fix

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`

- [ ] **Step 1: Update the system prompt completion discipline**

Add one English rule that says once the current focus is closed and `KEEP` is supported with no unresolved high-priority issue, the agent should move to completion for the current focus instead of repeating `evaluate_focus`.

- [ ] **Step 2: Update `complete_working_set` guidance**

Clarify in English that adjacent chunks brought into the working set only as context evidence do not automatically become required submission targets for the current round, and that the current focus anchor may be completed on its own once closed.

- [ ] **Step 3: Keep scope narrow**

Do not modify `InvestigationPromptBuilder`, `PromptBackedStrategyEvaluationService`, `WorkingSetCompletionHandler`, `ReviewToolExecutor`, or any schema / validator / runtime code.

### Task 3: Verify and sync handoff

**Files:**
- Modify: `docs/handoff.md`

- [ ] **Step 1: Run the targeted regression tests**

Run: `mvn -q "-Dtest=ReviewPromptBuilderTest,ReviewToolRegistryTest" test`

Expected: PASS.

- [ ] **Step 2: Record the design conclusion in handoff**

Add a short note that prompt-only completion closure now treats adjacent-read chunks as context evidence unless explicitly completed, and that `KEEP` should advance to current-focus completion rather than repeated evaluation.

- [ ] **Step 3: Report exact verification evidence**

In the final handoff, include the exact Maven command run and whether it passed or failed.
