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

## 2026-04-22 Review Agent Context / Prompt Refactor Notes
1. Current code does not yet implement `read_previous_chunks` / `read_next_chunks` as working-set-boundary expansion.
2. The concrete mismatch is in `ReviewToolExecutor.executeReadAdjacent(...)`, which still reads around `session.focus().chunkId()`.
3. Canonical chunk order already exists in reader-side package ordering:
   - primary key: `PostDraftChunkRecord.sequence`
   - tie-breaker: `chunkId`
4. `ReviewEvidenceBundle` is currently carrying both summary memory and high-fidelity read-chunk text in string form; this is the main context-layering fault.
5. `InvestigationPromptBuilder`, `EvaluationPromptBuilder`, `RevisionPromptBuilder`, and `RevisionSelfCheckPromptBuilder` currently do not inject a stable working-set fulltext section.
6. `WorkingSetCompletionHandler` currently enforces only:
   - focus chunk included
   - submitted chunkIds stay inside current workingSet
   - submitted chunkIds remain pending
7. Therefore ¡°other submitted chunks were actually read and verified this round¡± is not currently an execution-level guarantee and must be addressed explicitly in the refactor plan.
8. Phase C should stay constrained to cross-focus review-summary inheritance, not a general long-term memory system.

## 2026-04-22 Multi-Chunk Completion Enforcement Decision
1. This design is now locked: `complete_working_set` must enforce, at execution level, that any extra non-focus chunk submitted in the same round was actually read and explicitly verified in that round.
2. Prompt-side wording is still required, but no longer considered sufficient protection.
3. Required markers must remain inside review-session/runtime scope and must not be pushed into stable domain contracts.

## 2026-04-22 Review-Agent Scope Clarification
1. Review-agent work should still stay centered in `application/postdraft/review` and `infrastructure/postdraft/review`.
2. Narrow companion changes are also allowed in:
   - `application/postdraft/review/port/out`
   - `interfaces/api/dto`
   - `src/test/java/io/quillloom/support`
   when they are required to preserve human-request propagation, status visibility, or smoke/debug verification.
3. This is still not permission to spread review-agent refactors into unrelated modules.

## 2026-04-22 `questionForHuman` Contract Clarification
1. `questionForHuman` is an additive, backward-compatible human-collaboration / diagnostic field extension.
2. It does not change:
   - tool-call protocol
   - required tool arguments
   - `ReviewAgentStructuredGenerationPort` external contract
3. It may appear in persisted runtime, status, result, writer, and gateway layers as long as the change remains additive and old stored JSON remains readable.
4. If any external consumer requires strict schema stability, implementation must carry an explicit compatibility note or versioning decision instead of silently redefining the boundary.

## 2026-04-22 Phase B Status And Next Step
1. Phase B core structure has been implemented in code:
   - workingSet fulltext context layering
   - boundaryWindow-based adjacent expansion
   - per-focus markers
   - focus-only conservative completion
   - persistence / resume compatibility
   - prompt / human-request / dump wiring
2. Current remaining issue is behavioral, not structural: the agent is still not proactive enough about reading adjacent context in investigation.
3. Therefore the next priority is not Phase C yet.
4. The next priority is prompt-side reinforcement for adjacent-context reading behavior:
   - strengthen investigation/system prompt rules for continuity-dependent scenes
   - reduce premature evaluate_focus / complete_working_set when adjacent context is still missing
5. Phase C stays deferred until this prompt-behavior follow-up is validated.

## 2026-04-23 Phase B9 Prompt Follow-up Additions
1. B9 also includes fixing mojibake / broken text in the evaluation system prompt inside `PromptBackedStrategyEvaluationService`.
2. This is treated as a Phase B prompt-quality bug, not a separate redesign task, because corrupted evaluation prompt text directly pollutes strategy selection quality.
3. B9 must also harden the prompt rule that:
   - if current strategy is `LIGHT_EDIT` / `DEEP_EDIT` / `RETRANSLATE`
   - and the current focus has not yet successfully finished `draft_revision` plus self-check
   - the agent must not go directly to `complete_working_set`
4. The intent is prompt-side consistency: do not allow a decision whose reason admits revision is needed while the chosen action directly completes the chunk.

## 2026-04-23 Review-Agent LLM Transport Containment Follow-up
1. Phase B follow-up also includes transport-failure containment for review-agent LLM calls.
2. `GOAWAY received`, HTTP/2 shutdown, and similar `IOException` transport failures should be treated as transient transport failures when appropriate and enter bounded retry.
3. Even when a transport exception is not successfully classified as transient, the review-agent path must still prefer runtime containment (`LLM_CALL_FAILED` / persisted stop state) over crashing the whole Spring CLI process with an uncaught top-level `RuntimeException`.
4. This is not permission to hide failures. The requirement is controlled retry or controlled runtime stop with diagnostics, not silent fallback.

## 2026-04-23 B9 Clarifications
1. The investigation prompt should expose objective adjacent-context state only:
   - boundaryWindow left/right chunk ids
   - anchorOnlyView
   - hasPreviousRead
   - hasNextRead
   - adjacentReadCount
   It should not expose a code-side boolean claiming continuity evidence is already sufficient.
2. Prompt-side completion gating must use one explicit positive signal only:
   - `selfCheckPassed=true`
   - or equivalent `revision_ready_for_completion`
   Strategy alone is never enough to justify `complete_working_set`.
