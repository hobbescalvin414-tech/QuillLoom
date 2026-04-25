package io.quillloom.domain.translation;

/**
 * 记录 chunk 翻译过程中产生的未决问题、风险或待确认决策。
 */
public record TranslationDecisionNote(
        String type,
        String sourceAnchor,
        String description,
        String recommendation
) {
}