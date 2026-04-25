package io.quillloom.application.workflow.trace;

import io.quillloom.application.workflow.progress.WorkflowProgressListener;
import io.quillloom.application.workflow.trace.model.WorkflowEventStatus;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import io.quillloom.application.workflow.trace.model.WorkflowStageEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WorkflowTraceRecorder {

    private static final ThreadLocal<WorkflowTraceSession> CURRENT = new ThreadLocal<>();

    public WorkflowTraceSession startRun(String runId, String workflowName, String projectId) {
        return startRun(runId, workflowName, projectId, List.of());
    }

    public WorkflowTraceSession startRun(String runId,
                                         String workflowName,
                                         String projectId,
                                         List<WorkflowProgressListener> progressListeners) {
        WorkflowTraceSession session = new WorkflowTraceSession(runId, workflowName, projectId, progressListeners);
        CURRENT.set(session);
        session.notifyStarted();
        return session;
    }

    public Optional<WorkflowTraceSession> currentSession() {
        return Optional.ofNullable(CURRENT.get());
    }

    public WorkflowStageEvent record(WorkflowStage stage,
                                     String eventType,
                                     WorkflowEventStatus status,
                                     String coarseBlockId,
                                     String chunkId,
                                     Map<String, Object> payload) {
        WorkflowTraceSession session = CURRENT.get();
        if (session == null) {
            return null;
        }
        return session.append(stage, eventType, status, coarseBlockId, chunkId, payload);
    }

    public List<WorkflowStageEvent> snapshotEvents() {
        WorkflowTraceSession session = CURRENT.get();
        return session == null ? List.of() : session.events();
    }

    public void completeRun() {
        WorkflowTraceSession session = CURRENT.get();
        if (session != null) {
            session.notifyCompleted();
        }
    }

    public void failRun(Throwable error) {
        WorkflowTraceSession session = CURRENT.get();
        if (session != null) {
            session.notifyFailed(error);
        }
    }

    public void clear() {
        CURRENT.remove();
    }
}
