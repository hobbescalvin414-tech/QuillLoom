package io.quillloom.infrastructure.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quillloom.application.translation.port.out.ChunkTranslator;
import io.quillloom.infrastructure.llm.WorkflowFixedLlmTimeouts;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties({TranslationLlmProperties.class, TranslationPromptProperties.class})
public class ChunkTranslatorConfiguration {

    static Duration translationTimeout() {
        return WorkflowFixedLlmTimeouts.standardTimeout();
    }

    @Bean
    public LlmChunkTranslationClient llmChunkTranslationClient(TranslationLlmProperties properties,
                                                               ObjectMapper objectMapper) {
        validate(properties);

        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(properties.getModelName())
                .strictJsonSchema(true)
                .timeout(translationTimeout())
                .logRequests(properties.isLogRequests())
                .logResponses(properties.isLogResponses())
                .build();

        return new RetryingLlmChunkTranslationClient(new OpenAiCompatibleLlmChunkTranslationClient(chatModel, objectMapper));
    }

    @Bean
    @Primary
    public ChunkTranslator activeChunkTranslator(LlmChunkTranslator llmChunkTranslator) {
        return llmChunkTranslator;
    }

    private void validate(TranslationLlmProperties properties) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Agent D 已切换为 LLM 单轮执行链，必须显式启用 translation llm 配置。\n请设置 quillloom.translation.chunk-translation.llm.enabled=true。");
        }
        if (isBlank(properties.getBaseUrl())) {
            throw new IllegalStateException("启用 Agent D 单轮翻译时必须提供 baseUrl。");
        }
        if (isBlank(properties.getApiKey())) {
            throw new IllegalStateException("启用 Agent D 单轮翻译时必须提供 apiKey。");
        }
        if (isBlank(properties.getModelName())) {
            throw new IllegalStateException("启用 Agent D 单轮翻译时必须提供 modelName。");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
