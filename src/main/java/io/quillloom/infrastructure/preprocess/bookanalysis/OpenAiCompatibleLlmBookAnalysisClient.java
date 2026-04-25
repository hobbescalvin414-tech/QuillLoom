package io.quillloom.infrastructure.preprocess.bookanalysis;

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
import io.quillloom.infrastructure.preprocess.ResolvedTextTimeout;

import java.time.Duration;
import java.util.function.Function;

public class OpenAiCompatibleLlmBookAnalysisClient implements LlmBookAnalysisClient {

    private static final JsonSchema RESPONSE_SCHEMA = JsonSchema.builder()
            .name("book_analysis_result")
            .rootElement(JsonObjectSchema.builder()
                    .description("小说全书分析结果")
                    .addProperty("synopsis", JsonStringSchema.builder().description("全书概要").build())
                    .addProperty("narrativeOutline", JsonStringSchema.builder().description("叙事结构概述").build())
                    .addProperty("styleProfile", JsonStringSchema.builder().description("语言与叙述风格画像").build())
                    .addProperty("globalRisks", stringArray("全书级翻译风险列表"))
                    .addProperty("translationStrategyNotes", stringArray("全书级翻译策略提示列表"))
                    .addProperty("globalConstraints", JsonArraySchema.builder()
                            .description("全局约束列表")
                            .items(JsonObjectSchema.builder()
                                    .addProperty("type", JsonStringSchema.builder().description("约束类型").build())
                                    .addProperty("description", JsonStringSchema.builder().description("约束说明").build())
                                    .required("type", "description")
                                    .additionalProperties(false)
                                    .build())
                            .build())
                    .required("synopsis", "narrativeOutline", "styleProfile", "globalRisks", "translationStrategyNotes", "globalConstraints")
                    .additionalProperties(false)
                    .build())
            .build();

    private final Function<Duration, ChatModel> chatModelFactory;
    private final Function<String, ResolvedTextTimeout> timeoutResolver;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmBookAnalysisClient(ChatModel chatModel,
                                                 ObjectMapper objectMapper) {
        this(timeout -> chatModel,
                prompt -> new ResolvedTextTimeout(prompt == null ? 0 : prompt.length(), 60),
                objectMapper);
    }

    public OpenAiCompatibleLlmBookAnalysisClient(Function<Duration, ChatModel> chatModelFactory,
                                                 Function<String, ResolvedTextTimeout> timeoutResolver,
                                                 ObjectMapper objectMapper) {
        this.chatModelFactory = chatModelFactory;
        this.timeoutResolver = timeoutResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public BookAnalysisLlmResult generate(String prompt) {
        ResolvedTextTimeout resolvedTimeout = timeoutResolver.apply(prompt);
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(RESPONSE_SCHEMA)
                        .build())
                .build();

        ChatModel chatModel = chatModelFactory.apply(resolvedTimeout.toDuration());
        ChatResponse response = chatModel.chat(request);
        String text = response.aiMessage() == null ? null : response.aiMessage().text();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("LLM 全书分析结果为空。");
        }

        try {
            return objectMapper.readValue(text, BookAnalysisLlmResult.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("LLM 全书分析结果无法解析为结构化输出。返回内容：" + text, ex);
        }
    }

    private static JsonArraySchema stringArray(String description) {
        return JsonArraySchema.builder()
                .description(description)
                .items(JsonStringSchema.builder().build())
                .build();
    }
}
