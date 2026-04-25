package io.quillloom.infrastructure.translation;

import io.quillloom.application.translation.runtime.KnowledgeCardLookupRequest;
import io.quillloom.application.translation.runtime.KnowledgeGapReason;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.translation.TranslationTaskInput;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 根据第 1 轮结果和当前 chunk 信号，规划一次受控的本地知识库补卡请求。
 */
@Component
public class RuleBasedKnowledgeLookupRequestPlanner {

    private static final int MAX_QUERY_TERMS = 3;
    private static final int MAX_RETURNED_CARDS = 3;

    public KnowledgeCardLookupRequest plan(TranslationTaskInput input,
                                           ChunkTranslationLlmResult draftRoundResult) {
        if (input == null || draftRoundResult == null || !input.runtimeOptions().allowKnowledgeCards()) {
            return null;
        }
        KnowledgeGapReason reason = inferReason(input, draftRoundResult);
        List<String> queryTerms = buildQueryTerms(input, draftRoundResult, reason);
        if (queryTerms.isEmpty()) {
            return null;
        }
        return new KnowledgeCardLookupRequest(
                UUID.randomUUID().toString(),
                input.sourceMaterial().chunk().chunk().chunkId(),
                reason,
                queryTerms,
                requestedTypes(reason),
                buildAnchors(input, draftRoundResult),
                MAX_RETURNED_CARDS
        );
    }

    private KnowledgeGapReason inferReason(TranslationTaskInput input,
                                           ChunkTranslationLlmResult draftRoundResult) {
        if (hasCharacterSignal(input, draftRoundResult)) {
            return KnowledgeGapReason.MISSING_CHARACTER_CONTEXT;
        }
        if (hasTermSignal(input, draftRoundResult)) {
            return KnowledgeGapReason.MISSING_TERM_EXPLANATION;
        }
        if (hasSignal(input, draftRoundResult, "setting", "设定", "地点", "地名", "组织", "制度", "器物", "location", "organization", "artifact")) {
            return KnowledgeGapReason.MISSING_SETTING_CONTEXT;
        }
        if (hasSignal(input, draftRoundResult, "culture", "cultural", "文化", "风俗", "习俗", "礼制", "宗教", "称谓")) {
            return KnowledgeGapReason.MISSING_CULTURAL_BACKGROUND;
        }
        if (hasSignal(input, draftRoundResult, "history", "historical", "dynasty", "period", "历史", "时代", "朝代", "沿革")) {
            return KnowledgeGapReason.MISSING_HISTORICAL_BACKGROUND;
        }
        if (hasSignal(input, draftRoundResult, "imagery", "symbol", "allusion", "metaphor", "意象", "象征", "典故", "隐喻")) {
            return KnowledgeGapReason.MISSING_IMAGERY_CONTEXT;
        }
        return KnowledgeGapReason.GENERAL_BACKGROUND_GAP;
    }

    private boolean hasCharacterSignal(TranslationTaskInput input,
                                       ChunkTranslationLlmResult draftRoundResult) {
        if (!input.sourceMaterial().chunk().entities().isEmpty()) {
            Set<String> entitySet = lowercaseSet(input.sourceMaterial().chunk().entities());
            boolean noteMatched = draftRoundResult.decisionNotes().stream().anyMatch(note -> containsAny(entitySet, note.sourceAnchor(), note.description()));
            boolean candidateMatched = draftRoundResult.candidateUpdates().stream().anyMatch(update -> containsAny(entitySet, update.sourceTerm(), update.rationale()));
            if (noteMatched || candidateMatched) {
                return true;
            }
        }
        return hasSignal(input, draftRoundResult, "character", "人物", "角色", "人名", "称谓", "关系", "身份", "name", "person");
    }

    private boolean hasTermSignal(TranslationTaskInput input,
                                  ChunkTranslationLlmResult draftRoundResult) {
        if (!draftRoundResult.candidateUpdates().isEmpty()) {
            return true;
        }
        return hasSignal(input, draftRoundResult, "term", "术语", "译名", "译法", "candidate", "translation");
    }

    private boolean hasSignal(TranslationTaskInput input,
                              ChunkTranslationLlmResult draftRoundResult,
                              String... keywords) {
        List<String> texts = new ArrayList<>();
        input.sourceMaterial().chunk().backgroundQuestions().forEach(texts::add);
        input.sourceMaterial().chunk().translationRisks().forEach(texts::add);
        input.sourceMaterial().chunk().keyExpressions().forEach(texts::add);
        draftRoundResult.decisionNotes().forEach(note -> {
            texts.add(note.sourceAnchor());
            texts.add(note.description());
            texts.add(note.recommendation());
        });
        draftRoundResult.candidateUpdates().forEach(update -> {
            texts.add(update.sourceTerm());
            texts.add(update.rationale());
        });
        for (String text : texts) {
            if (containsKeyword(text, keywords)) {
                return true;
            }
        }
        return false;
    }

