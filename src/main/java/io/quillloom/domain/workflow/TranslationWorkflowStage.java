package io.quillloom.domain.workflow;

/**
 * 多阶段翻译工作流的显式阶段定义。
 */
public enum TranslationWorkflowStage {
    INITIALIZED,
    PREPROCESSED,
    DRAFTED,
    COMPILED
}