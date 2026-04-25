package io.quillloom.infrastructure.preprocess;

/**
 * 知识需求来自 chunk 标注中的哪类信号。
 */
public enum KnowledgeNeedSignalSource {
    BACKGROUND_QUESTION,
    TRANSLATION_RISK,
    KEY_EXPRESSION,
    ENTITY,
    UNKNOWN
}
