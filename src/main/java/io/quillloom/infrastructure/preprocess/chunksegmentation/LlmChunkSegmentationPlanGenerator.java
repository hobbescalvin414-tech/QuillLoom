package io.quillloom.infrastructure.preprocess.chunksegmentation;

import io.quillloom.application.preprocess.model.ChunkBoundaryPlan;
import io.quillloom.application.preprocess.model.ChunkSegmentationPlanningResult;
import io.quillloom.application.preprocess.model.ChunkSegmentationTaskInput;
import io.quillloom.application.preprocess.port.out.ChunkSegmentationPlanGenerator;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import io.quillloom.application.workflow.trace.model.WorkflowEventStatus;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LlmChunkSegmentationPlanGenerator implements ChunkSegmentationPlanGenerator {

    private static final int MAX_ATTEMPTS = 2;

    private final ChunkSegmentationPromptRenderer promptRenderer;
    private final ChunkSegmentationRepairPromptRenderer repairPromptRenderer;
    private final LlmChunkSegmentationPlanClient llmClient;
    private final ChunkSegmentationPlanningLlmResultNormalizer resultNormalizer;
    private final WorkflowTraceRecorder traceRecorder;

    @Autowired
    public LlmChunkSegmentationPlanGenerator(ChunkSegmentationPromptRenderer promptRenderer,
                                             LlmChunkSegmentationPlanClient llmClient,
                                             ChunkSegmentationPlanningLlmResultNormalizer resultNormalizer) {
        this(promptRenderer, new ChunkSegmentationRepairPromptRenderer(), llmClient, resultNormalizer, new WorkflowTraceRecorder());
    }
    public LlmChunkSegmentationPlanGenerator(ChunkSegmentationPromptRenderer promptRenderer,
                                             LlmChunkSegmentationPlanClient llmClient,
                                             ChunkSegmentationPlanningLlmResultNormalizer resultNormalizer,
                                             WorkflowTraceRecorder traceRecorder) {
        this(promptRenderer, new ChunkSegmentationRepairPromptRenderer(), llmClient, resultNormalizer, traceRecorder);
    }

    public LlmChunkSegmentationPlanGenerator(ChunkSegmentationPromptRenderer promptRenderer,
                                             ChunkSegmentationRepairPromptRenderer repairPromptRenderer,
                                             LlmChunkSegmentationPlanClient llmClient,
                                             ChunkSegmentationPlanningLlmResultNormalizer resultNormalizer) {
        this(promptRenderer, repairPromptRenderer, llmClient, resultNormalizer, new WorkflowTraceRecorder());
    }

    public LlmChunkSegmentationPlanGenerator(ChunkSegmentationPromptRenderer promptRenderer,
                                             ChunkSegmentationRepairPromptRenderer repairPromptRenderer,
                                             LlmChunkSegmentationPlanClient llmClient,
                                             ChunkSegmentationPlanningLlmResultNormalizer resultNormalizer,
                                             WorkflowTraceRecorder traceRecorder) {
        this.promptRenderer = promptRenderer;
        this.repairPromptRenderer = repairPromptRenderer;
        this.llmClient = llmClient;
        this.resultNormalizer = resultNormalizer;
        this.traceRecorder = traceRecorder;
    }

    @Override
    public ChunkSegmentationPlanningResult generate(ChunkSegmentationTaskInput input) {
        String prompt = promptRenderer.render(input);
        record("chunk_segmentation_prompt_rendered", input, Map.of(
                "input", Map.of(
                        "projectId", input.projectId(),
                        "title", input.title(),
                        "coarseBlockId", input.coarseChunkBlock().blockId(),
                        "sourceText", input.coarseChunkBlock().sourceText()
                ),
                "prompt", Map.of("text", prompt)
        ));

        LlmChunkSegmentationPlanClientResponse response = llmClient.generateDetailed(prompt);
        record("chunk_segmentation_llm_responded", input, Map.of(
                "rawResponse", Map.of("text", response.rawResponse() == null ? String.valueOf(response.result()) : response.rawResponse()),
                "parsedResult", toPlanningPayload(response.result())
        ));

        try {
            ChunkSegmentationPlanningResult normalized = resultNormalizer.normalize(input, response.result());
            record("chunk_segmentation_normalized", input, Map.of(
                    "normalizedResult", toBoundaryPayload(normalized.boundaries())
            ));
            return normalized;
        } catch (IllegalStateException ex) {
            record("chunk_segmentation_llm_failed", input, WorkflowEventStatus.FAILED, Map.of(
                    "attempt", 1,
                    "issue", Map.of(
                            "detail", ex.getMessage(),
                            "rawResponse", response.rawResponse() == null ? String.valueOf(response.result()) : response.rawResponse()
                    )
            ));

            ChunkSegmentationRepairIssue issue = new ChunkSegmentationRepairIssue(
                    ex.getMessage(),
                    response.rawResponse() == null ? String.valueOf(response.result()) : response.rawResponse()
            );
            String repairPrompt = repairPromptRenderer.render(prompt, issue);
            record("chunk_segmentation_repair_requested", input, Map.of(
                    "attempt", 2,
                    "issue", Map.of(
                            "detail", issue.detail(),
                            "rawResponse", issue.rawResponse()
                    ),
                    "prompt", Map.of("text", repairPrompt)
            ));

            LlmChunkSegmentationPlanClientResponse repairedResponse = llmClient.generateDetailed(repairPrompt);
            record("chunk_segmentation_repair_llm_responded", input, Map.of(
                    "rawResponse", Map.of("text", repairedResponse.rawResponse() == null ? String.valueOf(repairedResponse.result()) : repairedResponse.rawResponse()),
                    "parsedResult", toPlanningPayload(repairedResponse.result())
            ));

            try {
                ChunkSegmentationPlanningResult normalized = resultNormalizer.normalize(input, repairedResponse.result());
                record("chunk_segmentation_normalized", input, Map.of(
                        "normalizedResult", toBoundaryPayload(normalized.boundaries())
                ));
                record("chunk_segmentation_repair_succeeded", input, Map.of(
                        "attempts", MAX_ATTEMPTS
                ));
                return normalized;
            } catch (IllegalStateException retryEx) {
                record("chunk_segmentation_repair_failed", input, WorkflowEventStatus.FAILED, Map.of(
                        "attempt", 2,
                        "issue", Map.of(
                                "detail", retryEx.getMessage(),
                                "rawResponse", repairedResponse.rawResponse() == null ? String.valueOf(repairedResponse.result()) : repairedResponse.rawResponse()
                        )
                ));
                throw new IllegalStateException(
                        "chunk segmentation repair exhausted. attempts=" + MAX_ATTEMPTS
                                + ", firstFailure=" + ex.getMessage()
                                + ", secondFailure=" + retryEx.getMessage(),
                        retryEx
                );
            }
        }
    }

    private void record(String eventType, ChunkSegmentationTaskInput input, Map<String, Object> payload) {
        record(eventType, input, WorkflowEventStatus.SUCCEEDED, payload);
    }

    private void record(String eventType,
                        ChunkSegmentationTaskInput input,
                        WorkflowEventStatus status,
                        Map<String, Object> payload) {
        traceRecorder.record(WorkflowStage.CHUNK_SEGMENTATION, eventType, status, input.coarseChunkBlock().blockId(), null, payload);
    }

    private Map<String, Object> toPlanningPayload(ChunkSegmentationPlanningLlmResult result) {
        return Map.of("boundaries", result == null ? List.of() : toBoundaryPayload(result.boundaries()));
    }

    private List<Map<String, Object>> toBoundaryPayload(List<?> boundaries) {
        if (boundaries == null) {
            return List.of();
        }
        return boundaries.stream().map(boundary -> {
            Map<String, Object> item = new LinkedHashMap<>();
            if (boundary instanceof ChunkSegmentationPlanningLlmBoundary llmBoundary) {
                item.put("endParagraphIndex", llmBoundary.endParagraphIndex());
                item.put("boundaryHint", llmBoundary.boundaryHint());
            } else if (boundary instanceof ChunkBoundaryPlan planBoundary) {
                item.put("endParagraphIndex", planBoundary.endParagraphIndex());
                item.put("boundaryHint", planBoundary.boundaryHint());
            }
            return item;
        }).toList();
    }
}
