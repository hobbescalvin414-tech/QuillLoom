# Record Confirmed Terms Gate Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the incorrect `record_confirmed_terms` execution gate so the review agent can register stable working-set pairs that are not yet in the project-level confirmed-term table, while preserving the existing two-phase routing/proposal flow.

**Architecture:** Keep the two-phase `record_confirmed_terms` path intact: next-step routing stays separate from proposal and final executable assembly. Narrow executor failures to global confirmed-term table presence and argument/shape errors, then align prompt/tool guidance to the same semantics.

**Tech Stack:** Java 21, JUnit 5, Maven

---

### Task 1: Lock the new success/failure contract with tests

**Files:**
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolExecutorGuardrailTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewPromptBuilderTest.java`
- Modify: `src/test/java/io/quillloom/application/postdraft/review/ReviewToolRegistryTest.java`

- [ ] **Step 1: Add a failing executor test for lookup-miss stable-pair success**
- [ ] **Step 2: Add a failing executor test for existing global confirmed-term failure**
- [ ] **Step 3: Add prompt/guidance assertions for the new `record_confirmed_terms` semantics**
- [ ] **Step 4: Run targeted tests and confirm they fail before implementation**

### Task 2: Remove the incorrect runtime gate and keep two-phase compatibility

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolExecutor.java`

- [ ] **Step 1: Delete the `missing_working_set_confirmed_term_updates_support` gate**
- [ ] **Step 2: Delete the low-authority-only runtime rejection gate for `record_confirmed_terms`**
- [ ] **Step 3: Add a project-level confirmed-term presence rejection**
- [ ] **Step 4: Keep next-step -> proposal -> assembly flow unchanged**

### Task 3: Align prompt/tool semantics to the new executable contract

**Files:**
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/ReviewAgentSystemPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/prompt/InvestigationPromptBuilder.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/ReviewToolRegistry.java`
- Modify: `src/main/java/io/quillloom/application/postdraft/review/service/PromptBackedNextStepDecisionProvider.java`

- [ ] **Step 1: Rewrite system authority wording so lookup miss is not treated as an absolute prohibition**
- [ ] **Step 2: Keep the “stable pair required” rule, but remove wording that ties success to `confirmedTermUpdates`**
- [ ] **Step 3: Update proposal prompt text so stable working-set evidence plus no global hit can justify recording**
- [ ] **Step 4: Update local rejection hint so it no longer teaches “Review Agent does not fill D missing confirmedTermUpdates”**

### Task 4: Verify and report

**Files:**
- Modify: `docs/handoff.md` if file encoding allows safe edit; otherwise report blocker explicitly

- [ ] **Step 1: Run targeted regression tests for executor + prompt/tool guidance**
- [ ] **Step 2: If `docs/handoff.md` remains non-UTF8, do not re-encode it implicitly; report that blocker explicitly**
- [ ] **Step 3: Report exact commands and results in the final handoff**
