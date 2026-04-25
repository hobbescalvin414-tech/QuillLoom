package io.quillloom.application.workflow.trace.model;

import java.time.Instant;
import java.util.Map;

public record WorkflowStageEvent(
        String runId,
        String workflowName,
        long sequence,
        Instant timestamp,
        WorkflowStage stage,
        String eventType,
        WorkflowEventStatus status,
        String projectId,
        String coarseBlockId,
        String chunkId,
        Map<String, Object> payload
) {
}
