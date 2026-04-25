package io.quillloom.application.workflow.trace.model;

import java.time.Instant;

public record WorkflowRunManifest(
        String runId,
        String workflowName,
        String projectId,
        Instant startedAt,
        int eventCount
) {
}
