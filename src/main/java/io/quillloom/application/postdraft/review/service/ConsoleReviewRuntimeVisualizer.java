package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.model.HumanReviewRequest;
import io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.ReviewToolExecutionResult;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class ConsoleReviewRuntimeVisualizer implements ReviewRuntimeVisualizer {

    private final PrintStream out;
    private final int previewMaxLength;

    public ConsoleReviewRuntimeVisualizer() {
        this(System.out, 120);
    }

    public ConsoleReviewRuntimeVisualizer(PrintStream out) {
        this(out, 120);
    }

    public ConsoleReviewRuntimeVisualizer(PrintStream out, int previewMaxLength) {
        this.out = Objects.requireNonNull(out, "out");
        if (previewMaxLength < 0) {
            throw new IllegalArgumentException("previewMaxLength must not be negative");
        }
        this.previewMaxLength = previewMaxLength;
    }

    @Override
    public void projectStarted(ProjectReviewRuntimeSession runtime) {
        printLine(
                "project_started",
                "projectId=" + safe(runtime.projectId()),
                "pending=" + runtime.pendingChunkIds().size(),
                "completed=" + runtime.completedChunkOutcomes().size()
        );
    }

    @Override
    public void focusSelected(ProjectReviewRuntimeSession runtime) {
        printLine(
                "focus_selected",
                "projectId=" + safe(runtime.projectId()),
                "anchor=" + safe(runtime.currentFocusChunkId().orElse("")),
                "workingSet=" + formatList(runtime.workingSet().chunkIds()),
                "pending=" + runtime.pendingChunkIds().size(),
                "completed=" + runtime.completedChunkOutcomes().size()
        );
    }

    @Override
    public void toolCalled(ProjectReviewRuntimeSession runtime, ReviewToolDecision decision) {
        printLine(
                "tool_called",
                "projectId=" + safe(runtime.projectId()),
                "anchor=" + safe(runtime.currentFocusChunkId().orElse("")),
                "workingSet=" + formatList(runtime.workingSet().chunkIds()),
                "tool=" + safe(decision.toolName()),
                "arguments=" + preview(String.valueOf(decision.arguments())),
                "reason=" + preview(decision.reason())
        );
    }

    @Override
    public void toolCompleted(ProjectReviewRuntimeSession beforeRuntime,
                              ReviewToolExecutionResult executionResult) {
        ProjectReviewRuntimeSession afterRuntime = executionResult.nextRuntime();
        String status = executionResult.success() ? "success" : "rejected";
        String detail = executionResult.success()
                ? executionResult.summary()
                : executionResult.rejection().rejectionReason();
        printLine(
                "tool_completed",
                "projectId=" + safe(afterRuntime.projectId()),
                "anchor=" + safe(afterRuntime.currentFocusChunkId().orElse("")),
                "tool=" + safe(executionResult.toolCall().toolName()),
                "status=" + status,
                "summary=" + preview(detail),
                "workingSet=" + formatList(afterRuntime.workingSet().chunkIds())
        );

        List<String> completedChunkIds = completedChunkIds(beforeRuntime, afterRuntime);
        if (!completedChunkIds.isEmpty()) {
            printLine(
                    "chunk_completed",
                    "projectId=" + safe(afterRuntime.projectId()),
                    "chunkIds=" + formatList(completedChunkIds),
                    "completed=" + afterRuntime.completedChunkOutcomes().size(),
                    "pending=" + afterRuntime.pendingChunkIds().size()
            );
        }

        Optional<HumanReviewRequest> request = afterRuntime.humanReviewRequest();
        if (request.isPresent() && beforeRuntime.humanReviewRequest().isEmpty()) {
            printLine(
                    "human_review_requested",
                    "projectId=" + safe(afterRuntime.projectId()),
                    "anchor=" + safe(afterRuntime.currentFocusChunkId().orElse("")),
                    "question=" + preview(request.orElseThrow().questionForHuman()),
                    "reason=" + safe(request.orElseThrow().requestReason()),
                    "resumeHint=" + preview(request.orElseThrow().resumeHint())
            );
        }
    }

    @Override
    public void projectFinished(ProjectReviewRuntimeSession runtime) {
        printLine(
                "project_finished",
                "projectId=" + safe(runtime.projectId()),
                "state=" + runtime.state().name(),
                "stopReason=" + runtime.stopReason().name(),
                "agentStopReason=" + runtime.agentStopReason().name(),
                "completed=" + runtime.completedChunkOutcomes().size(),
                "pending=" + runtime.pendingChunkIds().size(),
                latestFailureDiagnostic(runtime).map(value -> "diagnostic=" + preview(value)).orElse("")
        );
    }

    private Optional<String> latestFailureDiagnostic(ProjectReviewRuntimeSession runtime) {
        if (runtime.processTrail().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(runtime.processTrail().get(runtime.processTrail().size() - 1));
    }

    private List<String> completedChunkIds(ProjectReviewRuntimeSession beforeRuntime,
                                           ProjectReviewRuntimeSession afterRuntime) {
        List<String> beforeIds = beforeRuntime.completedChunkOutcomes().stream()
                .map(ProjectChunkReviewOutcome::chunkId)
                .toList();
        ArrayList<String> added = new ArrayList<>();
        for (ProjectChunkReviewOutcome outcome : afterRuntime.completedChunkOutcomes()) {
            if (!beforeIds.contains(outcome.chunkId())) {
                added.add(outcome.chunkId());
            }
        }
        return List.copyOf(added);
    }

    private void printLine(String eventType, String... parts) {
        String tail = List.of(parts).stream()
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" "));
        out.println("[review-agent] event=" + eventType + " " + tail);
    }

    private String formatList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return "[" + values.stream()
                .map(this::safe)
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(",")) + "]";
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.trim().replace('\r', ' ').replace('\n', ' ');
    }

    private String preview(String value) {
        String normalized = safe(value);
        if (previewMaxLength == 0 || normalized.length() <= previewMaxLength) {
            return normalized;
        }
        return normalized.substring(0, previewMaxLength) + "...";
    }
}
