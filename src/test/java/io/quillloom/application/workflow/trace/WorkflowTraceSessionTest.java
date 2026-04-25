package io.quillloom.application.workflow.trace;

import io.quillloom.application.workflow.trace.model.WorkflowEventStatus;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowTraceSessionTest {

    @Test
    void shouldAppendEventsWithMonotonicSequence() {
        WorkflowTraceSession session = new WorkflowTraceSession("run-1", "draft-workflow", "project-1");

        var first = session.append(
                WorkflowStage.PREPROCESS,
                "coarse_planning_started",
                WorkflowEventStatus.SUCCEEDED,
                null,
                null,
                Map.of("input", Map.of("title", "book"))
        );
        var second = session.append(
                WorkflowStage.PREPROCESS,
                "coarse_planning_completed",
                WorkflowEventStatus.SUCCEEDED,
                null,
                null,
                Map.of()
        );

        assertEquals(1L, first.sequence());
        assertEquals(2L, second.sequence());
        assertEquals("run-1", second.runId());
        assertEquals("draft-workflow", second.workflowName());
        assertEquals("project-1", second.projectId());
    }
}
