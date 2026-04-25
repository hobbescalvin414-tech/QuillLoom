package io.quillloom.infrastructure.preprocess.chunkannotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quillloom.application.preprocess.port.out.ChunkAnnotationGenerator;
import io.quillloom.infrastructure.llm.WorkflowFixedLlmTimeouts;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(ChunkAnnotationLlmProperties.class)
public class ChunkAnnotationGeneratorConfiguration {

    @Bean
    public LlmChunkAnnotationClient llmChunkAnnotationClient(ChunkAnnotationLlmProperties properties,
                                                             ObjectMapper objectMapper) {
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

        return new OpenAiCompatibleLlmChunkAnnotationClient(chatModel, objectMapper);
    }

    @Bean
    @Primary
    public ChunkAnnotationGenerator activeChunkAnnotationGenerator(LlmChunkAnnotationGenerator llmGenerator) {
        return llmGenerator;
    }

    private void validate(ChunkAnnotationLlmProperties properties) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Agent B 已切换为 LLM 主链，必须显式启用 chunk annotation llm 配置。\n请设置 quillloom.preprocess.chunk-annotation.llm.enabled=true。\n");
        }
        if (isBlank(properties.getBaseUrl())) {
            throw new IllegalStateException("启用 Agent B LLM 标注时必须提供 baseUrl。");
        }
        if (isBlank(properties.getApiKey())) {
            throw new IllegalStateException("启用 Agent B LLM 标注时必须提供 apiKey。");
        }
        if (isBlank(properties.getModelName())) {
            throw new IllegalStateException("启用 Agent B LLM 标注时必须提供 modelName。");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
