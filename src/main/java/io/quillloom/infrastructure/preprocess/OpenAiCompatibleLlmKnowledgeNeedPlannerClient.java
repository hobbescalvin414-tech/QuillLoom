package io.quillloom.infrastructure.preprocess;

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

public class OpenAiCompatibleLlmKnowledgeNeedPlannerClient implements LlmKnowledgeNeedPlannerClient {

    private static final JsonSchema RESPONSE_SCHEMA = JsonSchema.builder()
            .name("knowledge_need_planning_result")
            .rootElement(JsonObjectSchema.builder()
                    .addProperty("needs", JsonArraySchema.builder()
                            .items(JsonObjectSchema.builder()
                                    .addProperty("shouldSearch", JsonBooleanSchema.builder().build())
                                    .addProperty("needKind", JsonEnumSchema.builder().enumValues(List.of(
                                            "BACKGROUND_CONTEXT",
                                            "TRANSLATION_SUPPORT",
                                            "EXPRESSION_CONTEXT",
                                            "ENTITY_PROFILE",
                                            "GENERAL_ENRICHMENT"
                                    )).build())
                                    .addProperty("signalSource", JsonEnumSchema.builder().enumValues(List.of(
                                            "backgroundQuestion",
                                            "translationRisk",
                                            "keyExpression",
                                            "entity"
                                    )).build())
                                    .addProperty("searchIntent", JsonStringSchema.builder().build())
                                    .addProperty("coverageKey", JsonStringSchema.builder().build())
                                    .addProperty("cardType", JsonEnumSchema.builder().enumValues(List.of(
                                            "HISTORICAL_BACKGROUND",
                                            "CULTURAL_BACKGROUND",
                                            "IMAGERY",
                                            "SETTING_ENTRY",
                                            "TERM_EXPLANATION",
                                            "CHARACTER_PROFILE"
                                    )).build())
                                    .addProperty("queryText", JsonStringSchema.builder().build())
                                    .addProperty("anchorNames", stringArray())
                                    .addProperty("keywords", stringArray())
                                    .addProperty("originRefs", stringArray())
                                    .addProperty("reason", JsonStringSchema.builder().build())
                                    .addProperty("priority", JsonIntegerSchema.builder().build())
                                    .required(
                                            "shouldSearch",
                                            "needKind",
                                            "signalSource",
                                            "searchIntent",
                                            "coverageKey",
                                            "cardType",
                                            "queryText",
                                            "anchorNames",
                                            "keywords",
                                            "originRefs",
                                            "reason",
                                            "priority"
                                    )
                                    .additionalProperties(false)
                                    .build())
                            .build())
                    .required("needs")
                    .additionalProperties(false)
                    .build())
            .build();

    private final ChatModel chatModel;

    public OpenAiCompatibleLlmKnowledgeNeedPlannerClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String generate(String prompt) {
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
            throw new IllegalStateException("C0 knowledge need planner LLM 返回空结果。");
        }
        return text;
    }

    private static JsonArraySchema stringArray() {
        return JsonArraySchema.builder()
                .items(JsonStringSchema.builder().build())
                .build();
    }
}
