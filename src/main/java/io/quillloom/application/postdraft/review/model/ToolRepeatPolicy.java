package io.quillloom.application.postdraft.review.model;

public enum ToolRepeatPolicy {
    ALLOW,
    AVOID_SAME_SIGNATURE,
    FORBID_SAME_SIGNATURE_AFTER_SUCCESS,
    STATE_TRANSITION_ONLY
}
