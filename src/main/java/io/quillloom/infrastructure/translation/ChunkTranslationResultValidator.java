package io.quillloom.infrastructure.translation;

import io.quillloom.application.translation.runtime.KnowledgeGapReason;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.translation.TranslationTaskInput;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对 Agent D 的结构化结果做执行层兜底校验。
 * 当前重点约束 confirmedTermUpdates、translatorCommentary、decisionNotes、transitionNote 与补卡请求的边界。
 */
@Component
public class ChunkTranslationResultValidator {

    private static final Set<String> ALLOWED_DECISION_NOTE_TYPES = Set.of(
            "risk",
            "issue",
            "unresolved",
            "ambiguity",
            "consistency",
            "style",
            "context",
            "confirmed-term-conflict",
            "text-boundary-warning",
            "glossary-compliance-warning"
    );

    private static final Set<String> ALLOWED_LOOKUP_REASONS = Set.of(
            KnowledgeGapReason.MISSING_CHARACTER_CONTEXT.name(),
            KnowledgeGapReason.MISSING_TERM_EXPLANATION.name(),
            KnowledgeGapReason.MISSING_SETTING_CONTEXT.name(),
            KnowledgeGapReason.MISSING_CULTURAL_BACKGROUND.name(),
            KnowledgeGapReason.MISSING_HISTORICAL_BACKGROUND.name(),
            KnowledgeGapReason.MISSING_IMAGERY_CONTEXT.name(),
            KnowledgeGapReason.GENERAL_BACKGROUND_GAP.name()
    );

    private static final Set<String> ALLOWED_LOOKUP_CARD_TYPES = Set.of(
            KnowledgeCardType.CHARACTER_PROFILE.name(),
            KnowledgeCardType.TERM_EXPLANATION.name(),
            KnowledgeCardType.SETTING_ENTRY.name(),
            KnowledgeCardType.CULTURAL_BACKGROUND.name(),
            KnowledgeCardType.HISTORICAL_BACKGROUND.name(),
            KnowledgeCardType.IMAGERY.name()
    );

    private static final List<String> COMMENTARY_FORBIDDEN_HINTS = List.of(
            "decisionnote",
            "decisionnotes",
            "confirmedterm",
            "confirmedtermupdates",
            "candidateupdate",
            "candidateupdates",
            "transitionnote",
            "knowledgelookuprequest",
            "queryterms",
            "requestedtypes",
            "previouschunk",
            "nextchunk",
            "boundaryadjustment",
            "未决",
            "候选",
            "确认术语",
            "已确认术语",
            "上一 chunk",
            "下一 chunk",
            "衔接提示",
            "补卡请求"
    );

    private static final List<String> TRANSITION_NOTE_FORBIDDEN_HINTS = List.of(
            "术语",
            "译名",
            "confirmed",
            "candidate",
            "coarse block",
            "粗分块",
            "re-split",
            "resplit",
            "重新分块",
            "重切",
            "切分",
            "改chunk",
            "改分块",
            "补卡"
    );

    private final TranslatedTextIssueDetector translatedTextIssueDetector;
    private final GlossaryComplianceIssueDetector glossaryComplianceIssueDetector;

    public ChunkTranslationResultValidator() {
        this(new TranslatedTextIssueDetector(), new GlossaryComplianceIssueDetector());
    }

    public ChunkTranslationResultValidator(TranslatedTextIssueDetector translatedTextIssueDetector) {
        this(translatedTextIssueDetector, new GlossaryComplianceIssueDetector());
    }

    public ChunkTranslationResultValidator(TranslatedTextIssueDetector translatedTextIssueDetector,
                                           GlossaryComplianceIssueDetector glossaryComplianceIssueDetector) {
        this.translatedTextIssueDetector = translatedTextIssueDetector;
        this.glossaryComplianceIssueDetector = glossaryComplianceIssueDetector;
    }

    public ChunkTranslationLlmResult validate(TranslationTaskInput input, ChunkTranslationLlmResult result) {
        Map<String, String> existingConfirmedTerms = input.executionContextView().confirmedTerms();
        List<ChunkTranslationDecisionNoteResult> decisionNotes = sanitizeDecisionNotes(result.decisionNotes());
        decisionNotes.addAll(sanitizeTextBoundaryWarnings(input.sourceMaterial().project().targetLanguage(), result.translatedText()));
        decisionNotes.addAll(glossaryComplianceIssueDetector.detect(existingConfirmedTerms, result.translatedText()));
        Map<String, ConfirmedTermUpdateResult> allowedConfirmedUpdates = new LinkedHashMap<>();

        for (ConfirmedTermUpdateResult update : safeList(result.confirmedTermUpdates())) {
            if (update == null) {
                continue;
            }
            String sourceTerm = trimToNull(update.sourceTerm());
            String translatedTerm = trimToNull(update.translatedTerm());
            if (sourceTerm == null || translatedTerm == null) {
                continue;
            }

            String existingTranslation = existingConfirmedTerms.get(sourceTerm);
            if (existingTranslation != null) {
                if (!existingTranslation.equals(translatedTerm)) {
                    decisionNotes.add(new ChunkTranslationDecisionNoteResult(
                            "confirmed-term-conflict",
                            sourceTerm,
                            "已确认术语不可在当前翻译阶段被改写：" + sourceTerm,
                            "沿用既有确认译名：" + existingTranslation
                    ));
                }
                continue;
            }

            allowedConfirmedUpdates.putIfAbsent(sourceTerm, new ConfirmedTermUpdateResult(sourceTerm, translatedTerm));
        }

        return new ChunkTranslationLlmResult(
                result.translatedText(),
                sanitizeTranslatorCommentary(result.translatorCommentary()),
                List.copyOf(decisionNotes),
                List.copyOf(allowedConfirmedUpdates.values()),
                safeList(result.candidateUpdates()),
                sanitizeTransitionNote(result.transitionNote()),
                sanitizeKnowledgeLookupRequest(result.knowledgeLookupRequest())
        );
    }

