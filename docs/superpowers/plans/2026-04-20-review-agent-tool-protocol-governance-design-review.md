# Review Agent Tool Protocol Governance Design Review

## Scope
1. This document is a design review only.
2. It does not introduce a protocol rewrite.
3. It uses the 2026-04-20 codebase as the baseline after Task 1/2 landed:
   - tighter `record_confirmed_terms` write threshold
   - strict `entries` shape validation
4. Existing architectural constraints remain in force:
   - no return to a large orchestrator
   - no generic autonomous agent society redesign
   - no breakage of the current D -> Review Agent boundary

## Problem Statement
The main failure is not a brand-new business rule branch. The main failure is a contract mismatch between prompt signals, schema signals, and validator/runtime signals, especially around `record_confirmed_terms.entries`.

Observed symptoms:
1. The model can describe the correct pair in `reason` while still outputting invalid `arguments.entries`.
2. Low-priority signals are still strong enough in some prompts to distract from higher-priority evidence.
3. Review-agent business failure can still hide behind a technically successful build if validation is too shallow.

## A. Problem Breakdown
### A1. Evidence boundary problem
Low-priority signals exist and are useful, but they must not independently authorize high-risk actions.

Signals in scope:
- `decisionNotes`
- `translatorCommentary`
- `transitionNote`
- `confirmedTermLookupMiss`

Required boundary:
1. These signals may justify more investigation.
2. These signals may justify `evaluate_focus`.
3. These signals may not independently justify:
   - `record_confirmed_terms`
   - `draft_revision`
   - `request_human_review`
4. `confirmedTermLookupMiss` means only "not registered yet".
5. `confirmedTermLookupMiss` does not mean "allowed to register".
6. `confirmedTermLookupMiss` is not direct write-table evidence.

### A2. Entries contract problem
`record_confirmed_terms.entries` must be a strict non-empty `object{string:string}`.

Allowed shape:
```json
{"entries":{"<source-term>":"<target-term>"}}
```

Rejected shapes:
```json
{"entries":{}}
{"entries":{"sourceTerm":"...","targetTerm":"..."}}
{"entries":[{"sourceTerm":"...","targetTerm":"..."}]}
{"entries":["A=B"]}
```

Required behavior:
1. No silent conversion.
2. No array-to-map repair.
3. No accepting malformed shapes just because intent is guessable.
4. When `toolName=record_confirmed_terms`, candidate pairs must live in `arguments.entries`, not only in `reason`.

### A3. Prompt / schema / validator consistency problem
The same restriction must be visible in all three layers:
1. prompt wording
2. schema description
3. validator / runtime behavior

If one layer says `entries` is a string map but another layer shows a pair-object example, the model is being taught the wrong contract.

## B. What Is In Scope for 2A
### B1. Signal consistency
Goals:
1. Make examples, descriptions, and schema wording consistent for `object{string:string}`.
2. Remove misleading examples that suggest pair-object or array forms.
3. Keep changes narrow and local to the current responsibilities.

### B2. Validation visibility
Goals:
1. Make review-agent business failure visible even when the test run or entrypoint still looks technically successful.
2. Start from review-agent prompt/provider/client tests and validation entrypoints.
3. Do not spread into unrelated modules.

## C. What Is Explicitly Out of Scope
1. No 2B work in this round.
2. No full structured `tool_use/tool_result` memory expansion.
3. No transcript / compact mechanism redesign.
4. No provider JSON schema redesign with `oneOf` or discriminators.
5. No repair sinking into `OpenAiCompatibleReviewAgentStructuredGenerationClient`.
6. No actionId / allowedActions refactor.
7. No new tools.
8. No `ReviewToolExecutor` switch expansion.
9. No unrelated architectural cleanup.

## D. Recommended 2A Direction
### D1. Single-source contract for `entries`
1. Keep `ToolArgumentSchema` as the source of the `entries` shape contract where feasible.
2. Make registry wording, schema wording, and tests all point to the same map-only rule.
3. Remove duplicate definitions that can drift apart.

### D2. Harder repair contract for `invalid_argument:entries`
Repair should be a strict two-option contract:
1. Continue `record_confirmed_terms` with valid non-empty `arguments.entries`.
2. Stop using `record_confirmed_terms` and switch to an allowed investigation/evaluation tool with valid arguments in one shot.

Forbidden third output:
1. giving up `record_confirmed_terms` but still returning invalid tool/arguments
2. using `reason` to discuss union/schema/argument-conflict analysis
3. adding explanatory text outside JSON

### D3. P0-first next-step prompt structure
Prompt rules must be ordered by authority:
1. P0 hard blocks first
2. high-priority evidence next
3. low-priority signals later
4. tool formatting reminders last

Required P0 rules:
1. confirmed-term conflict unresolved -> forbid `complete_working_set`
2. no explicit source->target pair -> forbid `record_confirmed_terms`
3. only low-priority signals -> forbid `record_confirmed_terms / draft_revision / request_human_review`

## E. Testing Expectations
At minimum, regression coverage should lock:
1. invalid `entries` repair contract in both repair entrypoints
2. non-entries errors do not inject the entries-specific contract
3. P0 prompt text blocks `complete_working_set` when a confirmed-term conflict is unresolved
4. low-priority signals can still justify investigation / `evaluate_focus`
5. low-priority signals cannot independently justify high-risk actions
6. schema description still contains the hard `entries` contract after governance text is reduced

## F. Why This Is Still 2A, Not 2B
1. The work tightens local prompt/schema/validator consistency.
2. It does not redesign the transport protocol.
3. It does not add structured memory channels.
4. It does not move business authorization into provider/client internals.
5. It reduces ambiguity in the existing contract instead of inventing a new one.

## G. Reviewer Questions and Answers
### 1. Why not fix this only in the executor?
Because executor rejection is a late guardrail. It stops bad writes, but it does not stop the model from learning and repeating the wrong shape.

### 2. Why not jump directly to 2B?
Because current evidence still points to a narrower failure: prompt/schema/validator mismatch around `entries`. 2B is broader and riskier.

### 3. Which parts are schema issues vs responsibility issues vs protocol issues?
1. Schema issue: wrong or inconsistent `entries` examples / descriptions.
2. Responsibility issue: low-priority signals doing more than they are allowed to do.
3. Protocol issue: current `toolName + arguments + reason` contract is still a simplified decision object rather than a full structured tool-use protocol.

## Conclusion
The immediate value is in making `entries` a single, hard, consistent contract and making high-risk action boundaries visible at prompt, schema, validator, and regression-test level. That is enough for 2A. It is not a protocol rewrite.
