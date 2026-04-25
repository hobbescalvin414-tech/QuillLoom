package io.quillloom.application.workflow.trace;

import io.quillloom.application.workflow.progress.WorkflowProgressListener;
import io.quillloom.application.workflow.trace.model.WorkflowEventStatus;
import io.quillloom.application.workflow.trace.model.WorkflowRunManifest;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import io.quillloom.application.workflow.trace.model.WorkflowStageEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowTraceRecorderProgressTest {

    @Test
    void shouldDispatchProgressEventsAcrossRecorderInstances() {
        WorkflowTraceRecorder starter = new WorkflowTraceRecorder();
        WorkflowTraceRecorder emitter = new WorkflowTraceRecorder();
        RecordingProgressListener listener = new RecordingProgressListener();

        starter.startRun("run-1", "draft-workflow", "project-1", List.of(listener));
        emitter.record(
                WorkflowStage.CHUNK_ANNOTATION,
                "chunk_annotation_completed",
                WorkflowEventStatus.SUCCEEDED,
                "block-1",
                "chunk-1",
                Map.of()
        );
        starter.completeRun();
        starter.clear();

        assertEquals(1, listener.started.size());
        assertEquals(1, listener.events.size());
        assertEquals("chunk_annotation_completed", listener.events.get(0).eventType());
        assertEquals(1, listener.completed.size());
    }

    private static final class RecordingProgressListener implements WorkflowProgressListener {
        private final List<WorkflowRunManifest> started = new ArrayList<>();
        private final List<WorkflowStageEvent> events = new ArrayList<>();
        private final List<WorkflowRunManifest> completed = new ArrayList<>();

        @Override
        public void onRunStarted(WorkflowRunManifest manifest) {
            started.add(manifest);
        }

        @Override
        public void onEvent(WorkflowStageEvent event) {
            events.add(event);
        }

        @Override
        public void onRunCompleted(WorkflowRunManifest manifest) {
            completed.add(manifest);
        }
    }
}
