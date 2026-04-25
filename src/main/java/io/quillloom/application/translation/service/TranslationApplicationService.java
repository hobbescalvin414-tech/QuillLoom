package io.quillloom.application.translation.service;

import io.quillloom.application.translation.assembler.TranslationTaskInputAssembler;
import io.quillloom.application.translation.model.ConfirmedTermConflict;
import io.quillloom.application.translation.model.TranslationDraftRunResult;
import io.quillloom.application.translation.port.out.ChunkTranslator;
import io.quillloom.application.translation.port.out.ConfirmedTermConflictRepairingChunkTranslator;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import io.quillloom.application.workflow.trace.model.WorkflowEventStatus;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import io.quillloom.domain.memory.ChapterMemorySnapshot;
import io.quillloom.domain.memory.ProjectMemorySnapshot;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.PreprocessDossier;
import io.quillloom.domain.shared.TermTextNormalizer;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.translation.TranslationRuntimeOptions;
import io.quillloom.domain.translation.TranslationTaskInput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent D 应用层入口。
 * 当前负责受控地装配稳定执行输入，并按顺序推进 chunk 翻译。
 */
@Service
public class TranslationApplicationService {

    private static final int CONFIRMED_TERM_CONFLICT_REPAIR_MAX_ATTEMPTS = 3;

    private final TranslationTaskInputAssembler translationTaskInputAssembler;
    private final ChunkTranslator chunkTranslator;
    private final WorkflowTraceRecorder traceRecorder;

    @Autowired
    public TranslationApplicationService(TranslationTaskInputAssembler translationTaskInputAssembler,
                                         ChunkTranslator chunkTranslator) {
        this(translationTaskInputAssembler, chunkTranslator, new WorkflowTraceRecorder());
    }

    public TranslationApplicationService(TranslationTaskInputAssembler translationTaskInputAssembler,
                                         ChunkTranslator chunkTranslator,
                                         WorkflowTraceRecorder traceRecorder) {
        this.translationTaskInputAssembler = translationTaskInputAssembler;
        this.chunkTranslator = chunkTranslator;
        this.traceRecorder = traceRecorder;
    }

    public ChunkTranslationDraft translate(TranslationTaskInput input) {
        return chunkTranslator.translate(input);
    }

    public ChunkTranslationDraft translateChunk(PreprocessDossier dossier,
                                                ChunkAnnotation chunk,
                                                ProjectMemorySnapshot projectMemory,
                                                ChapterMemorySnapshot chapterMemory) {
        return translateChunk(dossier, chunk, projectMemory, chapterMemory, List.of(), TranslationRuntimeOptions.defaults());
    }

    public ChunkTranslationDraft translateChunk(PreprocessDossier dossier,
                                                ChunkAnnotation chunk,
                                                ProjectMemorySnapshot projectMemory,
                                                ChapterMemorySnapshot chapterMemory,
                                                List<ChunkTranslationDraft> completedDrafts,
                                                TranslationRuntimeOptions runtimeOptions) {
        TranslationTaskInput input = assembleInput(dossier, chunk, projectMemory, chapterMemory, completedDrafts, runtimeOptions);
        return chunkTranslator.translate(input);
    }

    /**
     * 受控顺序执行全量 chunk。
     * 当前只把允许前推的稳定结果写入下一个 chunk 的输入视图。
     */
    public List<ChunkTranslationDraft> translateChunks(PreprocessDossier dossier,
                                                       ProjectMemorySnapshot projectMemory,
                                                       ChapterMemorySnapshot chapterMemory,
                                                       TranslationRuntimeOptions runtimeOptions) {
        return translateChunksWithMemory(dossier, projectMemory, chapterMemory, runtimeOptions).drafts();
    }

