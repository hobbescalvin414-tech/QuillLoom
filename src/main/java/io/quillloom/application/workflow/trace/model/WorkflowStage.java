package io.quillloom.application.workflow.trace.model;

public enum WorkflowStage {
    WORKFLOW,
    PREPROCESS,
    COARSE_PLANNING,
    CHUNK_SEGMENTATION,
    CHUNK_ANNOTATION,
    KNOWLEDGE_ENRICHMENT,
    TRANSLATION_INPUT,
    CHUNK_TRANSLATION
}