    private String sanitizeTranslatorCommentary(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return "";
        }

        List<String> keptLines = new ArrayList<>();
        for (String line : normalized.split("\\R")) {
            String trimmedLine = trimToNull(line);
            if (trimmedLine == null) {
                continue;
            }
            String lowerCase = trimmedLine.toLowerCase();
            boolean forbidden = false;
            for (String forbiddenHint : COMMENTARY_FORBIDDEN_HINTS) {
                if (lowerCase.contains(forbiddenHint.toLowerCase())) {
                    forbidden = true;
                    break;
                }
            }
            if (!forbidden) {
                keptLines.add(trimmedLine);
            }
        }

        return keptLines.isEmpty() ? "" : String.join("\n", keptLines);
    }

    private List<ChunkTranslationDecisionNoteResult> sanitizeTextBoundaryWarnings(String targetLanguage, String translatedText) {
        List<ChunkTranslationDecisionNoteResult> warnings = new ArrayList<>();
        for (TranslatedTextIssue issue : translatedTextIssueDetector.detect(targetLanguage, translatedText)) {
            warnings.add(new ChunkTranslationDecisionNoteResult(
                    "text-boundary-warning",
                    "translatedText",
                    issue.description(),
                    "请在下一轮仅修正文边界，移除解释性补写或知识卡泄漏。"
            ));
        }
        return warnings;
    }

    private List<ChunkTranslationDecisionNoteResult> sanitizeDecisionNotes(List<ChunkTranslationDecisionNoteResult> values) {
        List<ChunkTranslationDecisionNoteResult> sanitized = new ArrayList<>();
        Set<String> dedupKeys = new LinkedHashSet<>();
        for (ChunkTranslationDecisionNoteResult value : safeList(values)) {
            if (value == null) {
                continue;
            }
            String description = trimToNull(value.description());
            if (description == null) {
                continue;
            }
            String type = normalizeDecisionNoteType(value.type());
            String sourceAnchor = nullToEmpty(value.sourceAnchor()).trim();
            String recommendation = nullToEmpty(value.recommendation()).trim();
            String dedupKey = type + "|" + sourceAnchor + "|" + description + "|" + recommendation;
            if (dedupKeys.add(dedupKey)) {
                sanitized.add(new ChunkTranslationDecisionNoteResult(type, sourceAnchor, description, recommendation));
            }
        }
        return sanitized;
    }

    private String normalizeDecisionNoteType(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return "issue";
        }
        String lowerCase = normalized.toLowerCase();
        return ALLOWED_DECISION_NOTE_TYPES.contains(lowerCase) ? lowerCase : "issue";
    }

    private ChunkTranslationTransitionNoteResult sanitizeTransitionNote(ChunkTranslationTransitionNoteResult value) {
        if (value == null) {
            return new ChunkTranslationTransitionNoteResult("", "", false);
        }

        String previousChunkConnection = sanitizeTransitionText(value.previousChunkConnection());
        String nextChunkConnection = sanitizeTransitionText(value.nextChunkConnection());
        boolean boundaryAdjustmentSuggested = value.boundaryAdjustmentSuggested()
                && (!previousChunkConnection.isBlank() || !nextChunkConnection.isBlank());

        return new ChunkTranslationTransitionNoteResult(
                previousChunkConnection,
                nextChunkConnection,
                boundaryAdjustmentSuggested
        );
    }

    private ChunkTranslationKnowledgeLookupRequestResult sanitizeKnowledgeLookupRequest(ChunkTranslationKnowledgeLookupRequestResult value) {
        if (value == null) {
            return null;
        }
        List<String> queryTerms = sanitizeLookupStrings(value.queryTerms(), 3);
        if (queryTerms.isEmpty()) {
            return null;
        }
        String reason = normalizeLookupReason(value.reason());
        List<String> requestedTypes = sanitizeLookupTypes(value.requestedTypes());
        List<String> anchors = sanitizeLookupStrings(value.anchors(), 3);
        int limit = value.limit() == null || value.limit() <= 0 ? 3 : Math.min(value.limit(), 3);
        return new ChunkTranslationKnowledgeLookupRequestResult(reason, queryTerms, requestedTypes, anchors, limit);
    }

    private String sanitizeTransitionText(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return "";
        }
        String lowerCase = normalized.toLowerCase();
        for (String forbiddenHint : TRANSITION_NOTE_FORBIDDEN_HINTS) {
            if (lowerCase.contains(forbiddenHint.toLowerCase())) {
                return "";
            }
        }
        return normalized;
    }

    private String normalizeLookupReason(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return KnowledgeGapReason.GENERAL_BACKGROUND_GAP.name();
        }
        String upperCase = normalized.trim().toUpperCase(Locale.ROOT);
        return ALLOWED_LOOKUP_REASONS.contains(upperCase) ? upperCase : KnowledgeGapReason.GENERAL_BACKGROUND_GAP.name();
    }

    private List<String> sanitizeLookupTypes(List<String> values) {
        List<String> normalized = sanitizeLookupStrings(values, 3).stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(ALLOWED_LOOKUP_CARD_TYPES::contains)
                .toList();
        return normalized.isEmpty() ? List.of() : normalized;
    }

    private List<String> sanitizeLookupStrings(List<String> values, int maxCount) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> dedup = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized == null) {
                continue;
            }
            dedup.add(normalized);
            if (dedup.size() >= maxCount) {
                break;
            }
        }
        return List.copyOf(dedup);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
