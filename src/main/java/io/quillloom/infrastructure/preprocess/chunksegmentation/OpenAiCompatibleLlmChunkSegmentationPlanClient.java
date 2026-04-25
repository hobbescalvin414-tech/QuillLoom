package io.quillloom.infrastructure.preprocess.chunksegmentation;

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

public class OpenAiCompatibleLlmChunkSegmentationPlanClient implements LlmChunkSegmentationPlanClient {

    private static final JsonSchema RESPONSE_SCHEMA = JsonSchema.builder()
            .name("chunk_segmentation_planning_result")
            .rootElement(JsonObjectSchema.builder()
                    .description("coarse block 内的 chunk 边界规划结果")
                    .addProperty("boundaries", JsonArraySchema.builder()
                            .description("按顺序排列的 chunk 边界列表")
                            .items(JsonObjectSchema.builder()
                                    .addProperty("endParagraphIndex", JsonIntegerSchema.builder().description("当前 chunk 结束时对应的段落编号，必须递增且最后一个编号等于最后一段").build())
                                    .addProperty("boundaryHint", JsonStringSchema.builder().description("为什么在这里切").build())
                                    .required("endParagraphIndex", "boundaryHint")
                                    .additionalProperties(false)
                                    .build())
                            .build())
                    .required("boundaries")
                    .additionalProperties(false)
                    .build())
            .build();

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmChunkSegmentationPlanClient(ChatModel chatModel,
                                                          ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChunkSegmentationPlanningLlmResult generate(String prompt) {
        return generateDetailed(prompt).result();
    }

    @Override
    public LlmChunkSegmentationPlanClientResponse generateDetailed(String prompt) {
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
            throw new IllegalStateException("LLM 细切分规划结果为空。");
        }

        try {
            return new LlmChunkSegmentationPlanClientResponse(text, objectMapper.readValue(text, ChunkSegmentationPlanningLlmResult.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("LLM 细切分规划结果无法解析为结构化输出。返回内容：" + text, ex);
        }
    }
}