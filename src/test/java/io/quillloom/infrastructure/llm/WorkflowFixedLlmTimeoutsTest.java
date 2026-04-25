package io.quillloom.infrastructure.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowFixedLlmTimeoutsTest {

    @Test
    void shouldUseTenMinutesForFixedWorkflowLlmTimeout() {
        assertEquals(Duration.ofMinutes(10), WorkflowFixedLlmTimeouts.standardTimeout());
    }
}
