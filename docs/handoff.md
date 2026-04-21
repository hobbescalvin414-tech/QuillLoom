# Handoff

## Read First
1. [docs/README.md](./README.md)
2. [docs/current-architecture.md](./current-architecture.md)
3. [docs/current-status.md](./current-status.md)
4. [docs/modules/name-consistency.md](./modules/name-consistency.md)
5. [docs/modules/d-draft-chain-issues.md](./modules/d-draft-chain-issues.md)

## Architecture Baseline
1. The system is still a controlled pipeline, not an autonomous agent society.
2. Do not return to a large orchestrator.
3. C0 owns primary retrieval and project-level knowledge consolidation.
4. The assembly layer only selects the limited knowledge cards that are valid for the current chunk.
5. D consumes the first batch of assembled knowledge cards and may only do local follow-up card loading inside its loop.
6. D is offline.
7. Do not push loop-only runtime state back into stable domain contracts.
8. `TranslationTaskInput` remains a stable execution input contract, not a giant mutable state object.

## Review Agent Baseline
1. The current review agent is a guarded, tool-based local review loop.
2. Focus is anchor-chunk review, local evidence expansion, strategy evaluation, revision, and controlled human escalation.
3. The runtime is bounded by tool registry contracts, executor guardrails, validator checks, and prompt-side governance.
4. `read_confirmed_terms` is authoritative project lookup.
5. `record_confirmed_terms` is a high-risk state-changing tool and must stay tightly gated.

## Current Direction
1. Keep review-agent changes inside `application/postdraft/review` and `infrastructure/postdraft/review`.
2. Prefer small hardening work over protocol redesign.
3. Treat prompt/schema/validator mismatches as first-order failures because they directly pollute LLM behavior.
4. Keep prompt dumps available in dev for reproducible debugging.

## 2026-04-20 Progress Update
1. The low-priority signal evidence boundary has been implemented in the smallest allowed scope.
2. `decisionNotes`, `translatorCommentary`, `transitionNote`, and `confirmedTermLookupMiss` may support investigation and `evaluate_focus`, but they may not independently trigger `record_confirmed_terms`, `draft_revision`, or `request_human_review`.
3. `confirmedTermLookupMiss` means only "not registered yet". It is not registration permission and not direct write-table evidence.
4. Targeted regression coverage was added for the Bernolle-style miss + notes + commentary case.

## 2026-04-21 Prompt and Repair Hardening
1. `invalid_argument:entries` repair now follows a strict two-option contract:
   - continue `record_confirmed_terms` with a valid non-empty `object{string:string}` map
   - or switch to an allowed investigation/evaluation tool with fully valid arguments
2. The forbidden third output is explicitly blocked:
   - invalid tool/arguments after giving up `record_confirmed_terms`
   - union/schema/argument-conflict analysis in `reason`
   - explanatory text outside JSON
3. The next-step system prompt now uses a clearer P0-first layout.
4. `complete_working_set` is allowed only when no unresolved high-priority issue remains.

## 2026-04-21 Entries Binding Rule
1. When `toolName=record_confirmed_terms`, candidate pairs must appear in `arguments.entries`.
2. Candidate pairs may not appear only in `reason`.
3. During entries repair, if `reason` already contains an explicit source->target pair, the repair prompt must require the same pair inside `arguments.entries`.

## 2026-04-21 Prompt Dump Support
1. Dev runtime now supports prompt dumps behind configuration.
2. Dump files include:
   - prompt kind
   - attempt
   - anchor chunk id
   - tool name
   - validation error
   - error message
   - raw output
   - system prompt
   - user prompt
3. Dumps are written for:
   - initial next-step prompt
   - each repair prompt
   - final failure before exception propagation

## Open Issues
1. Historical docs outside the most recent review-agent work still need cleanup where they contain broken text.
2. Keep future prompt hardening changes narrow. Do not slide into protocol redesign without an explicit plan.
3. Continue using targeted regression tests whenever prompt/schema wording is changed.

## 2026-04-21 Prompt Minimal Hardening Design (Scope Locked)
1. This round is design-only, scoped to three prompt issues:
   - residual mojibake in runtime-chain prompt text
   - unstable investigation prompt quality
   - insufficiently concentrated argument-placement constraint signal
2. Minimal fix scope is now narrowed to two implementation items only:
   - add one hard rule sentence in `InvestigationPromptBuilder` output reminders
   - add investigation coverage in `ReviewPromptBuilderTest`
3. The entries placement rule should be repeated in investigation output reminders:
   - when `toolName=record_confirmed_terms`, candidate pairs must be in `arguments.entries`, not only in `reason`
4. Minimal test additions are locked to `ReviewPromptBuilderTest`:
   - add `assertNoMojibake(...)` for investigation prompt
   - add explicit investigation assertion for `arguments.entries` placement rule
5. This round does not include:
   - language unification across prompt layers
   - investigation prompt large-scale rewrite
   - extra cleanup in `ReviewAgentSystemPromptBuilder` or `ReviewToolRegistry`
6. Full design evidence and file/line references are documented at:
   - `docs/superpowers/specs/2026-04-21-review-agent-prompt-minimal-hardening-design.md`

## 2026-04-21 `record_confirmed_terms.entries={}` Runtime Containment
1. Current project-wide crash point is `AutonomousProjectReviewAgent`, not `ReviewToolExecutor`.
2. The failure path is:
   - LLM raw output returns `record_confirmed_terms` with `arguments.entries={}`
   - client validates it as `invalid_argument:entries` and throws `LlmStructuredOutputException`
   - provider retries repair, but after repair exhaustion still rethrows
   - runtime catches that exception and immediately upgrades the whole project to `LLM_CALL_FAILED`
