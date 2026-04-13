package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

import io.quillloom.application.preprocess.model.CoarseChunkBoundaryPlan;
import io.quillloom.application.preprocess.model.CoarseChunkPlanningResult;
import io.quillloom.application.preprocess.model.CoarseChunkPlanningTaskInput;
import io.quillloom.application.preprocess.port.out.CoarseChunkPlanGenerator;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import io.quillloom.application.workflow.trace.model.WorkflowEventStatus;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LlmCoarseChunkPlanGenerator implements CoarseChunkPlanGenerator {

    private static final int MAX_ATTEMPTS = 2;

    private final CoarseChunkPlanningPromptRenderer promptRenderer;
    private final CoarseChunkPlanningRepairPromptRenderer repairPromptRenderer;
    private final LlmCoarseChunkPlanClient llmClient;
    private final CoarseChunkPlanningLlmResultNormalizer resultNormalizer;
    private final WorkflowTraceRecorder traceRecorder;

    @Autowired
    public LlmCoarseChunkPlanGenerator(CoarseChunkPlanningPromptRenderer promptRenderer,
                                       LlmCoarseChunkPlanClient llmClient,
                                       CoarseChunkPlanningLlmResultNormalizer resultNormalizer) {
        this(promptRenderer, new CoarseChunkPlanningRepairPromptRenderer(), llmClient, resultNormalizer, new WorkflowTraceRecorder());
    }

    public LlmCoarseChunkPlanGenerator(CoarseChunkPlanningPromptRenderer promptRenderer,
                                       LlmCoarseChunkPlanClient llmClient,
                                       CoarseChunkPlanningLlmResultNormalizer resultNormalizer,
                                       WorkflowTraceRecorder traceRecorder) {
        this(promptRenderer, new CoarseChunkPlanningRepairPromptRenderer(), llmClient, resultNormalizer, traceRecorder);
    }

    public LlmCoarseChunkPlanGenerator(CoarseChunkPlanningPromptRenderer promptRenderer,
                                       CoarseChunkPlanningRepairPromptRenderer repairPromptRenderer,
                                       LlmCoarseChunkPlanClient llmClient,
                                       CoarseChunkPlanningLlmResultNormalizer resultNormalizer) {
        this(promptRenderer, repairPromptRenderer, llmClient, resultNormalizer, new WorkflowTraceRecorder());
    }

    public LlmCoarseChunkPlanGenerator(CoarseChunkPlanningPromptRenderer promptRenderer,
                                       CoarseChunkPlanningRepairPromptRenderer repairPromptRenderer,
                                       LlmCoarseChunkPlanClient llmClient,
                                       CoarseChunkPlanningLlmResultNormalizer resultNormalizer,
                                       WorkflowTraceRecorder traceRecorder) {
        this.promptRenderer = promptRenderer;
        this.repairPromptRenderer = repairPromptRenderer;
        this.llmClient = llmClient;
        this.resultNormalizer = resultNormalizer;
        this.traceRecorder = traceRecorder;
    }

    @Override
    public CoarseChunkPlanningResult generate(CoarseChunkPlanningTaskInput input) {
        String prompt = promptRenderer.render(input);
        record("coarse_planning_prompt_rendered", WorkflowEventStatus.SUCCEEDED, Map.of(
                "input", Map.of(
                        "projectId", input.projectId(),
                        "title", input.title(),
                        "sourceText", input.sourceText()
                ),
                "prompt", Map.of("text", prompt)
        ));

        LlmCoarseChunkPlanClientResponse response = llmClient.generateDetailed(prompt);
        record("coarse_planning_llm_responded", WorkflowEventStatus.SUCCEEDED, Map.of(
                "timeoutSeconds", response.timeoutSeconds(),
                "rawResponse", Map.of("text", response.rawResponse() == null ? String.valueOf(response.result()) : response.rawResponse()),
                "parsedResult", toPlanningPayload(response.result())
        ));

        try {
            CoarseChunkPlanningResult normalized = resultNormalizer.normalize(input, response.result());
            record("coarse_planning_normalized", WorkflowEventStatus.SUCCEEDED, Map.of(
                    "normalizedResult", toBoundaryPayload(normalized.boundaries())
            ));
            return normalized;
        } catch (IllegalStateException ex) {
            record("coarse_planning_llm_failed", WorkflowEventStatus.FAILED, Map.of(
                    "attempt", 1,
                    "issue", Map.of(
                            "detail", ex.getMessage(),
                            "rawResponse", response.rawResponse() == null ? String.valueOf(response.result()) : response.rawResponse()
                    )
            ));

            CoarseChunkPlanningRepairIssue issue = new CoarseChunkPlanningRepairIssue(
                    ex.getMessage(),
                    response.rawResponse() == null ? String.valueOf(response.result()) : response.rawResponse()
            );
            String repairPrompt = repairPromptRenderer.render(prompt, issue);
            record("coarse_planning_repair_requested", WorkflowEventStatus.SUCCEEDED, Map.of(
                    "attempt", 2,
                    "issue", Map.of(
                            "detail", issue.detail(),
                            "rawResponse", issue.rawResponse()
                    ),
                    "prompt", Map.of("text", repairPrompt)
            ));

            LlmCoarseChunkPlanClientResponse repairedResponse = llmClient.generateDetailed(repairPrompt);
            record("coarse_planning_repair_llm_responded", WorkflowEventStatus.SUCCEEDED, Map.of(
                    "timeoutSeconds", repairedResponse.timeoutSeconds(),
                    "rawResponse", Map.of("text", repairedResponse.rawResponse() == null ? String.valueOf(repairedResponse.result()) : repairedResponse.rawResponse()),
                    "parsedResult", toPlanningPayload(repairedResponse.result())
            ));

            try {
                CoarseChunkPlanningResult normalized = resultNormalizer.normalize(input, repairedResponse.result());
                record("coarse_planning_normalized", WorkflowEventStatus.SUCCEEDED, Map.of(
                        "normalizedResult", toBoundaryPayload(normalized.boundaries())
                ));
                record("coarse_planning_repair_succeeded", WorkflowEventStatus.SUCCEEDED, Map.of(
                        "attempts", MAX_ATTEMPTS
                ));
                return normalized;
            } catch (IllegalStateException retryEx) {
                record("coarse_planning_repair_failed", WorkflowEventStatus.FAILED, Map.of(
                        "attempt", 2,
                        "issue", Map.of(
                                "detail", retryEx.getMessage(),
                                "rawResponse", repairedResponse.rawResponse() == null ? String.valueOf(repairedResponse.result()) : repairedResponse.rawResponse()
                        )
                ));
                throw new IllegalStateException(
                        "coarse chunk planning repair exhausted. attempts=" + MAX_ATTEMPTS
                                + ", firstFailure=" + ex.getMessage()
                                + ", secondFailure=" + retryEx.getMessage(),
                        retryEx
                );
            }
        }
    }

    private void record(String eventType, WorkflowEventStatus status, Map<String, Object> payload) {
        traceRecorder.record(WorkflowStage.COARSE_PLANNING, eventType, status, null, null, payload);
    }

    private Map<String, Object> toPlanningPayload(CoarseChunkPlanningLlmResult result) {
        return Map.of("boundaries", result == null ? List.of() : toBoundaryPayload(result.boundaries()));
    }

    private List<Map<String, Object>> toBoundaryPayload(List<?> boundaries) {
        if (boundaries == null) {
            return List.of();
        }
        return boundaries.stream().map(boundary -> {
            Map<String, Object> item = new LinkedHashMap<>();
            if (boundary instanceof CoarseChunkPlanningLlmBoundary llmBoundary) {
                item.put("endParagraphIndex", llmBoundary.endParagraphIndex());
                item.put("summary", llmBoundary.summary());
                item.put("boundaryHint", llmBoundary.boundaryHint());
            } else if (boundary instanceof CoarseChunkBoundaryPlan planBoundary) {
                item.put("endParagraphIndex", planBoundary.endParagraphIndex());
                item.put("summary", planBoundary.summary());
                item.put("boundaryHint", planBoundary.boundaryHint());
            }
            return item;
        }).toList();
    }
}
