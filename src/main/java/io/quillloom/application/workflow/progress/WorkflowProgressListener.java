package io.quillloom.application.workflow.progress;

import io.quillloom.application.workflow.trace.model.WorkflowRunManifest;
import io.quillloom.application.workflow.trace.model.WorkflowStageEvent;

public interface WorkflowProgressListener {

    default void onRunStarted(WorkflowRunManifest manifest) {
    }

    default void onEvent(WorkflowStageEvent event) {
    }

    default void onRunCompleted(WorkflowRunManifest manifest) {
    }

    default void onRunFailed(WorkflowRunManifest manifest, Throwable error) {
    }
}