3. This round's preferred minimal containment point is runtime:
   - keep client/provider validation strict
   - downgrade exhausted next-step structured-output failure to current-focus local failure
   - preserve `chunkId`, error code, and raw output for diagnosis
   - continue remaining project chunks instead of failing the whole project
4. Do not hide the issue by silently skipping the chunk or auto-converting it to `request_human_review`.
5. Full design evidence and file/line references are documented at:
   - `docs/superpowers/plans/2026-04-21-review-agent-entries-empty-map-runtime-containment-design.md`

## 2026-04-21 `record_confirmed_terms` Two-Phase Design
1. Root-cause fix is now narrowed to `record_confirmed_terms` only; other tools stay single-phase.
2. The recommended design is:
   - phase 1: model decides whether record_confirmed_terms applies and emits explicit `sourceTerm/targetTerm` pair DTOs
   - phase 2: local code assembles final `arguments.entries` map and then reuses existing validator/executor flow
3. Main prompt should keep global routing boundaries for `record_confirmed_terms`, but final `entries` map-shape teaching should move out of the main next-step decision path.
4. The provider may add only a narrow pre-routing branch for entering the proposal path; this is not a general tool-governance redesign.
5. Proposal `NOT_APPLICABLE` may fall back to ordinary next-step decision, but proposal structured failure and proposal-to-decision assembly failure must not fall back; they depend on runtime containment to become current-focus local failures instead of project-wide `LLM_CALL_FAILED`.
6. This two-phase design does not independently close project-level failure containment and must not ship without the runtime containment work in the same rollout.
7. Full design is documented at:
   - `docs/superpowers/plans/2026-04-21-record-confirmed-terms-two-phase-design.md`

## 2026-04-21 `record_confirmed_terms` Two-Phase Implementation
1. `ReviewAgentStructuredGenerationPort` now has a dedicated `generateRecordConfirmedTermsProposal(...)` path, and the OpenAI-compatible client validates `RecordConfirmedTermsProposal` separately from ordinary next-step tool decisions.
2. `PromptBackedNextStepDecisionProvider` now has a narrow pre-route:
   - only high-weight pair signals from `confirmedTerm=` / `confirmedTermUpdates={...}` enter the proposal path
   - low-priority-only signals stay on ordinary next-step routing
   - proposal `NOT_APPLICABLE` falls back to ordinary next-step
3. Proposal phase now emits `RecordConfirmedTermsProposal`, and local assembly converts it into ordered `arguments.entries`; conflicting targets for the same source fail with a dedicated `RecordConfirmedTermsAssemblyException`.
4. Runtime containment is now type-based, not message-based:
   - `ReviewAgentNextStepStructuredOutputException`
   - `RecordConfirmedTermsProposalException`
   - `RecordConfirmedTermsAssemblyException`
5. These three failures are now contained as current-focus failures:
   - current focus is removed from pending execution
   - summary goes to `processTrail`
   - full diagnostic including `chunkId`, `failureCode`, and `rawOutput` goes to `issueBacklog`
   - project continues with remaining pending chunks
6. Non-containable raw `LlmStructuredOutputException` still preserves project-level `LLM_CALL_FAILED`.

## 2026-04-21 `record_confirmed_terms` Proposal Routing Correction
1. A new regression was found after the two-phase implementation: proposal was moved too early and started changing agent investigation order, not only `record_confirmed_terms` argument shaping.
2. The incorrect current flow is:
   - local stable pair signal appears
   - provider enters proposal first
   - proposal returns `record_confirmed_terms`
   - normal next-step investigation is bypassed
3. The correct flow must be:
   - ordinary `generateNextToolDecision` runs first
   - if project-level confirmation is still needed, it may choose `read_confirmed_terms`
   - only after ordinary next-step has already selected `record_confirmed_terms` may proposal run
4. Therefore proposal must be downgraded from a pre-routing decision branch to a `record_confirmed_terms` parameter-shaping phase only.
5. Repeated registration is a symptom, not the root cause; the root cause is that proposal was allowed to override the normal ¡°investigate first, then decide whether to record¡± behavior.
6. Full design is documented at:
   - `docs/superpowers/plans/2026-04-21-record-confirmed-terms-two-phase-routing-correction-design.md`

## 2026-04-21 Two-Phase Repair Unification
1. `record_confirmed_terms` now uses one shared repair framework across next-step and proposal stages.
2. Proposal `NOT_APPLICABLE` is no longer treated as an immediate fatal exception; it now triggers next-step replan inside the same bounded repair loop.
3. Proposal structured-output failure and proposal assembly failure now return structured repair prompts to the LLM instead of failing after one mistake.
4. Next-step and proposal now share one bounded repair budget inside the same decision cycle.
5. Shared repair budget exhaustion still stays inside the existing runtime containment boundary:
   - next-step terminal failure -> `ReviewAgentNextStepStructuredOutputException`
   - proposal structured / proposal replan exhaustion -> `RecordConfirmedTermsProposalException`
   - proposal assembly exhaustion -> `RecordConfirmedTermsAssemblyException`
6. Legacy one-phase `[entries repair]` guidance is no longer the repair path for two-phase proposal handling; if stage A still directly emits invalid `record_confirmed_terms` decisions, entries compatibility repair may remain in narrowed form there until that error surface is removed from the main contract.