    public TranslationDraftRunResult translateChunksWithMemory(PreprocessDossier dossier,
                                                               ProjectMemorySnapshot projectMemory,
                                                               ChapterMemorySnapshot chapterMemory,
                                                               TranslationRuntimeOptions runtimeOptions) {
        List<ChunkAnnotation> chunks = dossier.chunkAnnotations().chunks();
        if (chunks == null || chunks.isEmpty()) {
            return new TranslationDraftRunResult(List.of(), projectMemory);
        }

        List<ChunkTranslationDraft> completedDrafts = new ArrayList<>();
        ProjectMemorySnapshot effectiveProjectMemory = projectMemory;
        ChapterMemorySnapshot effectiveChapterMemory = chapterMemory;
        for (ChunkAnnotation chunk : chunks) {
            TranslationTaskInput input = assembleInput(
                    dossier,
                    chunk,
                    effectiveProjectMemory,
                    effectiveChapterMemory,
                    completedDrafts,
                    runtimeOptions
            );
            ChunkTranslationDraft draft = chunkTranslator.translate(input);
            draft = repairConfirmedTermConflicts(input, effectiveProjectMemory, draft);
            completedDrafts.add(draft);
            effectiveProjectMemory = evolveProjectMemory(effectiveProjectMemory, draft);
            effectiveChapterMemory = evolveChapterMemory(effectiveChapterMemory, draft);
        }
        return new TranslationDraftRunResult(completedDrafts, effectiveProjectMemory);
    }

    public List<ChunkTranslationDraft> translateChunks(PreprocessDossier dossier,
                                                       ProjectMemorySnapshot projectMemory,
                                                       ChapterMemorySnapshot chapterMemory) {
        return translateChunks(dossier, projectMemory, chapterMemory, TranslationRuntimeOptions.defaults());
    }

    private TranslationTaskInput assembleInput(PreprocessDossier dossier,
                                               ChunkAnnotation chunk,
                                               ProjectMemorySnapshot projectMemory,
                                               ChapterMemorySnapshot chapterMemory,
                                               List<ChunkTranslationDraft> completedDrafts,
                                               TranslationRuntimeOptions runtimeOptions) {
        return translationTaskInputAssembler.assemble(
                dossier,
                chunk,
                projectMemory,
                chapterMemory,
                completedDrafts,
                runtimeOptions
        );
    }

    private ChunkTranslationDraft repairConfirmedTermConflicts(TranslationTaskInput input,
                                                               ProjectMemorySnapshot projectMemory,
                                                               ChunkTranslationDraft initialDraft) {
        ChunkTranslationDraft currentDraft = initialDraft;
        for (int attempt = 1; attempt <= CONFIRMED_TERM_CONFLICT_REPAIR_MAX_ATTEMPTS; attempt++) {
            ConfirmedTermConflict conflict = detectConfirmedTermConflict(projectMemory, currentDraft);
            if (conflict == null) {
                return currentDraft;
            }
            recordConfirmedTermConflictRepairAttempt(input, conflict, attempt);
            if (!(chunkTranslator instanceof ConfirmedTermConflictRepairingChunkTranslator repairingTranslator)) {
                throw new IllegalStateException(
                        "confirmed_term_conflict_repair_unavailable: sourceKey=%s, chunkId=%s"
                                .formatted(conflict.sourceKey(), conflict.evidenceChunkId())
                );
            }
            currentDraft = repairingTranslator.repairConfirmedTermConflict(input, currentDraft, conflict, attempt);
        }

        ConfirmedTermConflict unresolved = detectConfirmedTermConflict(projectMemory, currentDraft);
        if (unresolved != null) {
            throw new IllegalStateException(
                    "confirmed_term_conflict_repair_exhausted: sourceKey=%s, existingSourceTerm=%s, existingTargetTerm=%s, incomingSourceTerm=%s, incomingTargetTerm=%s, chunkId=%s"
                            .formatted(
                                    unresolved.sourceKey(),
                                    unresolved.existingSourceTerm(),
                                    unresolved.existingTargetTerm(),
                                    unresolved.incomingSourceTerm(),
                                    unresolved.incomingTargetTerm(),
                                    unresolved.evidenceChunkId()
                            )
            );
        }
        return currentDraft;
    }

