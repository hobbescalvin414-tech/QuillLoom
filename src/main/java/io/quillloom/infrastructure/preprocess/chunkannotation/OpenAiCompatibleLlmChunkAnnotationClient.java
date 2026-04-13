package io.quillloom.infrastructure.preprocess.chunkannotation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;

public class OpenAiCompatibleLlmChunkAnnotationClient implements LlmChunkAnnotationClient {

    private static final JsonSchema RESPONSE_SCHEMA = JsonSchema.builder()
            .name("chunk_annotation_result")
            .rootElement(JsonObjectSchema.builder()
                    .description("小说 chunk 标注结果")
                    .addProperty("summary", JsonStringSchema.builder().description("当前 chunk 的摘要").build())
                    .addProperty("entities", stringArray("当前 chunk 中的重要实体列表"))
                    .addProperty("backgroundQuestions", stringArray("翻译前值得确认的背景问题列表"))
                    .addProperty("translationRisks", stringArray("潜在翻译风险列表"))
                    .addProperty("keyExpressions", stringArray("值得关注的关键表达列表"))
                    .addProperty("personAliasHints", JsonArraySchema.builder()
                            .description("当前 chunk 内可能指向同一人物的不同称呼提示")
                            .items(JsonObjectSchema.builder()
                                    .addProperty("surfaceForms", stringArray("可能相关的称呼列表"))
                                    .addProperty("hintType", JsonStringSchema.builder().build())
                                    .addProperty("confidence", JsonStringSchema.builder().build())
                                    .addProperty("evidence", JsonStringSchema.builder().build())
                                    .required("surfaceForms", "hintType", "confidence", "evidence")
                                    .additionalProperties(false)
                                    .build())
                            .build())
                    .required("summary", "entities", "backgroundQuestions", "translationRisks", "keyExpressions", "personAliasHints")
                    .additionalProperties(false)
                    .build())
            .build();

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmChunkAnnotationClient(ChatModel chatModel,
                                                    ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChunkAnnotationLlmResult generate(String prompt) {
        return generateDetailed(prompt).result();
    }

    @Override
    public ChunkAnnotationLlmClientResponse generateDetailed(String prompt) {
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(RESPONSE_SCHEMA)
                        .build())
                .build();

        ChatResponse response = chatModel.chat(request);
        String text = response.aiMessage() == null ? null : response.aiMessage().text();
        if (text == null || text.isBlank()) {
            throw new ChunkAnnotationStructuredOutputException(
                    "empty-response",
                    "LLM chunk annotation output is blank",
                    text,
                    true,
                    null
            );
        }

        try {
            return new ChunkAnnotationLlmClientResponse(text, objectMapper.readValue(text, ChunkAnnotationLlmResult.class));
        } catch (JsonProcessingException ex) {
            throw new ChunkAnnotationStructuredOutputException(
                    classify(ex),
                    "LLM chunk annotation output cannot be parsed as structured JSON",
                    text,
                    true,
                    ex
            );
        }
    }

    private String classify(JsonProcessingException ex) {
        String message = ex.getOriginalMessage();
        if (message != null && message.contains("Unexpected end-of-input")) {
            return "json-eof";
        }
        return "invalid-json";
    }

    private static JsonArraySchema stringArray(String description) {
        return JsonArraySchema.builder()
                .description(description)
                .items(JsonStringSchema.builder().build())
                .build();
    }
}
