package io.quillloom.infrastructure.preprocess.chunkannotation;

import io.quillloom.application.preprocess.model.ChunkAnnotationTaskInput;
import io.quillloom.application.preprocess.port.out.ChunkAnnotationGenerator;
import io.quillloom.application.workflow.trace.WorkflowTraceRecorder;
import io.quillloom.application.workflow.trace.model.WorkflowEventStatus;
import io.quillloom.application.workflow.trace.model.WorkflowStage;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 LLM 生成 chunk 标注，并在结构化输出失败时执行一次受控修复式重试。
 */
@Component
public class LlmChunkAnnotationGenerator implements ChunkAnnotationGenerator {

    private static final int MAX_ATTEMPTS = 2;

    private final ChunkAnnotationPromptRenderer promptRenderer;
    private final ChunkAnnotationRepairPromptRenderer repairPromptRenderer;
    private final LlmChunkAnnotationClient llmClient;
    private final ChunkAnnotationLlmResultNormalizer resultNormalizer;
    private final ChunkAnnotationLlmResultParser resultParser;
    private final WorkflowTraceRecorder traceRecorder;

    public LlmChunkAnnotationGenerator(ChunkAnnotationPromptRenderer promptRenderer,
                                       LlmChunkAnnotationClient llmClient,
                                       ChunkAnnotationLlmResultNormalizer resultNormalizer,
                                       ChunkAnnotationLlmResultParser resultParser) {
        this(promptRenderer, new ChunkAnnotationRepairPromptRenderer(), llmClient, resultNormalizer, resultParser, new WorkflowTraceRecorder());
    }

    public LlmChunkAnnotationGenerator(ChunkAnnotationPromptRenderer promptRenderer,
                                       LlmChunkAnnotationClient llmClient,
                                       ChunkAnnotationLlmResultNormalizer resultNormalizer,
                                       ChunkAnnotationLlmResultParser resultParser,
                                       WorkflowTraceRecorder traceRecorder) {
        this(promptRenderer, new ChunkAnnotationRepairPromptRenderer(), llmClient, resultNormalizer, resultParser, traceRecorder);
    }

    @Autowired
    public LlmChunkAnnotationGenerator(ChunkAnnotationPromptRenderer promptRenderer,
                                       ChunkAnnotationRepairPromptRenderer repairPromptRenderer,
                                       LlmChunkAnnotationClient llmClient,
                                       ChunkAnnotationLlmResultNormalizer resultNormalizer,
                                       ChunkAnnotationLlmResultParser resultParser) {
        this(promptRenderer, repairPromptRenderer, llmClient, resultNormalizer, resultParser, new WorkflowTraceRecorder());
    }

    public LlmChunkAnnotationGenerator(ChunkAnnotationPromptRenderer promptRenderer,
                                       ChunkAnnotationRepairPromptRenderer repairPromptRenderer,
                                       LlmChunkAnnotationClient llmClient,
                                       ChunkAnnotationLlmResultNormalizer resultNormalizer,
                                       ChunkAnnotationLlmResultParser resultParser,
                                       WorkflowTraceRecorder traceRecorder) {
        this.promptRenderer = promptRenderer;
        this.repairPromptRenderer = repairPromptRenderer;
        this.llmClient = llmClient;
        this.resultNormalizer = resultNormalizer;
        this.resultParser = resultParser;
        this.traceRecorder = traceRecorder;
    }