    private void recordConfirmedTermConflictRepairAttempt(TranslationTaskInput input,
                                                          ConfirmedTermConflict conflict,
                                                          int attempt) {
        traceRecorder.record(
                WorkflowStage.CHUNK_TRANSLATION,
                "confirmed_term_conflict_repair_attempt",
                WorkflowEventStatus.STARTED,
                input.sourceMaterial().chunk().chunk().coarseBlockId(),
                input.sourceMaterial().chunk().chunk().chunkId(),
                Map.of(
                        "attempt", attempt,
                        "sourceKey", conflict.sourceKey(),
                        "existingSourceTerm", conflict.existingSourceTerm(),
                        "existingTargetTerm", conflict.existingTargetTerm(),
                        "incomingSourceTerm", conflict.incomingSourceTerm(),
                        "incomingTargetTerm", conflict.incomingTargetTerm()
                )
        );
    }

    private ConfirmedTermConflict detectConfirmedTermConflict(ProjectMemorySnapshot projectMemory,
                                                              ChunkTranslationDraft draft) {
        if (projectMemory == null || draft == null || draft.confirmedTermUpdates().isEmpty()) {
            return null;
        }
        Map<String, ConfirmedTermEntry> existingTerms = confirmedTermEntries(projectMemory.confirmedTerms());
        for (Map.Entry<String, String> incoming : draft.confirmedTermUpdates().entrySet()) {
            String incomingSourceTerm = TermTextNormalizer.displayText(incoming.getKey());
            String incomingTargetTerm = TermTextNormalizer.displayText(incoming.getValue());
            String sourceKey = TermTextNormalizer.keyText(incomingSourceTerm);
            String targetKey = TermTextNormalizer.keyText(incomingTargetTerm);
            if (sourceKey.isBlank() || targetKey.isBlank()) {
                continue;
            }
            ConfirmedTermEntry existing = existingTerms.get(sourceKey);
            if (existing != null && !existing.targetKey().equals(targetKey)) {
                return new ConfirmedTermConflict(
                        sourceKey,
                        existing.displaySourceTerm(),
                        existing.displayTargetTerm(),
                        incomingSourceTerm,
                        incomingTargetTerm,
                        draft.chunkId()
                );
            }
        }
        return null;
    }

    private ProjectMemorySnapshot evolveProjectMemory(ProjectMemorySnapshot projectMemory,
                                                      ChunkTranslationDraft draft) {
        if (projectMemory == null) {
            return null;
        }

        Map<String, ConfirmedTermEntry> mergedConfirmedTerms = confirmedTermEntries(projectMemory.confirmedTerms());
        draft.confirmedTermUpdates().forEach((sourceTerm, targetTerm) ->
                mergeConfirmedTermOrThrow(mergedConfirmedTerms, sourceTerm, targetTerm, "chunkId=" + draft.chunkId()));

        List<TranslationCandidateUpdate> mergedCandidateTerms = mergeCandidateTermUpdates(
                projectMemory.candidateTermUpdates(),
                draft.candidateUpdates()
        );

        return new ProjectMemorySnapshot(
                projectMemory.projectId(),
                confirmedTermDisplayMap(mergedConfirmedTerms),
                projectMemory.stylePolicies(),
                projectMemory.canonFacts(),
                mergedCandidateTerms
        );
    }

    private Map<String, ConfirmedTermEntry> confirmedTermEntries(Map<String, String> confirmedTerms) {
        Map<String, ConfirmedTermEntry> entries = new LinkedHashMap<>();
        if (confirmedTerms == null || confirmedTerms.isEmpty()) {
            return entries;
        }
        confirmedTerms.forEach((sourceTerm, targetTerm) ->
                mergeConfirmedTermOrThrow(entries, sourceTerm, targetTerm, "projectMemory"));
        return entries;
    }

    private Map<String, String> confirmedTermDisplayMap(Map<String, ConfirmedTermEntry> entries) {
        Map<String, String> display = new LinkedHashMap<>();
        entries.values().forEach(entry -> display.put(entry.displaySourceTerm(), entry.displayTargetTerm()));
        return Map.copyOf(display);
    }

