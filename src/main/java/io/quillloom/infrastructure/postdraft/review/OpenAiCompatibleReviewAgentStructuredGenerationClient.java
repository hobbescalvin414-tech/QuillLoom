package io.quillloom.infrastructure.postdraft.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quillloom.application.postdraft.review.model.ReviewAgentEvaluation;
import io.quillloom.application.postdraft.review.model.RecordConfirmedTermsProposal;
import io.quillloom.application.postdraft.review.model.ReviewToolDefinition;
import io.quillloom.application.postdraft.review.model.ReviewToolDecision;
import io.quillloom.application.postdraft.review.model.RecordConfirmedTermEntry;
import io.quillloom.application.postdraft.review.model.RevisionDraft;
import io.quillloom.application.postdraft.review.model.RevisionSelfCheckResult;
import io.quillloom.application.postdraft.review.port.out.LlmStructuredOutputException;
import io.quillloom.application.postdraft.review.port.out.LlmTransientException;
import io.quillloom.application.postdraft.review.port.out.ReviewAgentStructuredGenerationPort;
import io.quillloom.application.postdraft.review.service.ReviewToolDecisionContractValidator;
import io.quillloom.application.postdraft.review.service.ReviewToolRegistry;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;

public class OpenAiCompatibleReviewAgentStructuredGenerationClient implements ReviewAgentStructuredGenerationPort {

    private static final Set<String> INVESTIGATION_ARGUMENT_UNION_FIELDS =
            Set.of("count", "chunkIds", "sourceTerms", "queryTerms", "entries");

    private static final JsonSchema EVALUATION_SCHEMA = JsonSchema.builder()
            .name("review_agent_evaluation_decision")
            .rootElement(JsonObjectSchema.builder()
                    .addProperty("recommendedStrategy", JsonStringSchema.builder().build())
                    .addProperty("strategyReason", JsonStringSchema.builder().build())
                    .addProperty("evidenceSufficiency", JsonStringSchema.builder().build())
                    .addProperty("continueInvestigation", JsonBooleanSchema.builder().build())
                    .required("recommendedStrategy", "strategyReason", "evidenceSufficiency", "continueInvestigation")
                    .additionalProperties(false)
                    .build())
            .build();

    private static final JsonSchema REVISION_SCHEMA = JsonSchema.builder()
            .name("review_agent_revision_draft")
            .rootElement(JsonObjectSchema.builder()
                    .addProperty("formalTranslation", JsonStringSchema.builder().build())
                    .addProperty("revisionMode", JsonStringSchema.builder().build())
                    .addProperty("keyRationales", stringArray())
                    .addProperty("residualRisks", stringArray())
                    .required("formalTranslation", "revisionMode", "keyRationales", "residualRisks")
                    .additionalProperties(false)
                    .build())
            .build();

    private static final JsonSchema SELF_CHECK_SCHEMA = JsonSchema.builder()
            .name("review_agent_revision_self_check")
            .rootElement(JsonObjectSchema.builder()
                    .addProperty("passed", JsonBooleanSchema.builder().build())
                    .addProperty("stopReason", JsonStringSchema.builder().build())
                    .addProperty("findings", stringArray())
                    .required("passed", "stopReason", "findings")
                    .additionalProperties(false)
                    .build())
            .build();

    private static final JsonSchema RECORD_CONFIRMED_TERMS_PROPOSAL_SCHEMA = JsonSchema.builder()
            .name("review_agent_record_confirmed_terms_proposal")
            .rootElement(JsonObjectSchema.builder()
                    .addProperty("action", JsonStringSchema.builder().build())
                    .addProperty("reason", JsonStringSchema.builder().build())
                    .addProperty("entries", JsonArraySchema.builder()
                            .items(JsonObjectSchema.builder()
                                    .addProperty("sourceTerm", JsonStringSchema.builder().build())
                                    .addProperty("targetTerm", JsonStringSchema.builder().build())
                                    .required("sourceTerm", "targetTerm")
                                    .additionalProperties(false)
                                    .build())
                            .build())
                    .required("action", "reason", "entries")
                    .additionalProperties(false)
                    .build())
            .build();

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final ReviewToolRegistry toolRegistry;
    private final ReviewToolDecisionContractValidator contractValidator;

