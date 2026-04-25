package io.quillloom.application.workflow.trace;

import io.quillloom.application.workflow.progress.WorkflowProgressListener;
import io.quillloom.application.workflow.trace.model.WorkflowEventStatus;
import io.quillloom.application.workflow.trace.model.WorkflowRunManifest;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import io.quillloom.application.workflow.trace.model.WorkflowStageEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class WorkflowTraceSession {

    private final String runId;
    private final String workflowName;
    private final String projectId;
    private final Instant startedAt;
    private final AtomicLong sequence;
    private final List<WorkflowStageEvent> events;
    private final List<WorkflowProgressListener> progressListeners;

    public WorkflowTraceSession(String runId, String workflowName, String projectId) {
        this(runId, workflowName, projectId, List.of());
    }

    public WorkflowTraceSession(String runId,
                                String workflowName,
                                String projectId,
                                List<WorkflowProgressListener> progressListeners) {
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.workflowName = Objects.requireNonNull(workflowName, "workflowName must not be null");
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.startedAt = Instant.now();
        this.sequence = new AtomicLong();
        this.events = new ArrayList<>();
        this.progressListeners = progressListeners == null ? List.of() : List.copyOf(progressListeners);
    }

    public void notifyStarted() {
        WorkflowRunManifest manifest = toManifest();
        progressListeners.forEach(listener -> listener.onRunStarted(manifest));
    }

    public void notifyCompleted() {
        WorkflowRunManifest manifest = toManifest();
        progressListeners.forEach(listener -> listener.onRunCompleted(manifest));
    }

    public void notifyFailed(Throwable error) {
        WorkflowRunManifest manifest = toManifest();
        progressListeners.forEach(listener -> listener.onRunFailed(manifest, error));
    }

    public WorkflowStageEvent append(WorkflowStage stage,
                                     String eventType,
                                     WorkflowEventStatus status,
                                     String coarseBlockId,
                                     String chunkId,
                                     Map<String, Object> payload) {
        WorkflowStageEvent event = new WorkflowStageEvent(
                runId,
                workflowName,
                sequence.incrementAndGet(),
                Instant.now(),
                Objects.requireNonNull(stage, "stage must not be null"),
                Objects.requireNonNull(eventType, "eventType must not be null"),
                Objects.requireNonNull(status, "status must not be null"),
                projectId,
                coarseBlockId,
                chunkId,
                payload == null ? Map.of() : Map.copyOf(payload)
        );
        events.add(event);
        progressListeners.forEach(listener -> listener.onEvent(event));
        return event;
    }

    public WorkflowRunManifest toManifest() {
        return new WorkflowRunManifest(runId, workflowName, projectId, startedAt, events.size());
    }

    public List<WorkflowStageEvent> events() {
        return List.copyOf(events);
    }
}
