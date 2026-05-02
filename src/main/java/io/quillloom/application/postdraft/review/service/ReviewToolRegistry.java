package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.ReviewToolDefinition;
import io.quillloom.application.postdraft.review.model.ToolArgumentSchema;
import io.quillloom.application.postdraft.review.model.ToolRepeatPolicy;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ReviewToolRegistry {

    private final Map<String, ReviewToolDefinition> definitions;

    public ReviewToolRegistry(Collection<ReviewToolDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        LinkedHashMap<String, ReviewToolDefinition> indexed = new LinkedHashMap<>();
        for (ReviewToolDefinition definition : definitions) {
            ReviewToolDefinition nextDefinition = Objects.requireNonNull(definition, "definition");
            if (indexed.putIfAbsent(nextDefinition.toolName(), nextDefinition) != null) {
                throw new IllegalArgumentException("duplicate toolName: " + nextDefinition.toolName());
            }
        }
        this.definitions = Map.copyOf(indexed);
    }

    public static ReviewToolRegistry defaultRegistry() {
        return new ReviewToolRegistry(List.of(
                ReviewToolDefinition.builder("read_previous_chunks", "Read previous chunks as local context evidence.")
                        .whenToUse("Use only when the current focus needs nearby previous context to judge continuity, reference resolution, action flow, or tone. Use count=1 by default. Use count=2 only when one adjacent chunk is clearly insufficient for the unresolved judgment. Prefer another small adjacent read over a large single read.")
                        .whenNotToUse("Do not use this for project-wide searching or repeated reads of the same direction and quantity.")
                        .resultSemantics("Returned chunks are local evidence for the current focus and expand the workingSet. Already completed chunks remain context evidence only.")
                        .repeatPolicy(ToolRepeatPolicy.AVOID_SAME_SIGNATURE)
                        .nextStepGuidance("After each adjacent read, first verify whether the current continuity or logic uncertainty is now objectively closed. If not, continue with another small adjacent read before evaluate_focus or complete_working_set. Prefer incremental 1-2 chunk expansion across multiple rounds over a large-range single read.")
                        .requiredArguments(Set.of("count"))
                        .argumentSchemas(List.of(new ToolArgumentSchema("count", "integer", true, "Number of previous chunks to read. Must be a positive integer.")))
                        .build(),
                ReviewToolDefinition.builder("read_next_chunks", "Read next chunks as local context evidence.")
                        .whenToUse("Use only when the current focus needs nearby following context to judge transition, continuation, naming continuity, or logic flow. Use count=1 by default. Use count=2 only when one adjacent chunk is clearly insufficient for the unresolved judgment. Prefer another small adjacent read over a large single read.")
                        .whenNotToUse("Do not prefetch unrelated future chunks and do not use this as a project-wide scanning tool.")
                        .resultSemantics("Returned chunks are local evidence for the current focus and expand the workingSet. Do not submit them until they are actually completed.")
                        .repeatPolicy(ToolRepeatPolicy.AVOID_SAME_SIGNATURE)
                        .nextStepGuidance("After each adjacent read, first verify whether the current continuity or logic uncertainty is now objectively closed. If not, continue with another small adjacent read before evaluate_focus or complete_working_set. Prefer incremental 1-2 chunk expansion across multiple rounds over a large-range single read.")
                        .requiredArguments(Set.of("count"))
                        .argumentSchemas(List.of(new ToolArgumentSchema("count", "integer", true, "Number of next chunks to read. Must be a positive integer.")))
                        .build(),
                ReviewToolDefinition.builder("expand_block_context", "Expand to the chunk set inside the current block.")
                        .whenToUse("Use when the current chunk is too narrow and same-block context is necessary to judge the local semantic unit.")
                        .whenNotToUse("Do not cross block boundaries and do not use this instead of precise adjacent-chunk reads.")
                        .resultSemantics("Returns a snapshot of chunks in the current block and adds them to the local workingSet evidence scope.")
                        .repeatPolicy(ToolRepeatPolicy.AVOID_SAME_SIGNATURE)
                        .nextStepGuidance("After expansion, continue reviewing the current focus. expand_block_context does not replace adjacent continuity verification; use read_previous_chunks / read_next_chunks when boundary continuity is still unresolved.")
                        .build(),
                ReviewToolDefinition.builder("read_decision_notes", "Read decision notes for the current focus.")
                        .whenToUse("Use when draft-stage decision notes directly affect the current chunk’s translation, risk, or review judgment.")
                        .whenNotToUse("Do not use this to fill general project background. Notes do not override sourceText or translatedText.")
                        .resultSemantics("Returns draft-stage decision records as auxiliary evidence for the current focus.")
                        .repeatPolicy(ToolRepeatPolicy.AVOID_SAME_SIGNATURE)
                        .nextStepGuidance("After reading notes, compare them against the current translation and decide whether evaluate_focus or complete_working_set is appropriate.")
                        .build(),
                ReviewToolDefinition.builder("read_transition_note", "Read the transition note for the current focus.")
                        .whenToUse("Use when the current problem is about chunk-to-chunk continuity, transition, foreshadowing, or sentence carry-over.")
                        .whenNotToUse("Do not treat the note as a term database or knowledge source. Prefer stronger evidence for non-transition issues.")
                        .resultSemantics("Returns draft-stage transition hints as auxiliary continuity evidence.")
                        .repeatPolicy(ToolRepeatPolicy.AVOID_SAME_SIGNATURE)
                        .nextStepGuidance("If the transition note conflicts with the actual translation, evaluate first and revise only when needed.")
                        .build(),
                ReviewToolDefinition.builder("lookup_knowledge_cards", "Search the local project knowledge base by query terms, or return the currently relevant cards when no query is given.")
                        .whenToUse("Use when the current focus contains people, places, events, or settings that need project knowledge support.")
                        .whenNotToUse("Do not use this as the primary retrieval pipeline, do not access the network, and do not query unrelated future material.")
                        .resultSemantics("Returns local knowledge-card summaries as evidence for the current focus only.")
                        .repeatPolicy(ToolRepeatPolicy.AVOID_SAME_SIGNATURE)
                        .nextStepGuidance("If knowledge cards are sufficient, evaluate_focus. If stable term evidence is still missing, then use read_confirmed_terms for visible source terms only.")
                        .argumentSchemas(List.of(new ToolArgumentSchema("queryTerms", "string[]", false, "Semantic query terms for local knowledge lookup.")))
                        .build(),
                ReviewToolDefinition.builder("read_confirmed_terms", "Look up project-level confirmed translations by source term. Returns hits only for matched source terms.")
                        .whenToUse("Use only when a source term is actually visible in the current focus or already-read workingSet and project-level naming must be checked.")
                        .whenNotToUse("Do not pre-query terms that are not visible in the current focus. After a hit or miss for the same sourceTerm in this focus, do not query it again.")
                        .resultSemantics("Both confirmedTerm hits and confirmedTermLookupMiss entries are authoritative query results. A miss only means not registered yet; by itself it is not enough to register, but it may support record_confirmed_terms once a stable working-set pair is already closed.")
                        .repeatPolicy(ToolRepeatPolicy.FORBID_SAME_SIGNATURE_AFTER_SUCCESS)
                        .authoritativeResult(true)
                        .nextStepGuidance("After a hit, check whether the current translation already uses it. If yes and nothing else is wrong, complete_working_set; otherwise evaluate_focus.")
                        .requiredArguments(Set.of("sourceTerms"))
                        .argumentSchemas(List.of(new ToolArgumentSchema("sourceTerms", "string[]", true, "Source terms to look up.")))
                        .build(),
                ReviewToolDefinition.builder("record_confirmed_terms", "Record stable source->target pairs into the project confirmed-term store. This does not finish the current chunk.")
                        .whenToUse("Use only when the current anchor or workingSet has already established a stable source->target pair from actual sourceText and translatedText evidence.")
                        .whenNotToUse("Do not use this instead of revision. Do not record conflicts with existing confirmed terms. Do not record terms not visible in the current focus. Do not rely only on confirmedTermLookupMiss, decisionNotes, transitionNote, or translatorCommentary.")
                        .resultSemantics("A successful result means the project accepted the registration for this round. It does not mean the current chunk is complete.")
                        .repeatPolicy(ToolRepeatPolicy.STATE_TRANSITION_ONLY)
                        .nextStepGuidance("After recording, verify that the current translation is consistent. If consistent, complete_working_set; otherwise evaluate_focus or draft_revision.")
                        .requiredArguments(Set.of("entries"))
                        .argumentSchemas(List.of(new ToolArgumentSchema(
                                "entries",
                                "object{string:string}",
                                true,
                                "Non-empty JSON map from source term to target term. Only {\"<source-term>\":\"<target-term>\"} is allowed. {} is invalid. Pair-object shapes such as {\"sourceTerm\":\"...\",\"targetTerm\":\"...\"}, array shapes such as [{\"sourceTerm\":\"...\",\"targetTerm\":\"...\"}], and string arrays such as [\"A=B\"] are forbidden. When toolName=record_confirmed_terms, candidate pairs must appear in arguments.entries, not only in reason."
                        )))
                        .build(),
                ReviewToolDefinition.builder("evaluate_focus", "Evaluate the current focus / workingSet and choose the next processing strategy.")
                        .whenToUse("Use when the current evidence is sufficient to choose KEEP, LIGHT_EDIT, DEEP_EDIT, RETRANSLATE, or REQUIRE_HUMAN_REVIEW.")
                        .whenNotToUse("Do not evaluate too early when evidence is still clearly insufficient. Do not escalate local argument mistakes into human review.")
                        .resultSemantics("The result decides whether to continue investigation, keep as-is, revise, or request human review.")
                        .repeatPolicy(ToolRepeatPolicy.STATE_TRANSITION_ONLY)
                        .nextStepGuidance("For KEEP with no unresolved issue, complete_working_set. For revision strategies, use draft_revision, and do not complete_working_set until selfCheckPassed=true or revision_ready_for_completion is explicitly present. For unresolved real semantics, request_human_review.")
                        .build(),
                ReviewToolDefinition.builder("draft_revision", "Generate a revision draft and run self-check.")
                        .whenToUse("Use only after evaluate_focus has already selected LIGHT_EDIT, DEEP_EDIT, or RETRANSLATE.")
                        .whenNotToUse("Do not use this for KEEP. Do not revise directly from low-priority signals only. If self-check already passed, do not revise again without new evidence.")
                        .resultSemantics("A successful result contains a revision draft and self-check status. After passing self-check, the flow may move to completion.")
                        .repeatPolicy(ToolRepeatPolicy.STATE_TRANSITION_ONLY)
                        .nextStepGuidance("After self-check passes, prefer complete_working_set. If self-check has not passed yet, do not complete_working_set. On failure, gather missing evidence or reevaluate the focus.")
                        .build(),
                ReviewToolDefinition.builder("request_human_review", "Request human review and stop the current runtime. arguments must be {}.")
                        .whenToUse("Use only when local tools cannot resolve a real semantic conflict, ambiguity, or judgment call.")
                        .whenNotToUse("Do not use this for missing arguments, repeated tool calls, local workflow errors, or low-priority signals alone.")
                        .resultSemantics("A successful result moves the project into WAITING_HUMAN. Human input is evidence, not a command.")
                        .repeatPolicy(ToolRepeatPolicy.STATE_TRANSITION_ONLY)
                        .nextStepGuidance("Before calling this tool, explain clearly in top-level reason what local evidence could not resolve.")
                        .build(),
                ReviewToolDefinition.builder("complete_working_set", "Submit the chunkIds that are actually completed in the current anchor round.")
                        .whenToUse("Use when the current focus evidence is already closed for this round and the anchor chunk is ready to submit. The current workingSet may still contain adjacent chunks kept only as context evidence.")
                        .whenNotToUse("chunkIds must not omit the anchor. Do not submit chunks outside the current workingSet, already completed chunks, or chunks used only as context evidence.")
                        .resultSemantics("A successful result commits the review outcome for the specified chunkIds and advances to the next pending chunk.")
                        .repeatPolicy(ToolRepeatPolicy.STATE_TRANSITION_ONLY)
                        .nextStepGuidance("Before submission, ensure chunkIds contain only still-pending chunks actually completed in this round. Adjacent chunks read only as context evidence do not automatically become required chunkIds, and the current focus anchor may still be completed on its own once its evidence is closed. If focusChunk is no longer pending, do not call complete_working_set for that stale focus. If no pending chunk remains afterward, use complete_project next.")
                        .requiredArguments(Set.of("chunkIds"))
                        .argumentSchemas(List.of(new ToolArgumentSchema("chunkIds", "string[]", true, "Chunk IDs completed in this round. Must include the current anchor and come from the current workingSet.")))
                        .build(),
                ReviewToolDefinition.builder("complete_project", "Finish the project only after all pending chunks are completed.")
                        .whenToUse("Use only when the system already indicates project_ready_for_completion or when pending chunks are empty.")
                        .whenNotToUse("Do not use this while pending chunks remain or while the current workingSet is not finished.")
                        .resultSemantics("A successful result marks the project as COMPLETED.")
                        .repeatPolicy(ToolRepeatPolicy.STATE_TRANSITION_ONLY)
                        .nextStepGuidance("Use only at project close-out. In pending-empty project close-out, prefer complete_project instead of continuing the stale focus or complete_working_set.")
                        .build()
        ));
    }

    public boolean contains(String toolName) {
        return definitions.containsKey(toolName);
    }

    public ReviewToolDefinition require(String toolName) {
        ReviewToolDefinition definition = definitions.get(toolName);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown review tool: " + toolName);
        }
        return definition;
    }

    public List<ReviewToolDefinition> definitions() {
        return definitions.values().stream().toList();
    }
}
