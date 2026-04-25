package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quillloom.infrastructure.preprocess.ResolvedTextTimeout;

import java.time.Duration;
import java.util.function.Function;

public class OpenAiCompatibleLlmCoarseChunkPlanClient implements LlmCoarseChunkPlanClient {

    private static final JsonSchema RESPONSE_SCHEMA = JsonSchema.builder()
            .name("coarse_chunk_planning_result")
            .rootElement(JsonObjectSchema.builder()
                    .description("小说粗分块规划结果")
                    .addProperty("boundaries", JsonArraySchema.builder()
                            .description("按顺序排列的粗块边界列表")
                            .items(JsonObjectSchema.builder()
                                    .addProperty("endParagraphIndex", JsonIntegerSchema.builder().description("当前粗块结束时对应的段落编号，必须递增且最后一个编号等于最后一段").build())
                                    .addProperty("summary", JsonStringSchema.builder().description("当前粗块概括").build())
                                    .addProperty("boundaryHint", JsonStringSchema.builder().description("为什么在这里切").build())
                                    .required("endParagraphIndex", "summary", "boundaryHint")
                                    .additionalProperties(false)
                                    .build())
                            .build())
                    .required("boundaries")
                    .additionalProperties(false)
                    .build())
            .build();

    private final Function<Duration, ChatModel> chatModelFactory;
    private final Function<String, ResolvedTextTimeout> timeoutResolver;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmCoarseChunkPlanClient(ChatModel chatModel,
                                                    ObjectMapper objectMapper) {
        this(timeout -> chatModel,
                prompt -> new ResolvedTextTimeout(prompt == null ? 0 : prompt.length(), 60),
                objectMapper);
    }

    public OpenAiCompatibleLlmCoarseChunkPlanClient(Function<Duration, ChatModel> chatModelFactory,
                                                    Function<String, ResolvedTextTimeout> timeoutResolver,
                                                    ObjectMapper objectMapper) {
        this.chatModelFactory = chatModelFactory;
        this.timeoutResolver = timeoutResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public CoarseChunkPlanningLlmResult generate(String prompt) {
        return generateDetailed(prompt).result();
    }

    @Override
    public LlmCoarseChunkPlanClientResponse generateDetailed(String prompt) {
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
            throw new IllegalStateException("LLM 粗分块规划结果为空。");
        }

        try {
            return new LlmCoarseChunkPlanClientResponse(text, objectMapper.readValue(text, CoarseChunkPlanningLlmResult.class), resolvedTimeout.timeoutSeconds());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("LLM 粗分块规划结果无法解析为结构化输出。返回内容：" + text, ex);
        }
    }
}
