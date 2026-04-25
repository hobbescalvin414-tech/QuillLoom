package io.quillloom.infrastructure.llm;

import java.time.Duration;

public final class WorkflowFixedLlmTimeouts {

    private static final Duration STANDARD_TIMEOUT = Duration.ofMinutes(10);

    private WorkflowFixedLlmTimeouts() {
    }

    public static Duration standardTimeout() {
        return STANDARD_TIMEOUT;
    }
}
