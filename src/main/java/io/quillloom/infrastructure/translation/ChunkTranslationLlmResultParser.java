package io.quillloom.infrastructure.translation;

import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.ChunkTranslationDraft;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.domain.translation.TranslationDecisionNote;
import io.quillloom.domain.translation.TranslationTaskInput;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 LLM 结构化结果收口为领域层的 chunk 翻译草稿。
 */
@Component
public class ChunkTranslationLlmResultParser {

    public ChunkTranslationDraft parse(TranslationTaskInput input, ChunkTranslationLlmResult result) {
        return new ChunkTranslationDraft(
                input.sourceMaterial().chunk().chunk().chunkId(),
                result.translatedText(),
                result.translatorCommentary(),
                parseDecisionNotes(result.decisionNotes()),
                parseConfirmedTerms(result.confirmedTermUpdates()),
                parseCandidateUpdates(result.candidateUpdates()),
                parseTransitionNote(result.transitionNote())
        );
    }

    private List<TranslationDecisionNote> parseDecisionNotes(List<ChunkTranslationDecisionNoteResult> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(value -> new TranslationDecisionNote(
                        value.type(),
                        value.sourceAnchor(),
                        value.description(),
                        value.recommendation()
                ))
                .toList();
    }

    private Map<String, String> parseConfirmedTerms(List<ConfirmedTermUpdateResult> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> results = new LinkedHashMap<>();
        for (ConfirmedTermUpdateResult value : values) {
            results.put(value.sourceTerm(), value.translatedTerm());
        }
        return Map.copyOf(results);
    }

    private List<TranslationCandidateUpdate> parseCandidateUpdates(List<ChunkTranslationCandidateUpdateResult> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(value -> new TranslationCandidateUpdate(
                        value.sourceTerm(),
                        value.candidateTranslation(),
                        value.rationale(),
                        value.requiresReview()
                ))
                .toList();
    }

    private ChunkTransitionNote parseTransitionNote(ChunkTranslationTransitionNoteResult value) {
        return new ChunkTransitionNote(
                value.previousChunkConnection(),
                value.nextChunkConnection(),
                value.boundaryAdjustmentSuggested()
        );
    }
}