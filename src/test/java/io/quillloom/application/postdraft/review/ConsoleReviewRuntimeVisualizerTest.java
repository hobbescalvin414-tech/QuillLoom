package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.ReviewToolExecutionResult;
import io.quillloom.application.postdraft.review.service.ConsoleReviewRuntimeVisualizer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleReviewRuntimeVisualizerTest {

    @Test
    void shouldRenderTraceModeWithRoundActionRepairFailureAndFinishEvents() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleReviewRuntimeVisualizer visualizer = new ConsoleReviewRuntimeVisualizer(
                new PrintStream(output, true, StandardCharsets.UTF_8.name()),
                0,
                ConsoleReviewRuntimeVisualizer.ConsoleMode.TRACE
        );

        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-2"))
                .enterSelectingFocus()
                .withSelectedFocus("chunk-2");
        ReviewToolDecision decision = new ReviewToolDecision(
                "read_next_chunks",
                Map.of("count", 1),
                "need right context"
        );
        ReviewToolExecutionResult execution = ReviewToolExecutionResult.success(
                decision.toCall(),
                runtime,
                "read_next_chunks"
        );

        visualizer.projectStarted(runtime);
        visualizer.focusRoundStarted(runtime);
        visualizer.decisionProduced(runtime, decision);
        visualizer.toolCalled(runtime, decision);
        visualizer.toolCompleted(runtime, execution);
        visualizer.toolRejected(runtime, "unsupported_tool");
        visualizer.repairTriggered(runtime, "structured_output_repair", "invalid_argument:entries");
        visualizer.containableFailureCaptured(runtime, "NEXT_STEP_STRUCTURED_OUTPUT_FAILED", "rawOutput=bad-json");
        visualizer.focusRoundFinished(runtime);
        visualizer.projectFinished(runtime);

        String rendered = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(rendered.contains("event=project_started"));
        assertTrue(rendered.contains("event=focus_round_started"));
        assertTrue(rendered.contains("event=decision_produced"));
        assertTrue(rendered.contains("event=tool_called"));
        assertTrue(rendered.contains("event=tool_completed"));
        assertTrue(rendered.contains("event=tool_rejected"));
        assertTrue(rendered.contains("event=repair_triggered"));
        assertTrue(rendered.contains("event=containable_failure_captured"));
        assertTrue(rendered.contains("event=focus_round_finished"));
        assertTrue(rendered.contains("repairKind=structured_output_repair"));
        assertTrue(rendered.contains("failureCode=NEXT_STEP_STRUCTURED_OUTPUT_FAILED"));
    }

    @Test
    void shouldRenderCompactModeWithoutRoundRepairAndFailureSubdetails() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleReviewRuntimeVisualizer visualizer = new ConsoleReviewRuntimeVisualizer(
                new PrintStream(output, true, StandardCharsets.UTF_8.name()),
                0,
                ConsoleReviewRuntimeVisualizer.ConsoleMode.COMPACT
        );

        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1"))
                .enterSelectingFocus()
                .withSelectedFocus("chunk-1");
        ReviewToolDecision decision = new ReviewToolDecision(
                "complete_working_set",
                Map.of("chunkIds", List.of("chunk-1")),
                "ready to finish"
        );
        ReviewToolExecutionResult execution = ReviewToolExecutionResult.success(
                decision.toCall(),
                runtime,
                "complete_working_set"
        );

        visualizer.projectStarted(runtime);
        visualizer.focusRoundStarted(runtime);
        visualizer.decisionProduced(runtime, decision);
        visualizer.toolCalled(runtime, decision);
        visualizer.toolCompleted(runtime, execution);
        visualizer.toolRejected(runtime, "unsupported_tool");
        visualizer.repairTriggered(runtime, "proposal_repair", "bad proposal");
        visualizer.containableFailureCaptured(runtime, "NEXT_STEP_STRUCTURED_OUTPUT_FAILED", "rawOutput=bad-json");
        visualizer.focusRoundFinished(runtime);

        String rendered = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(rendered.contains("event=project_started"));
        assertTrue(rendered.contains("event=tool_called"));
        assertTrue(rendered.contains("event=tool_completed"));
        assertFalse(rendered.contains("event=focus_round_started"));
        assertFalse(rendered.contains("event=decision_produced"));
        assertFalse(rendered.contains("event=tool_rejected"));
        assertFalse(rendered.contains("event=repair_triggered"));
        assertFalse(rendered.contains("event=containable_failure_captured"));
        assertFalse(rendered.contains("event=focus_round_finished"));
    }

    @Test
    void shouldStaySilentInOffMode() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleReviewRuntimeVisualizer visualizer = new ConsoleReviewRuntimeVisualizer(
                new PrintStream(output, true, StandardCharsets.UTF_8.name()),
                0,
                ConsoleReviewRuntimeVisualizer.ConsoleMode.OFF
        );

        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1"))
                .enterSelectingFocus()
                .withSelectedFocus("chunk-1");
        ReviewToolDecision decision = new ReviewToolDecision(
                "read_confirmed_terms",
                Map.of("sourceTerms", List.of("Louki")),
                "lookup confirmed term"
        );
        ReviewToolExecutionResult execution = ReviewToolExecutionResult.success(
                decision.toCall(),
                runtime,
                "read_confirmed_terms"
        );

        visualizer.projectStarted(runtime);
        visualizer.focusRoundStarted(runtime);
        visualizer.decisionProduced(runtime, decision);
        visualizer.toolCalled(runtime, decision);
        visualizer.toolCompleted(runtime, execution);
        visualizer.toolRejected(runtime, "unsupported_tool");
        visualizer.repairTriggered(runtime, "structured_output_repair", "bad json");
        visualizer.containableFailureCaptured(runtime, "NEXT_STEP_STRUCTURED_OUTPUT_FAILED", "rawOutput=bad-json");
        visualizer.focusRoundFinished(runtime);
        visualizer.projectFinished(runtime);

        String rendered = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(rendered.isBlank());
    }
}
