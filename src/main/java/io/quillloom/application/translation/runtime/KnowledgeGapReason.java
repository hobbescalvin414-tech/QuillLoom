package io.quillloom.application.translation.runtime;

/**
 * D 在单 chunk loop 内请求补卡时的知识缺口原因。
 */
public enum KnowledgeGapReason {
    MISSING_CHARACTER_CONTEXT,
    MISSING_TERM_EXPLANATION,
    MISSING_SETTING_CONTEXT,
    MISSING_CULTURAL_BACKGROUND,
    MISSING_HISTORICAL_BACKGROUND,
    MISSING_IMAGERY_CONTEXT,
    GENERAL_BACKGROUND_GAP
}