    private List<String> buildQueryTerms(TranslationTaskInput input,
                                         ChunkTranslationLlmResult draftRoundResult,
                                         KnowledgeGapReason reason) {
        Set<String> terms = new LinkedHashSet<>();
        switch (reason) {
            case MISSING_CHARACTER_CONTEXT -> {
                addMatchingEntityTerms(terms, input, draftRoundResult);
                input.sourceMaterial().chunk().entities().forEach(value -> addIfPresent(terms, value));
            }
            case MISSING_TERM_EXPLANATION -> {
                draftRoundResult.candidateUpdates().forEach(update -> addIfPresent(terms, update.sourceTerm()));
                input.sourceMaterial().chunk().keyExpressions().forEach(value -> addIfPresent(terms, value));
            }
            case MISSING_SETTING_CONTEXT -> {
                draftRoundResult.decisionNotes().forEach(note -> addIfPresent(terms, note.sourceAnchor()));
                input.sourceMaterial().chunk().entities().forEach(value -> addIfPresent(terms, value));
                input.sourceMaterial().chunk().keyExpressions().forEach(value -> addIfPresent(terms, value));
            }
            case MISSING_CULTURAL_BACKGROUND, MISSING_HISTORICAL_BACKGROUND, GENERAL_BACKGROUND_GAP -> {
                input.sourceMaterial().chunk().backgroundQuestions().forEach(value -> addIfPresent(terms, value));
                input.sourceMaterial().chunk().translationRisks().forEach(value -> addIfPresent(terms, value));
                draftRoundResult.decisionNotes().forEach(note -> addIfPresent(terms, note.description()));
            }
            case MISSING_IMAGERY_CONTEXT -> {
                input.sourceMaterial().chunk().keyExpressions().forEach(value -> addIfPresent(terms, value));
                draftRoundResult.decisionNotes().forEach(note -> addIfPresent(terms, note.sourceAnchor()));
            }
        }
        if (terms.isEmpty()) {
            addIfPresent(terms, input.sourceMaterial().chunk().summary());
        }
        return List.copyOf(terms.stream().limit(MAX_QUERY_TERMS).toList());
    }

    private List<KnowledgeCardType> requestedTypes(KnowledgeGapReason reason) {
        return switch (reason) {
            case MISSING_CHARACTER_CONTEXT -> List.of(KnowledgeCardType.CHARACTER_PROFILE);
            case MISSING_TERM_EXPLANATION -> List.of(KnowledgeCardType.TERM_EXPLANATION);
            case MISSING_SETTING_CONTEXT -> List.of(KnowledgeCardType.SETTING_ENTRY);
            case MISSING_CULTURAL_BACKGROUND -> List.of(KnowledgeCardType.CULTURAL_BACKGROUND);
            case MISSING_HISTORICAL_BACKGROUND -> List.of(KnowledgeCardType.HISTORICAL_BACKGROUND);
            case MISSING_IMAGERY_CONTEXT -> List.of(KnowledgeCardType.IMAGERY);
            case GENERAL_BACKGROUND_GAP -> List.of(
                    KnowledgeCardType.TERM_EXPLANATION,
                    KnowledgeCardType.SETTING_ENTRY,
                    KnowledgeCardType.CULTURAL_BACKGROUND,
                    KnowledgeCardType.HISTORICAL_BACKGROUND,
                    KnowledgeCardType.CHARACTER_PROFILE
            );
        };
    }

    private List<String> buildAnchors(TranslationTaskInput input,
                                      ChunkTranslationLlmResult draftRoundResult) {
        Set<String> anchors = new LinkedHashSet<>();
        draftRoundResult.decisionNotes().forEach(note -> addIfPresent(anchors, note.sourceAnchor()));
        draftRoundResult.candidateUpdates().forEach(update -> addIfPresent(anchors, update.sourceTerm()));
        if (anchors.isEmpty()) {
            input.sourceMaterial().chunk().entities().forEach(value -> addIfPresent(anchors, value));
        }
        return List.copyOf(anchors.stream().limit(MAX_QUERY_TERMS).toList());
    }

    private void addMatchingEntityTerms(Set<String> terms,
                                        TranslationTaskInput input,
                                        ChunkTranslationLlmResult draftRoundResult) {
        Set<String> entities = lowercaseSet(input.sourceMaterial().chunk().entities());
        draftRoundResult.decisionNotes().forEach(note -> {
            if (containsAny(entities, note.sourceAnchor(), note.description())) {
                addIfPresent(terms, note.sourceAnchor());
            }
        });
        draftRoundResult.candidateUpdates().forEach(update -> {
            if (containsAny(entities, update.sourceTerm(), update.rationale())) {
                addIfPresent(terms, update.sourceTerm());
            }
        });
    }

    private Set<String> lowercaseSet(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.toLowerCase(Locale.ROOT));
        }
        return normalized;
    }

    private boolean containsAny(Set<String> values, String... texts) {
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            String normalized = text.toLowerCase(Locale.ROOT);
            for (String value : values) {
                if (normalized.contains(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsKeyword(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void addIfPresent(Set<String> values, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        values.add(value.trim());
    }
}