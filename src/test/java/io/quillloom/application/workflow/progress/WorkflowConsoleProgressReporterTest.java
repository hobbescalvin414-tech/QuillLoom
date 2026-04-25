package io.quillloom.application.workflow.progress;

import io.quillloom.application.workflow.trace.model.WorkflowEventStatus;
import io.quillloom.application.workflow.trace.model.WorkflowRunManifest;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import io.quillloom.application.workflow.trace.model.WorkflowStageEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowConsoleProgressReporterTest {

    @Test
    void shouldRenderWorkflowAndChunkProgressLines() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-09T08:00:00Z"));
        List<String> lines = new ArrayList<>();
        WorkflowConsoleProgressReporter reporter = new WorkflowConsoleProgressReporter(lines::add, clock, Duration.ofSeconds(20), false);

        reporter.onRunStarted(new WorkflowRunManifest("run-1", "draft-workflow", "project-1", clock.instant(), 0));
        reporter.onEvent(new WorkflowStageEvent(
                "run-1",
                "draft-workflow",
                1L,
                clock.instant(),
                WorkflowStage.CHUNK_ANNOTATION,
                "chunk_annotation_prompt_rendered",
                WorkflowEventStatus.SUCCEEDED,
                "project-1",
                "block-1",
                "chunk-3",
                Map.of()
        ));
        reporter.onEvent(new WorkflowStageEvent(
                "run-1",
                "draft-workflow",
                2L,
                clock.instant(),
                WorkflowStage.CHUNK_TRANSLATION,
                "chunk_translation_completed",
                WorkflowEventStatus.SUCCEEDED,
                "project-1",
                "block-1",
                "chunk-3",
                Map.of("compiledResult", Map.of("translatedText", "ok"))
        ));
        reporter.onRunCompleted(new WorkflowRunManifest("run-1", "draft-workflow", "project-1", clock.instant(), 2));

        assertTrue(lines.stream().anyMatch(line -> line.contains("[workflow]") && line.contains("started")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("[annotate]") && line.contains("chunk=chunk-3") && line.contains("started")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("[translate]") && line.contains("chunk=chunk-3") && line.contains("completed")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("[workflow]") && line.contains("completed")));
    }

    @Test
    void shouldEmitHeartbeatWhenNoNewProgressArrives() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-09T08:00:00Z"));
        List<String> lines = new ArrayList<>();
        WorkflowConsoleProgressReporter reporter = new WorkflowConsoleProgressReporter(lines::add, clock, Duration.ofSeconds(20), false);

        reporter.onRunStarted(new WorkflowRunManifest("run-2", "draft-workflow", "project-2", clock.instant(), 0));
        reporter.onEvent(new WorkflowStageEvent(
                "run-2",
                "draft-workflow",
                1L,
                clock.instant(),
                WorkflowStage.CHUNK_TRANSLATION,
                "chunk_translation_prompt_rendered",
                WorkflowEventStatus.SUCCEEDED,
                "project-2",
                "block-2",
                "chunk-8",
                Map.of("round", "draft")
        ));

        clock.advance(Duration.ofSeconds(21));
        reporter.emitHeartbeatIfStalled();

        assertTrue(lines.stream().anyMatch(line -> line.contains("[heartbeat]") && line.contains("chunk=chunk-8") && line.contains("still-running")));
    }

    @Test
    void shouldPrintFailureStackTraceWhenRunFails() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-09T08:00:00Z"));
        List<String> lines = new ArrayList<>();
        WorkflowConsoleProgressReporter reporter = new WorkflowConsoleProgressReporter(lines::add, clock, Duration.ofSeconds(20), false);
        IllegalStateException error = new IllegalStateException("coarse boom", new RuntimeException("root boom"));

        reporter.onRunStarted(new WorkflowRunManifest("run-failed", "draft-workflow", "project-failed", clock.instant(), 0));
        reporter.onRunFailed(new WorkflowRunManifest("run-failed", "draft-workflow", "project-failed", clock.instant(), 1), error);

        String output = String.join("\n", lines);
        assertTrue(output.contains("[workflow] run=run-failed failed reason=coarse boom"));
        assertTrue(output.contains("java.lang.IllegalStateException: coarse boom"));
        assertTrue(output.contains("Caused by: java.lang.RuntimeException: root boom"));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