    private void mergeConfirmedTermOrThrow(Map<String, ConfirmedTermEntry> confirmedTerms,
                                           String sourceTerm,
                                           String targetTerm,
                                           String evidence) {
        String displaySourceTerm = TermTextNormalizer.displayText(sourceTerm);
        String displayTargetTerm = TermTextNormalizer.displayText(targetTerm);
        String sourceKey = TermTextNormalizer.keyText(displaySourceTerm);
        String targetKey = TermTextNormalizer.keyText(displayTargetTerm);
        if (sourceKey.isBlank() || targetKey.isBlank()) {
            return;
        }
        ConfirmedTermEntry existing = confirmedTerms.get(sourceKey);
        if (existing == null) {
            confirmedTerms.put(sourceKey, new ConfirmedTermEntry(displaySourceTerm, displayTargetTerm, targetKey));
            return;
        }
        if (existing.targetKey().equals(targetKey)) {
            return;
        }
        throw new IllegalStateException(
                "confirmed_term_conflict: sourceTerm=%s, existing=%s, incoming=%s, evidence=%s"
                        .formatted(displaySourceTerm, existing.displayTargetTerm(), displayTargetTerm, evidence)
        );
    }

    private List<TranslationCandidateUpdate> mergeCandidateTermUpdates(List<TranslationCandidateUpdate> existing,
                                                                       List<TranslationCandidateUpdate> incoming) {
        Map<String, TranslationCandidateUpdate> merged = new LinkedHashMap<>();
        for (TranslationCandidateUpdate update : safeCandidateUpdates(existing)) {
            merged.put(TermTextNormalizer.pairKey(update.sourceTerm(), update.candidateTranslation()), update);
        }
        for (TranslationCandidateUpdate update : safeCandidateUpdates(incoming)) {
            merged.putIfAbsent(TermTextNormalizer.pairKey(update.sourceTerm(), update.candidateTranslation()), update);
        }
        return List.copyOf(merged.values());
    }

    private String candidateKey(TranslationCandidateUpdate update) {
        return TermTextNormalizer.pairKey(update.sourceTerm(), update.candidateTranslation());
    }

    private List<TranslationCandidateUpdate> safeCandidateUpdates(List<TranslationCandidateUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return List.of();
        }
        List<TranslationCandidateUpdate> sanitized = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();
        for (TranslationCandidateUpdate update : updates) {
            if (update == null) {
                continue;
            }
            String sourceTerm = TermTextNormalizer.displayText(update.sourceTerm());
            String candidateTranslation = TermTextNormalizer.displayText(update.candidateTranslation());
            if (sourceTerm.isBlank() || candidateTranslation.isBlank()) {
                continue;
            }
            String key = TermTextNormalizer.pairKey(sourceTerm, candidateTranslation);
            if (dedup.add(key)) {
                sanitized.add(new TranslationCandidateUpdate(
                        sourceTerm,
                        candidateTranslation,
                        update.rationale() == null ? "" : update.rationale().trim(),
                        update.requiresReview()
                ));
            }
        }
        return List.copyOf(sanitized);
    }

    private String normalize(String value) {
        return TermTextNormalizer.keyText(value);
    }

    private record ConfirmedTermEntry(
            String displaySourceTerm,
            String displayTargetTerm,
            String targetKey
    ) {
    }

    private ChapterMemorySnapshot evolveChapterMemory(ChapterMemorySnapshot chapterMemory,
                                                      ChunkTranslationDraft draft) {
        if (chapterMemory == null) {
            return null;
        }

        List<String> mergedContinuityNotes = new ArrayList<>(chapterMemory.continuityNotes());
        appendIfPresent(mergedContinuityNotes, draft.transitionNote().nextChunkConnection());
        if (draft.transitionNote().boundaryAdjustmentSuggested()) {
            appendIfPresent(mergedContinuityNotes, "previous chunk may need boundary follow-up");
        }

        return new ChapterMemorySnapshot(
                chapterMemory.chapterId(),
                chapterMemory.confirmedTerms(),
                chapterMemory.unresolvedIssues(),
                List.copyOf(mergedContinuityNotes)
        );
    }

    private void appendIfPresent(List<String> values, String value) {
        if (value == null || value.isBlank() || values.contains(value)) {
            return;
        }
        values.add(value);
    }
}
