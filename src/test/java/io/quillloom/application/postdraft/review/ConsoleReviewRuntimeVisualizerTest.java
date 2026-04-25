package io.quillloom.application.postdraft.review;

import io.quillloom.application.postdraft.review.model.HumanReviewRequest;
import io.quillloom.application.postdraft.review.model.ProjectChunkReviewOutcome;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.ReviewAgentState;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.model.ReviewProcessSummary;
import io.quillloom.application.postdraft.review.model.ReviewStrategy;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.ReviewToolExecutionResult;
import io.quillloom.application.postdraft.review.service.ConsoleReviewRuntimeVisualizer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleReviewRuntimeVisualizerTest {

    @Test
    void shouldPrintReadableProgressLinesForProjectRun() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleReviewRuntimeVisualizer visualizer = new ConsoleReviewRuntimeVisualizer(
                new PrintStream(output, true, StandardCharsets.UTF_8.name()),
                120
        );

        ProjectReviewRuntimeSession before = ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1"))
                .enterSelectingFocus()
                .withSelectedFocus("chunk-1");
        ReviewToolDecision decision = new ReviewToolDecision(
                "complete_working_set",
                Map.of("chunkIds", List.of("chunk-1")),
                "ready to finish"
        );
        ProjectChunkReviewOutcome outcome = new ProjectChunkReviewOutcome(
                "chunk-1",
                "final text",
                ReviewStrategy.LIGHT_EDIT,
                new ReviewProcessSummary(
                        "project-1",
                        ReviewFocus.forChunk("chunk-1"),
                        ReviewStrategy.LIGHT_EDIT,
                        Set.of(),
                        List.of("evidence"),
                        "done"
                )
        );
        ProjectReviewRuntimeSession after = before.completeWorkingSet(List.of(outcome)).completeProject();
        ReviewToolExecutionResult execution = ReviewToolExecutionResult.success(decision.toCall(), after, "complete_working_set");

        visualizer.projectStarted(before);
        visualizer.focusSelected(before);
        visualizer.toolCalled(before, decision);
        visualizer.toolCompleted(before, execution);
        visualizer.projectFinished(after);

        String rendered = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(rendered.contains("[review-agent] event=project_started"));
        assertTrue(rendered.contains("[review-agent] event=focus_selected"));
        assertTrue(rendered.contains("[review-agent] event=tool_called"));
        assertTrue(rendered.contains("[review-agent] event=tool_completed"));
        assertTrue(rendered.contains("[review-agent] event=chunk_completed"));
        assertTrue(rendered.contains("[review-agent] event=project_finished"));
        assertTrue(rendered.contains("chunkIds=[chunk-1]"));
    }

    @Test
    void shouldKeepFullReasonWhenPreviewLimitIsZero() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleReviewRuntimeVisualizer visualizer = new ConsoleReviewRuntimeVisualizer(
                new PrintStream(output, true, StandardCharsets.UTF_8.name()),
                0
        );

        ProjectReviewRuntimeSession before = ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1"))
                .enterSelectingFocus()
                .withSelectedFocus("chunk-1");
        String fullReason = "0123456789".repeat(20);
        ReviewToolDecision decision = new ReviewToolDecision(
                "read_confirmed_terms",
                Map.of("sourceTerms", List.of("Louki")),
                fullReason
        );

        visualizer.toolCalled(before, decision);

        String rendered = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(rendered.contains("reason=" + fullReason));
    }

    @Test
    void shouldPrintStructuredArgumentsWhenToolIsCalled() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleReviewRuntimeVisualizer visualizer = new ConsoleReviewRuntimeVisualizer(
                new PrintStream(output, true, StandardCharsets.UTF_8.name()),
                0
        );

        ProjectReviewRuntimeSession before = ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1"))
                .enterSelectingFocus()
                .withSelectedFocus("chunk-1");
        ReviewToolDecision decision = new ReviewToolDecision(
                "read_confirmed_terms",
                Map.of("sourceTerms", List.of("Le Bouquet")),
                "reason says lookup Le Bouquet"
        );

        visualizer.toolCalled(before, decision);

        String rendered = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(rendered.contains("arguments={sourceTerms=[Le Bouquet]}"));
    }

    @Test
    void shouldPrintFullToolResultSummaryWhenPreviewLimitIsZero() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleReviewRuntimeVisualizer visualizer = new ConsoleReviewRuntimeVisualizer(
                new PrintStream(output, true, StandardCharsets.UTF_8.name()),
                0
        );

        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1"))
                .enterSelectingFocus()
                .withSelectedFocus("chunk-1");
        ReviewToolDecision decision = new ReviewToolDecision(
                "read_confirmed_terms",
                Map.of("sourceTerms", List.of("Le Conde")),
                "lookup"
        );
        ReviewToolExecutionResult execution = ReviewToolExecutionResult.success(
                decision.toCall(),
                runtime,
                "tool_result read_confirmed_terms sourceTerms=[Le Conde] -> confirmedTerm=Le Conde->孔代咖啡馆"
        );

        visualizer.toolCompleted(runtime, execution);

        String rendered = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(rendered.contains("confirmedTerm=Le Conde->孔代咖啡馆"));
    }
    @Test
    void shouldPrintLlmCallFailedDiagnosticWhenProjectFinishes() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleReviewRuntimeVisualizer visualizer = new ConsoleReviewRuntimeVisualizer(
                new PrintStream(output, true, StandardCharsets.UTF_8.name()),
                0
        );

        ProjectReviewRuntimeSession runtime = ProjectReviewRuntimeSession
                .initialize("project-1", List.of("chunk-1"))
                .withSelectedFocus("chunk-1")
                .failLlmCall("Review agent invalid structured tool decision: invalid_argument:entries");

        visualizer.projectFinished(runtime);

        String rendered = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(rendered.contains("stopReason=LLM_CALL_FAILED"));
        assertTrue(rendered.contains("diagnostic=llmCallFailed=Review agent invalid structured tool decision: invalid_argument:entries"));
    }

    @Test
    void shouldPrintQuestionReasonAndResumeHintWhenHumanReviewIsRequested() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleReviewRuntimeVisualizer visualizer = new ConsoleReviewRuntimeVisualizer(
                new PrintStream(output, true, StandardCharsets.UTF_8.name()),
                0
        );

        ProjectReviewRuntimeSession before = ProjectReviewRuntimeSession.initialize("project-1", List.of("chunk-1"))
                .enterSelectingFocus()
                .withSelectedFocus("chunk-1");
        HumanReviewRequest request = new HumanReviewRequest(
                "project-1",
                ReviewFocus.forChunk("chunk-1"),
                new ReviewProcessSummary(
                        "project-1",
                        ReviewFocus.forChunk("chunk-1"),
                        ReviewStrategy.REQUIRE_HUMAN_REVIEW,
                        Set.of(),
                        List.of("need-help"),
                        "paused"
                ),
                "chunk=chunk-1, reason=naming conflict",
                "请确认 Louki 的统一译名。",
                "project_waiting_human",
                ReviewAgentState.WAITING_HUMAN,
                "resumeDecision=continue_investigation|enter_revision",
                0,
                1
        );
        ProjectReviewRuntimeSession after = before.withHumanReviewRequest(request);
        ReviewToolDecision decision = new ReviewToolDecision("request_human_review", Map.of(), "need human review");
        ReviewToolExecutionResult execution = ReviewToolExecutionResult.success(decision.toCall(), after, "request_human_review");

        visualizer.toolCompleted(before, execution);

        String rendered = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(rendered.contains("event=human_review_requested"));
        assertTrue(rendered.contains("question=请确认 Louki 的统一译名。"));
        assertTrue(rendered.contains("reason=project_waiting_human"));
        assertTrue(rendered.contains("resumeHint=resumeDecision=continue_investigation|enter_revision"));
    }
}
