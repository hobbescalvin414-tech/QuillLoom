package io.quillloom.infrastructure.preprocess;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quillloom.infrastructure.llm.WorkflowFixedLlmTimeouts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(KnowledgeSearchOrganizerLlmProperties.class)
public class KnowledgeSearchOrganizerConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "knowledgeSearchResultOrganizer")
    @Primary
    public KnowledgeSearchResultOrganizer knowledgeSearchResultOrganizer(KnowledgeSearchOrganizerLlmProperties properties,
                                                                         KnowledgeSearchOrganizerPromptRenderer promptRenderer,
                                                                         KnowledgeSearchResultOrganizerParser parser,
                                                                         ObjectMapper objectMapper) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("C0 organizer 已改为 LLM 主链，必须显式启用 quillloom.preprocess.knowledge-search.organizer.llm.enabled=true。");
        }
        validate(properties);
        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(properties.getModelName())
                .strictJsonSchema(true)
                .timeout(WorkflowFixedLlmTimeouts.standardTimeout())
                .logRequests(properties.isLogRequests())
                .logResponses(properties.isLogResponses())
                .build();
        LlmKnowledgeSearchResultOrganizerClient llmClient =
                new OpenAiCompatibleLlmKnowledgeSearchResultOrganizerClient(chatModel, objectMapper);
        return new LlmKnowledgeSearchResultOrganizer(promptRenderer, llmClient, parser);
    }

    private void validate(KnowledgeSearchOrganizerLlmProperties properties) {
        if (isBlank(properties.getBaseUrl())) {
            throw new IllegalStateException("Missing organizer llm baseUrl");
        }
        if (isBlank(properties.getApiKey())) {
            throw new IllegalStateException("Missing organizer llm apiKey");
        }
        if (isBlank(properties.getModelName())) {
            throw new IllegalStateException("Missing organizer llm modelName");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
