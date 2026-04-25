package io.quillloom.infrastructure.translation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

public class OpenAiCompatibleLlmChunkTranslationClient implements LlmChunkTranslationClient {

    private static final JsonSchema RESPONSE_SCHEMA = JsonSchema.builder()
            .name("chunk_translation_result")
            .rootElement(JsonObjectSchema.builder()
                    .description("小说单个 chunk 的结构化翻译草稿")
                    .addProperty("translatedText", JsonStringSchema.builder().description("当前 chunk 的目标语言翻译草稿").build())
                    .addProperty("translatorCommentary", JsonStringSchema.builder().description("本轮翻译处理说明").build())
                    .addProperty("decisionNotes", objectArray("未决问题或风险列表",
                            JsonObjectSchema.builder()
                                    .addProperty("type", JsonStringSchema.builder().build())
                                    .addProperty("sourceAnchor", JsonStringSchema.builder().build())
                                    .addProperty("description", JsonStringSchema.builder().build())
                                    .addProperty("recommendation", JsonStringSchema.builder().build())
                                    .required("type", "sourceAnchor", "description", "recommendation")
                                    .additionalProperties(false)
                                    .build()))
                    .addProperty("confirmedTermUpdates", objectArray("当前初稿阶段生效译名增量列表",
                            JsonObjectSchema.builder()
                                    .addProperty("sourceTerm", JsonStringSchema.builder().build())
                                    .addProperty("translatedTerm", JsonStringSchema.builder().build())
                                    .required("sourceTerm", "translatedTerm")
                                    .additionalProperties(false)
                                    .build()))
                    .addProperty("candidateUpdates", objectArray("同一 source term 的候选译法更新列表",
                            JsonObjectSchema.builder()
                                    .addProperty("sourceTerm", JsonStringSchema.builder().build())
                                    .addProperty("candidateTranslation", JsonStringSchema.builder().build())
                                    .addProperty("rationale", JsonStringSchema.builder().build())
                                    .addProperty("requiresReview", JsonBooleanSchema.builder().build())
                                    .required("sourceTerm", "candidateTranslation", "rationale", "requiresReview")
                                    .additionalProperties(false)
                                    .build()))
                    .addProperty("transitionNote", JsonObjectSchema.builder()
                            .description("与前后 chunk 的衔接说明")
                            .addProperty("previousChunkConnection", JsonStringSchema.builder().build())
                            .addProperty("nextChunkConnection", JsonStringSchema.builder().build())
                            .addProperty("boundaryAdjustmentSuggested", JsonBooleanSchema.builder().build())
                            .required("previousChunkConnection", "nextChunkConnection", "boundaryAdjustmentSuggested")
                            .additionalProperties(false)
                            .build())
                    .addProperty("knowledgeLookupRequest", JsonObjectSchema.builder()
                            .description("仅第 1 轮在发现知识缺口时可选返回的本地知识库补卡请求")
                            .addProperty("reason", JsonStringSchema.builder().build())
                            .addProperty("queryTerms", stringArray("本次补卡请求的查询词列表"))
                            .addProperty("requestedTypes", stringArray("本次希望优先命中的知识卡类型列表"))
                            .addProperty("anchors", stringArray("触发本次补卡的原文锚点列表"))
                            .addProperty("limit", JsonIntegerSchema.builder().build())
                            .required("reason", "queryTerms", "requestedTypes", "anchors", "limit")
                            .additionalProperties(false)
                            .build())
                    .required("translatedText", "translatorCommentary", "decisionNotes",
                            "confirmedTermUpdates", "candidateUpdates", "transitionNote")
                    .additionalProperties(false)
                    .build())
            .build();

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    static JsonSchema responseSchema() {
        return RESPONSE_SCHEMA;
    }

    public OpenAiCompatibleLlmChunkTranslationClient(ChatModel chatModel,
                                                     ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChunkTranslationLlmResult generate(String prompt) {
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
            throw new ChunkTranslationStructuredOutputException("Agent D 单轮翻译结果为空。");
        }

        try {
            return objectMapper.readValue(text, ChunkTranslationLlmResult.class);
        } catch (JsonProcessingException ex) {
            throw new ChunkTranslationStructuredOutputException("Agent D 单轮翻译结果无法解析为结构化输出。返回内容：" + text, ex);
        }
    }

    private static JsonArraySchema objectArray(String description, JsonObjectSchema itemSchema) {
        return JsonArraySchema.builder()
                .description(description)
                .items(itemSchema)
                .build();
    }

    private static JsonArraySchema stringArray(String description) {
        return JsonArraySchema.builder()
                .description(description)
                .items(JsonStringSchema.builder().build())
                .build();
    }
}
