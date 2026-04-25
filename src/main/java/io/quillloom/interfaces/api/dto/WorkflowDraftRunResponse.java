package io.quillloom.interfaces.api.dto;

import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.preprocess.PreprocessDossier;
import io.quillloom.domain.shared.TermTextNormalizer;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.workflow.NovelTranslationWorkflowState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record WorkflowDraftRunResponse(
        String projectId,
        String title,
        String synopsis,
        int chunkCount,
        List<String> fallbackChunkIds,
        Map<String, String> activeGlossary,
        List<CandidateGlossaryItem> candidateGlossary,
        String mergedDraft,
        List<ChunkDraftItem> chunkDrafts
) {

    public static WorkflowDraftRunResponse from(NovelTranslationWorkflowState state) {
        PreprocessDossier dossier = state.preprocessDossier();
        Map<String, ChunkTranslationDraft> draftByChunkId = new LinkedHashMap<>();
        if (state.chunkDrafts() != null) {
            state.chunkDrafts().forEach(draft -> draftByChunkId.put(draft.chunkId(), draft));
        }

        List<ChunkDraftItem> items = dossier.chunkAnnotations().chunks().stream()
                .map(chunk -> toChunkDraftItem(chunk, draftByChunkId.get(chunk.chunk().chunkId())))
                .toList();

        return new WorkflowDraftRunResponse(
                state.projectId(),
                dossier.project().title(),
                dossier.globalAnalysis().bookAnalysis().synopsis(),
                dossier.chunkAnnotations().chunks().size(),
                state.fallbackChunkIds(),
                buildActiveGlossary(state.chunkDrafts()),
                buildCandidateGlossary(state.chunkDrafts()),
                state.draftCompilation() == null ? "" : state.draftCompilation().mergedDraft(),
                items
        );
    }

    private static Map<String, String> buildActiveGlossary(List<ChunkTranslationDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return Map.of();
        }
        Map<String, ConfirmedTermEntry> glossary = new LinkedHashMap<>();
        drafts.forEach(draft -> draft.confirmedTermUpdates().forEach((sourceTerm, targetTerm) ->
                mergeConfirmedTermOrThrow(glossary, sourceTerm, targetTerm, "chunkId=" + draft.chunkId())));
        Map<String, String> display = new LinkedHashMap<>();
        glossary.values().forEach(entry -> display.put(entry.displaySourceTerm(), entry.displayTargetTerm()));
        return Map.copyOf(display);
    }

    private static void mergeConfirmedTermOrThrow(Map<String, ConfirmedTermEntry> confirmedTerms,
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

    private static List<CandidateGlossaryItem> buildCandidateGlossary(List<ChunkTranslationDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        Map<String, CandidateAccumulator> grouped = new LinkedHashMap<>();
        for (ChunkTranslationDraft draft : drafts) {
            for (TranslationCandidateUpdate update : draft.candidateUpdates()) {
                if (update == null || isBlank(update.sourceTerm()) || isBlank(update.candidateTranslation())) {
                    continue;
                }
                String sourceTerm = TermTextNormalizer.displayText(update.sourceTerm());
                String candidateTranslation = TermTextNormalizer.displayText(update.candidateTranslation());
                CandidateAccumulator accumulator = grouped.computeIfAbsent(
                        TermTextNormalizer.keyText(sourceTerm),
                        ignored -> new CandidateAccumulator()
                );
                if (accumulator.sourceTerm == null) {
                    accumulator.sourceTerm = sourceTerm;
                }
                if (accumulator.candidateTranslationKeys.add(TermTextNormalizer.keyText(candidateTranslation))) {
                    accumulator.candidateTranslations.add(candidateTranslation);
                }
                if (!isBlank(update.rationale())) {
                    accumulator.rationales.add(update.rationale().trim());
                }
                accumulator.requiresReview = accumulator.requiresReview || update.requiresReview();
            }
        }
        List<CandidateGlossaryItem> items = new ArrayList<>();
        grouped.forEach((sourceKey, accumulator) -> items.add(new CandidateGlossaryItem(
                accumulator.sourceTerm,
                List.copyOf(accumulator.candidateTranslations),
                List.copyOf(accumulator.rationales),
                accumulator.requiresReview
        )));
        return List.copyOf(items);
    }

    private static ChunkDraftItem toChunkDraftItem(ChunkAnnotation chunk,
                                                   ChunkTranslationDraft draft) {
        return new ChunkDraftItem(
                chunk.chunk().chunkId(),
                chunk.chunk().sequence(),
                chunk.chunk().sourceText(),
                chunk.summary(),
                chunk.entities(),
                draft == null ? "" : draft.translatedText(),
                draft == null ? "" : draft.translatorCommentary()
        );
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record CandidateGlossaryItem(
            String sourceTerm,
            List<String> candidateTranslations,
            List<String> rationales,
            boolean requiresReview
    ) {
    }

    public record ChunkDraftItem(
            String chunkId,
            int sequence,
            String sourceText,
            String summary,
            List<String> entities,
            String translatedText,
            String translatorCommentary
    ) {
    }

    private static final class CandidateAccumulator {
        private String sourceTerm;
        private final Set<String> candidateTranslationKeys = new LinkedHashSet<>();
        private final Set<String> candidateTranslations = new LinkedHashSet<>();
        private final Set<String> rationales = new LinkedHashSet<>();
        private boolean requiresReview;
    }

    private record ConfirmedTermEntry(
            String displaySourceTerm,
            String displayTargetTerm,
            String targetKey
    ) {
    }
}