    public OpenAiCompatibleReviewAgentStructuredGenerationClient(ChatModel chatModel,
                                                                 ObjectMapper objectMapper) {
        this(chatModel, objectMapper, ReviewToolRegistry.defaultRegistry(), new ReviewToolDecisionContractValidator());
    }

    OpenAiCompatibleReviewAgentStructuredGenerationClient(ChatModel chatModel,
                                                          ObjectMapper objectMapper,
                                                          ReviewToolRegistry toolRegistry,
                                                          ReviewToolDecisionContractValidator contractValidator) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.contractValidator = contractValidator;
    }

    @Override
    public ReviewToolDecision generateNextToolDecision(String systemPrompt, String userPrompt) {
        ReviewToolDecision decision = invoke(systemPrompt, userPrompt, investigationSchema(), ReviewToolDecision.class);
        var validationError = contractValidator.validateNextStepDecision(decision, toolRegistry);
        if (validationError.isPresent()) {
            throw new LlmStructuredOutputException("Review agent invalid structured tool decision: "
                    + validationError.orElseThrow()
                    + renderRawStructuredOutputDetail(decision));
        }
        return decision;
    }

    @Override
    public ReviewAgentEvaluation generateEvaluationDecision(String systemPrompt, String userPrompt) {
        return invoke(systemPrompt, userPrompt, EVALUATION_SCHEMA, ReviewAgentEvaluation.class);
    }

    @Override
    public RecordConfirmedTermsProposal generateRecordConfirmedTermsProposal(String systemPrompt, String userPrompt) {
        RecordConfirmedTermsProposal proposal = invoke(
                systemPrompt,
                userPrompt,
                RECORD_CONFIRMED_TERMS_PROPOSAL_SCHEMA,
                RecordConfirmedTermsProposal.class
        );
        validateRecordConfirmedTermsProposal(proposal);
        return proposal;
    }

    @Override
    public RevisionDraft generateRevisionDraft(String systemPrompt, String userPrompt) {
        return invoke(systemPrompt, userPrompt, REVISION_SCHEMA, RevisionDraft.class);
    }

    @Override
    public RevisionSelfCheckResult generateRevisionSelfCheck(String systemPrompt, String userPrompt) {
        return invoke(systemPrompt, userPrompt, SELF_CHECK_SCHEMA, RevisionSelfCheckResult.class);
    }

    private <T> T invoke(String systemPrompt, String userPrompt, JsonSchema schema, Class<T> type) {
        ChatRequest.Builder requestBuilder = ChatRequest.builder()
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(schema)
                        .build());
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            requestBuilder.messages(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt));
        } else {
            requestBuilder.messages(UserMessage.from(userPrompt));
        }
        ChatRequest request = requestBuilder.build();
        ChatResponse response;
        try {
            response = chatModel.chat(request);
        } catch (RuntimeException ex) {
            if (isTransientTransportError(ex)) {
                throw new LlmTransientException("Review agent structured generation request failed with transient llm transport error", ex);
            }
            throw ex;
        }
        String text = response.aiMessage() == null ? null : response.aiMessage().text();
        if (text == null || text.isBlank()) {
            throw new LlmTransientException("Review agent structured generation output is blank");
        }
        try {
            JsonNode root = objectMapper.readTree(text);
            if (type == ReviewToolDecision.class) {
                validateReviewToolDecisionNode(root, text);
                stripNullArguments(root);
                stripArgumentsFromOtherUnionBranches(root);
            }
            return objectMapper.treeToValue(root, type);
        } catch (JsonProcessingException ex) {
            throw new LlmStructuredOutputException(
                    "Review agent structured generation output cannot be parsed as structured JSON"
                            + renderRawStructuredOutputDetail(text),
                    ex
            );
        }
    }

    private void validateReviewToolDecisionNode(JsonNode root, String rawOutput) {
        if (root == null || !root.isObject()) {
            throw new LlmStructuredOutputException(
                    "Review agent invalid structured tool decision: root_must_be_object"
                            + renderRawStructuredOutputDetail(rawOutput)
            );
        }

        JsonNode arguments = root.get("arguments");
        if (arguments == null || !arguments.isObject()) {
            throw new LlmStructuredOutputException(
                    "Review agent invalid structured tool decision: arguments_must_be_object"
                            + renderRawStructuredOutputDetail(rawOutput)
            );
        }
    }

    private void validateRecordConfirmedTermsProposal(RecordConfirmedTermsProposal proposal) {
        if (proposal == null) {
            throw new LlmStructuredOutputException("Review agent invalid record_confirmed_terms proposal: null_proposal");
        }
        try {
            new RecordConfirmedTermsProposal(proposal.action(), proposal.reason(), proposal.entries());
            for (RecordConfirmedTermEntry entry : proposal.entries()) {
                new RecordConfirmedTermEntry(entry.sourceTerm(), entry.targetTerm());
            }
        } catch (IllegalArgumentException ex) {
            throw new LlmStructuredOutputException(
                    "Review agent invalid record_confirmed_terms proposal: " + ex.getMessage(),
                    ex
            );
        }
    }

    private String renderRawStructuredOutputDetail(ReviewToolDecision decision) {
        try {
            return renderRawStructuredOutputDetail(objectMapper.writeValueAsString(decision));
        } catch (JsonProcessingException ex) {
            return "";
        }
    }

    private String renderRawStructuredOutputDetail(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return "";
        }
        String normalized = rawOutput.replace("\r", "\\r").replace("\n", "\\n");
        int limit = 800;
        String clipped = normalized.length() <= limit
                ? normalized
                : normalized.substring(0, limit) + "...(truncated)";
        return "; rawOutput=" + clipped;
    }

    private boolean isTransientTransportError(RuntimeException ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof RateLimitException || current instanceof TimeoutException) {
                return true;
            }
            if (current instanceof HttpException httpException) {
                int statusCode = httpException.statusCode();
                if (statusCode == 429 || statusCode == 503) {
                    return true;
                }
            }
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpConnectTimeoutException
                    || current instanceof ConnectException) {
                return true;
            }
            if (current instanceof IOException ioException && isTransientTransportIOException(ioException)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTransientTransportIOException(IOException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("goaway")
                || normalized.contains("http/2")
                || normalized.contains("connection closed")
                || normalized.contains("connection reset")
                || normalized.contains("stream was reset")
                || normalized.contains("broken pipe");
    }

    private void stripNullArguments(JsonNode root) {
        JsonNode arguments = root.get("arguments");
        if (arguments == null || !arguments.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = arguments.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                fields.remove();
            }
        }
    }

    private void stripArgumentsFromOtherUnionBranches(JsonNode root) {
        JsonNode toolNameNode = root.get("toolName");
        if (toolNameNode == null || !toolNameNode.isTextual()) {
            return;
        }
        String toolName = toolNameNode.asText();
        if (!toolRegistry.contains(toolName)) {
            return;
        }
        Set<String> allowedArguments = toolRegistry.require(toolName).allowedArguments();
        JsonNode arguments = root.get("arguments");
        if (arguments == null || !arguments.isObject()) {
            return;
        }
        // response_format can only express a generic arguments union; remove known fields from other tool branches without repairing semantics.
        Iterator<Map.Entry<String, JsonNode>> fields = arguments.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String argumentName = entry.getKey();
            if (INVESTIGATION_ARGUMENT_UNION_FIELDS.contains(argumentName)
                    && !allowedArguments.contains(argumentName)) {
                fields.remove();
            }
        }
    }

    private JsonSchema investigationSchema() {
        return JsonSchema.builder()
                .name("review_agent_investigation_decision")
                .rootElement(JsonObjectSchema.builder()
                        .description(investigationSchemaDescription())
                        .addProperty("toolName", JsonStringSchema.builder()
                                .description("Must be a tool name registered in ReviewToolRegistry.")
                                .build())
                        .addProperty("arguments", investigationArgumentsSchema())
                        .addProperty("reason", JsonStringSchema.builder()
                                .description("Tool-call rationale must stay in the top-level reason field; request_human_review also uses the top-level reason. When toolName=record_confirmed_terms, this next-step decision only routes into the record_confirmed_terms path; final confirmed-term pairs belong to the later proposal stage, not this reason field.")
                                .build())
                        .required("toolName", "arguments", "reason")
                        .additionalProperties(false)
                        .build())
                .build();
    }

    private String investigationSchemaDescription() {
        StringBuilder builder = new StringBuilder();
        builder.append("Review Agent tool decision. Only arguments declared by the selected tool definition are allowed. ")
                .append("Undeclared arguments must be omitted; request_human_review arguments must be {}. ")
                .append("When toolName=record_confirmed_terms, this next-step decision only selects that tool path; final confirmed-term pairs are produced in the later proposal stage, not in this first-stage reason.");
        for (ReviewToolDefinition definition : toolRegistry.definitions()) {
            builder.append("\nTool ")
                    .append(definition.toolName())
                    .append(": allowedArguments=")
                    .append(definition.allowedArguments())
                    .append(", requiredArguments=")
                    .append(definition.requiredArguments())
                    .append(", argumentRequirements=")
                    .append(renderArgumentRequirements(definition));
            String compactExample = renderCompactArgumentsExample(definition);
            if (!compactExample.isBlank()) {
                builder.append(", argumentsExample=")
                        .append(compactExample);
            }
            switch (definition.toolName()) {
                case "record_confirmed_terms" -> builder.append(", record only stable source-target pairs supported by the current working-set evidence.");
                case "request_human_review" -> builder.append(", use only for real unresolved semantics that local tools cannot close.");
                case "complete_working_set" -> builder.append(", use only for working-set completion, not project completion.");
                case "complete_project" -> builder.append(", use for pending-empty, project-ready endgame.");
                default -> {
                }
            }
        }
        return builder.toString();
    }

    private String renderArgumentRequirements(ReviewToolDefinition definition) {
        if (definition.argumentSchemas().isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        definition.argumentSchemas().forEach(schema -> {
            if (!builder.isEmpty()) {
                builder.append("; ");
            }
            builder.append(schema.name())
                    .append(": ")
                    .append(schema.type())
                    .append(" (")
                    .append(schema.required() ? "required" : "optional")
                    .append(")");
        });
        return builder.toString();
    }

    private String renderCompactArgumentsExample(ReviewToolDefinition definition) {
        return switch (definition.toolName()) {
            case "read_previous_chunks", "read_next_chunks", "read_confirmed_terms",
                    "lookup_knowledge_cards", "record_confirmed_terms", "complete_working_set" ->
                    definition.renderArgumentsExample();
            default -> "";
        };
    }

    private JsonObjectSchema investigationArgumentsSchema() {
        String entriesDescription = toolRegistry.contains("record_confirmed_terms")
                ? toolRegistry.require("record_confirmed_terms")
                .findArgumentSchema("entries")
                .map(schema -> sanitizeRecordConfirmedTermsEntriesDescriptionForNextStep(schema.schemaDescription()))
                .orElse("record_confirmed_terms entries")
                : "record_confirmed_terms entries";
        return JsonObjectSchema.builder()
                .description("Generic arguments union. Only arguments declared by the selected tool definition are allowed.")
                .addProperty("count", JsonIntegerSchema.builder().build())
                .addProperty("chunkIds", stringArray())
                .addProperty("sourceTerms", stringArray())
                .addProperty("queryTerms", stringArray())
                .addProperty("entries", JsonObjectSchema.builder()
                        .description(entriesDescription)
                        .additionalProperties(true)
                        .build())
                .additionalProperties(false)
                .build();
    }

    private String sanitizeRecordConfirmedTermsEntriesDescriptionForNextStep(String originalDescription) {
        if (originalDescription == null || originalDescription.isBlank()) {
            return "record_confirmed_terms route-stage entries placeholder. Final confirmed-term pairs are produced in the later proposal stage.";
        }
        return originalDescription.replace(
                " When toolName=record_confirmed_terms, candidate pairs must appear in arguments.entries, not only in reason.",
                " In next-step routing, entries may remain a route-stage placeholder; final confirmed-term pairs are produced in the later proposal stage."
        );
    }

    private static JsonArraySchema stringArray() {
        return JsonArraySchema.builder()
                .items(JsonStringSchema.builder().build())
                .build();
    }
}
