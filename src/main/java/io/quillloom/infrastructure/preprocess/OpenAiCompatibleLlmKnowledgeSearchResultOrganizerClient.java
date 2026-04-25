package io.quillloom.infrastructure.preprocess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;

public class OpenAiCompatibleLlmKnowledgeSearchResultOrganizerClient implements LlmKnowledgeSearchResultOrganizerClient {

    private static final JsonSchema RESPONSE_SCHEMA = JsonSchema.builder()
            .name("knowledge_search_organizer_result")
            .rootElement(JsonObjectSchema.builder()
                    .addProperty("shouldCreateCard", JsonBooleanSchema.builder().build())
                    .addProperty("title", JsonStringSchema.builder().build())
                    .addProperty("summary", JsonStringSchema.builder().build())
                    .addProperty("translationNotes", JsonArraySchema.builder().items(JsonStringSchema.builder().build()).build())
                    .addProperty("keywords", JsonArraySchema.builder().items(JsonStringSchema.builder().build()).build())
                    .addProperty("anchorNames", JsonArraySchema.builder().items(JsonStringSchema.builder().build()).build())
                    .addProperty("usedEvidenceIndexes", JsonArraySchema.builder().items(JsonIntegerSchema.builder().build()).build())
                    .addProperty("confidence", JsonEnumSchema.builder().enumValues(List.of("HIGH", "MEDIUM", "LOW")).build())
                    .addProperty("rejectionReason", JsonStringSchema.builder().build())
                    .required("shouldCreateCard", "title", "summary", "translationNotes", "keywords", "anchorNames", "usedEvidenceIndexes", "confidence", "rejectionReason")
                    .additionalProperties(false)
                    .build())
            .build();

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmKnowledgeSearchResultOrganizerClient(ChatModel chatModel,
                                                                   ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public KnowledgeSearchOrganizerLlmResult generate(String prompt) {
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
            throw new IllegalStateException("LLM knowledge search organizer returned empty result");
        }
        try {
            return objectMapper.readValue(text, KnowledgeSearchOrganizerLlmResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("LLM knowledge search organizer returned invalid JSON: " + text, exception);
        }
    }
}
