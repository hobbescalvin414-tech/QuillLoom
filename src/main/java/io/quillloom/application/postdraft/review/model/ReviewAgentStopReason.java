package io.quillloom.application.postdraft.review.model;

public enum ReviewAgentStopReason {
    NONE,
    WORKING_SET_COMPLETED,
    HUMAN_REVIEW_REQUIRED,
    MAX_BUDGET_REACHED,
    NO_PROGRESS,
    LLM_PORT_MISSING,
    PROJECT_COMPLETED,
    FAILED
}
