package io.quillloom.application.postdraft.review.model;

public enum ReviewProjectStopReason {
    NONE,
    HUMAN_REVIEW_REQUIRED,
    NO_PROGRESS,
    LLM_CALL_FAILED,
    WALL_CLOCK_TIMEOUT,
    PROJECT_COMPLETED,
    FAILED
}