    @Override
    public ChunkAnnotation generate(ChunkAnnotationTaskInput input) {
        String prompt = promptRenderer.render(input);
        record("chunk_annotation_prompt_rendered", WorkflowEventStatus.SUCCEEDED, input, Map.of(
                "input", buildInputPayload(input),
                "prompt", Map.of("text", prompt)
        ));

        ChunkAnnotationLlmClientResponse response;
        try {
            response = llmClient.generateDetailed(prompt);
            recordResponse("chunk_annotation_llm_responded", input, response);
        } catch (ChunkAnnotationStructuredOutputException ex) {
            record("chunk_annotation_llm_failed", WorkflowEventStatus.FAILED, input, Map.of(
                    "attempt", 1,
                    "issue", Map.of(
                            "reasonCode", nullToEmpty(ex.reasonCode()),
                            "detail", nullToEmpty(ex.detail()),
                            "rawResponse", nullToEmpty(ex.rawResponse())
                    )
            ));
            if (!ex.recoverable()) {
                throw ex;
            }

            ChunkAnnotationRepairIssue issue = new ChunkAnnotationRepairIssue(ex.reasonCode(), ex.detail(), ex.rawResponse());
            String repairPrompt = repairPromptRenderer.render(prompt, issue);
            record("chunk_annotation_repair_requested", WorkflowEventStatus.SUCCEEDED, input, Map.of(
                    "attempt", 2,
                    "issue", Map.of(
                            "reasonCode", nullToEmpty(issue.reasonCode()),
                            "detail", nullToEmpty(issue.detail()),
                            "rawResponse", nullToEmpty(issue.rawResponse())
                    ),
                    "prompt", Map.of("text", repairPrompt)
            ));

            try {
                response = llmClient.generateDetailed(repairPrompt);
                recordResponse("chunk_annotation_repair_llm_responded", input, response);
            } catch (ChunkAnnotationStructuredOutputException retryEx) {
                record("chunk_annotation_repair_failed", WorkflowEventStatus.FAILED, input, Map.of(
                        "attempt", 2,
                        "issue", Map.of(
                                "reasonCode", nullToEmpty(retryEx.reasonCode()),
                                "detail", nullToEmpty(retryEx.detail()),
                                "rawResponse", nullToEmpty(retryEx.rawResponse())
                        )
                ));
                throw new IllegalStateException(
                        "chunk annotation repair exhausted. attempts=" + MAX_ATTEMPTS
                                + ", firstFailure=" + nullToEmpty(ex.detail())
                                + ", secondFailure=" + nullToEmpty(retryEx.detail()),
                        retryEx
                );
            }
        }

        ChunkAnnotationLlmResult normalizedResult = resultNormalizer.normalize(input, response.result());
        record("chunk_annotation_normalized", WorkflowEventStatus.SUCCEEDED, input, Map.of(
                "normalizedResult", toLlmResultPayload(normalizedResult)
        ));

        ChunkAnnotation annotation = resultParser.parse(input, normalizedResult);
        record("chunk_annotation_completed", WorkflowEventStatus.SUCCEEDED, input, Map.of(
                "compiledResult", toAnnotationPayload(annotation)
        ));
        return annotation;
    }

    private void recordResponse(String eventType,
                                ChunkAnnotationTaskInput input,
                                ChunkAnnotationLlmClientResponse response) {
        ChunkAnnotationLlmResult rawResult = response.result();
        record(eventType, WorkflowEventStatus.SUCCEEDED, input, Map.of(
                "rawResponse", Map.of("text", response.rawResponse() == null ? renderFallbackRawResult(rawResult) : response.rawResponse()),
                "parsedResult", toLlmResultPayload(rawResult)
        ));
    }

    private void record(String eventType,
                        WorkflowEventStatus status,
                        ChunkAnnotationTaskInput input,
                        Map<String, Object> payload) {
        traceRecorder.record(
                WorkflowStage.CHUNK_ANNOTATION,
                eventType,
                status,
                input.chunk().coarseBlockId(),
                input.chunk().chunkId(),
                payload
        );
    }

    private Map<String, Object> buildInputPayload(ChunkAnnotationTaskInput input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", input.projectId());
        payload.put("title", input.title());
        payload.put("sourceLanguage", input.sourceLanguage());
        payload.put("targetLanguage", input.targetLanguage());
        payload.put("chunk", Map.of(
                "chunkId", input.chunk().chunkId(),
                "sequence", input.chunk().sequence(),
                "coarseBlockId", input.chunk().coarseBlockId(),
                "startOffset", input.chunk().startOffset(),
                "endOffset", input.chunk().endOffset(),
                "sourceText", input.chunk().sourceText()
        ));
        return payload;
    }

    private Map<String, Object> toLlmResultPayload(ChunkAnnotationLlmResult result) {
        if (result == null) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", nullToEmpty(result.summary()));
        payload.put("entities", safeList(result.entities()));
        payload.put("backgroundQuestions", safeList(result.backgroundQuestions()));
        payload.put("translationRisks", safeList(result.translationRisks()));
        payload.put("keyExpressions", safeList(result.keyExpressions()));
        payload.put("personAliasHints", result.personAliasHints() == null ? List.of() : List.copyOf(result.personAliasHints()));
        return payload;
    }

    private Map<String, Object> toAnnotationPayload(ChunkAnnotation annotation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", nullToEmpty(annotation.summary()));
        payload.put("entities", safeList(annotation.entities()));
        payload.put("backgroundQuestions", safeList(annotation.backgroundQuestions()));
        payload.put("translationRisks", safeList(annotation.translationRisks()));
        payload.put("keyExpressions", safeList(annotation.keyExpressions()));
        payload.put("personAliasHints", annotation.personAliasHints() == null ? List.of() : List.copyOf(annotation.personAliasHints()));
        return payload;
    }

    private String renderFallbackRawResult(ChunkAnnotationLlmResult result) {
        if (result == null) {
            return "{}";
        }
        return "summary=" + nullToEmpty(result.summary())
                + "; entities=" + safeList(result.entities())
                + "; backgroundQuestions=" + safeList(result.backgroundQuestions())
                + "; translationRisks=" + safeList(result.translationRisks())
                + "; keyExpressions=" + safeList(result.keyExpressions())
                + "; personAliasHints=" + (result.personAliasHints() == null ? List.of() : List.copyOf(result.personAliasHints()));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
