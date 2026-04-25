package io.quillloom.infrastructure.preprocess.chunksegmentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quillloom.application.preprocess.port.out.ChunkSegmentationPlanGenerator;
import io.quillloom.infrastructure.llm.WorkflowFixedLlmTimeouts;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(ChunkSegmentationPlanningLlmProperties.class)
public class ChunkSegmentationPlanGeneratorConfiguration {

    @Bean
    public LlmChunkSegmentationPlanClient llmChunkSegmentationPlanClient(ChunkSegmentationPlanningLlmProperties properties,
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

        return new OpenAiCompatibleLlmChunkSegmentationPlanClient(chatModel, objectMapper);
    }

    @Bean
    @Primary
    public ChunkSegmentationPlanGenerator activeChunkSegmentationPlanGenerator(LlmChunkSegmentationPlanGenerator llmGenerator) {
        return llmGenerator;
    }

    private void validate(ChunkSegmentationPlanningLlmProperties properties) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Agent B 已切换为 LLM 细切分主链，必须显式启用 chunk segmentation llm 配置。\n请设置 quillloom.preprocess.chunk-segmentation.llm.enabled=true。\n");
        }
        if (isBlank(properties.getBaseUrl())) {
            throw new IllegalStateException("启用 Agent B LLM 细切分时必须提供 baseUrl。");
        }
        if (isBlank(properties.getApiKey())) {
            throw new IllegalStateException("启用 Agent B LLM 细切分时必须提供 apiKey。");
        }
        if (isBlank(properties.getModelName())) {
            throw new IllegalStateException("启用 Agent B LLM 细切分时必须提供 modelName。");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
