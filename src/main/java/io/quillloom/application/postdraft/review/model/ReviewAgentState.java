package io.quillloom.application.postdraft.review.model;

public enum ReviewAgentState {
    INITIALIZING,
    SELECTING_FOCUS,
    INVESTIGATING,
    EVALUATING,
    REVISING,
    COMPRESSING_MEMORY,
    FINALIZING,
    WAITING_HUMAN,
    COMPLETED,
    FAILED
}