3. Transport containment has one fixed fallback boundary:
   - transport/client layer classifies transient failures first
   - anything not classified or not retried successfully must be contained at runtime orchestrator level as `LLM_CALL_FAILED`
   - this applies to next-step, evaluation, revision draft, and revision self-check paths

## 2026-04-23 B10 Project Completion Follow-up
1. B10 remains intentionally narrow.
2. The runtime already knows project-level progress through pendingChunkIds and completedChunkOutcomes; the remaining problem is exposing that state clearly enough and ending the project once no chunk remains pending.
3. B10 should expose only:
   - pendingChunkCount
   - completedChunkCount
   - currentFocusChunkStillPending
4. pending-empty auto-close is allowed only for ACTIVE runtime endgame.
5. pendingChunkIds being empty is necessary but not sufficient; blocking backlog or non-ACTIVE stop contexts must still block auto-completion.
6. Ordinary investigation/evaluation/revision failure must not be auto-converted into completed.

## 2026-04-23 Prompt Refactor Design Guardrails
1. Prompt refactor should use one normative-source rule:
   - system prompt owns only cross-stage constant rules
   - investigation prompt owns only current-round decision gates derived from runtime facts
   - stage-specific prompts must not restate next-step gate rules as full normative text
   - explanatory appendices are non-normative and must not become a second source of truth
2. Review-agent stage progression is constrained but not a hard state machine:
   - next-step remains the only tool-selection entry
   - evaluation only outputs strategy and evidence sufficiency
   - revision only outputs draft
   - self-check only outputs readiness signal
   - completion is still chosen by next-step, not auto-triggered by downstream stages
3. Prompt compression must not weaken the format-defense chain:
   - JSON/schema shape
   - validator checks
   - repair prompts
   - runtime containment
   remain separate required layers
4. Prompt refactor must stay compatible with the current memory mechanism:
   - reuse existing workingSetContext, evidence summaries, transcript, gaps, and local failures
   - do not introduce new persisted memory types or change persistence/resume/compact contracts only to support prompt layering

## 2026-04-24 Prompt Refactor Follow-up Clarifications
1. ecord_confirmed_terms keeps its current narrow two-phase special path:
   - next-step selects the tool
   - proposal generation / proposal repair / assembly / proposal NOT_APPLICABLE local replan remain tool-local subflow only
   - this must not be generalized into a new global proposal phase
2. The evidence-closure appendix in the prompt-refactor spec is explanatory only, but it must still map back into investigation prompt decision-gate text:
   - each major review dimension should contribute at least 1-2 executable gate lines
   - the appendix must not become a second normative source
3. Prompt compression must preserve minimal semantic schema hints for high-risk tools:
   - ecord_confirmed_terms
   - equest_human_review
   - complete_working_set
   - complete_project
   Shape-only schema wording is not sufficient for these tools.

## 2026-04-25 Review-Agent Console Visualization Refactor Guardrails
1. Console visualization refactor should stay presentation-only:
   - improve trace readability
   - do not become a new router / planner / orchestrator
2. The next priority is not a TUI or web UI.
3. The key missing observability units are:
   - focus round
   - action/result pairing
   - repair / replan / containable-failure visibility
4. Showing the agent's "thinking" means showing:
   - decision reason summary
   - current stage / gate state
   - chosen tool and result
   not exposing raw chain-of-thought.
## 2026-04-25 Prompt Template Canonicalization
1. The prompt-refactor spec now contains Chinese module-level prompt templates for system / investigation / evaluation / revision / self-check / repair.
2. These Chinese templates are the canonical semantic baseline for implementation.
3. Code may use English prompt text, but only as faithful translation:
   - do not add new governance rules
   - do not drop required constraints
   - do not reshuffle layer ownership
4. If implementation wording conflicts with the spec templates, the spec semantics win.
## 2026-04-25 Console Visualization Follow-up Guardrails
1. High-level visualization events must be emitted only by AutonomousProjectReviewAgent.
2. PromptBackedNextStepDecisionProvider and other lower-level services must not gain direct visualizer dependencies.
3. If lower layers need to expose repair / proposal / rejection / local replan details, they should return them through results, exceptions, processTrail, or read-only diagnostics for the agent to visualize.
4. Round semantics are fixed to ProjectReviewRuntimeSession.currentFocusRound.
5. Repair / proposal / local replan stay attached under the current round and must not be shown as a new focus round.
6. Console output should have explicit OFF / COMPACT / TRACE modes; default service wiring should use OFF or COMPACT.
## 2026-04-25 Console Visualization Final Narrowing
1. For visualization-related repair / proposal / local replan details, implementation should prefer existing carriers in this order:
   - ReviewToolExecutionResult
   - ProjectReviewRuntimeSession.processTrail
   - classified exception types
2. Only if those existing carriers cannot stably express the needed information may the implementation add an agent-private read-only trace/diagnostics DTO.
3. Such DTOs must not enter persistence, resume payloads, or external protocols.
4. Output mode minimum sets are now fixed:
   - COMPACT: project_started, focus_selected, decision/action summary, toolCompleted/result summary, humanReviewRequested, terminal failure, project_finished
   - TRACE: round start/finish, gate summary, detailed action/result blocks, repair blocks, proposal special path, containable-failure blocks
5. COMPACT must not show round sub-blocks or repair/proposal sub-blocks by default.
6. TRACE must not blindly duplicate the full legacy single-line event stream.
