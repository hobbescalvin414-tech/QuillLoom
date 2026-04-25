package io.quillloom.infrastructure.translation;

import io.quillloom.domain.translation.TranslationTaskInput;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 对 Agent D 单轮输出做最小规范化，保证下游始终拿到可解析结构。
 */
@Component
public class ChunkTranslationLlmResultNormalizer {

    private static final int MAX_DECISION_NOTE_COUNT = 8;
    private static final int MAX_CONFIRMED_TERM_COUNT = 12;
    private static final int MAX_CANDIDATE_UPDATE_COUNT = 12;
    private static final int MAX_LOOKUP_QUERY_TERM_COUNT = 3;
    private static final int MAX_LOOKUP_ANCHOR_COUNT = 3;

    public ChunkTranslationLlmResult normalize(TranslationTaskInput input, ChunkTranslationLlmResult result) {
        ChunkTranslationLlmResult source = result == null
                ? new ChunkTranslationLlmResult(null, null, null, null, null, null, null)
                : result;

        return new ChunkTranslationLlmResult(
                normalizeTranslatedText(input, source.translatedText()),
                normalizeCommentary(source.translatorCommentary()),
                normalizeDecisionNotes(source.decisionNotes()),
                normalizeConfirmedTermUpdates(source.confirmedTermUpdates()),
                normalizeCandidateUpdates(source.candidateUpdates()),
                normalizeTransitionNote(source.transitionNote()),
                normalizeKnowledgeLookupRequest(source.knowledgeLookupRequest())
        );
    }

    private String normalizeTranslatedText(TranslationTaskInput input, String translatedText) {
        String normalized = translatedText == null ? "" : translatedText.trim();
        if (!normalized.isBlank()) {
            return normalized;
        }
        String sourceText = input.sourceMaterial().chunk().chunk().sourceText();
        return sourceText == null ? "" : sourceText.trim();
    }

    private String normalizeCommentary(String commentary) {
        String normalized = commentary == null ? "" : commentary.trim();
        return normalized.isBlank() ? "本轮按稳定执行输入生成当前 chunk 翻译草稿。" : normalized;
    }

    private List<ChunkTranslationDecisionNoteResult> normalizeDecisionNotes(List<ChunkTranslationDecisionNoteResult> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        return List.copyOf(values.stream()
                .filter(value -> value != null && !isBlank(value.description()))
                .map(value -> new ChunkTranslationDecisionNoteResult(
                        defaultIfBlank(value.type(), "note"),
                        defaultIfBlank(value.sourceAnchor(), "current-chunk"),
                        value.description().trim(),
                        defaultIfBlank(value.recommendation(), "继续沿用当前译法并等待后文确认")
                ))
                .filter(value -> seen.add(value.type() + "|" + value.sourceAnchor() + "|" + value.description()))
                .limit(MAX_DECISION_NOTE_COUNT)
                .toList());
    }

    private List<ConfirmedTermUpdateResult> normalizeConfirmedTermUpdates(List<ConfirmedTermUpdateResult> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        return List.copyOf(values.stream()
                .filter(value -> value != null && !isBlank(value.sourceTerm()) && !isBlank(value.translatedTerm()))
                .map(value -> new ConfirmedTermUpdateResult(value.sourceTerm().trim(), value.translatedTerm().trim()))
                .filter(value -> seen.add(value.sourceTerm()))
                .limit(MAX_CONFIRMED_TERM_COUNT)
                .toList());
    }

    private List<ChunkTranslationCandidateUpdateResult> normalizeCandidateUpdates(List<ChunkTranslationCandidateUpdateResult> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        return List.copyOf(values.stream()
                .filter(value -> value != null && !isBlank(value.sourceTerm()) && !isBlank(value.candidateTranslation()))
                .map(value -> new ChunkTranslationCandidateUpdateResult(
                        value.sourceTerm().trim(),
                        value.candidateTranslation().trim(),
                        defaultIfBlank(value.rationale(), "当前按上下文给出候选译法"),
                        value.requiresReview()
                ))
                .filter(value -> seen.add(value.sourceTerm() + "|" + value.candidateTranslation()))
                .limit(MAX_CANDIDATE_UPDATE_COUNT)
                .toList());
    }

    private ChunkTranslationTransitionNoteResult normalizeTransitionNote(ChunkTranslationTransitionNoteResult transitionNote) {
        if (transitionNote == null) {
            return new ChunkTranslationTransitionNoteResult("", "", false);
        }
        return new ChunkTranslationTransitionNoteResult(
                defaultIfBlank(transitionNote.previousChunkConnection(), ""),
                defaultIfBlank(transitionNote.nextChunkConnection(), ""),
                transitionNote.boundaryAdjustmentSuggested()
        );
    }

    private ChunkTranslationKnowledgeLookupRequestResult normalizeKnowledgeLookupRequest(ChunkTranslationKnowledgeLookupRequestResult request) {
        if (request == null) {
            return null;
        }
        List<String> queryTerms = normalizeStringList(request.queryTerms(), MAX_LOOKUP_QUERY_TERM_COUNT);
        if (queryTerms.isEmpty()) {
            return null;
        }
        return new ChunkTranslationKnowledgeLookupRequestResult(
                defaultIfBlank(request.reason(), "GENERAL_BACKGROUND_GAP"),
                queryTerms,
                normalizeStringList(request.requestedTypes(), MAX_LOOKUP_QUERY_TERM_COUNT),
                normalizeStringList(request.anchors(), MAX_LOOKUP_ANCHOR_COUNT),
                normalizeLimit(request.limit())
        );
    }

    private List<String> normalizeStringList(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        return List.copyOf(values.stream()
                .filter(value -> !isBlank(value))
                .map(String::trim)
                .filter(seen::add)
                .limit(limit)
                .toList());
    }

    private Integer normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 3;
        }
        return Math.min(limit, 3);
    }

    private String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}