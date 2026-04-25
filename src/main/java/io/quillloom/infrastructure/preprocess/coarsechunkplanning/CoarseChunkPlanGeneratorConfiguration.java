package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quillloom.application.preprocess.port.out.CoarseChunkPlanGenerator;
import io.quillloom.infrastructure.preprocess.TextLengthTimeoutPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(CoarseChunkPlanningLlmProperties.class)
public class CoarseChunkPlanGeneratorConfiguration {

    @Bean
    public LlmCoarseChunkPlanClient llmCoarseChunkPlanClient(CoarseChunkPlanningLlmProperties properties,
                                                             ObjectMapper objectMapper) {
        validate(properties);

        TextLengthTimeoutPolicy timeoutPolicy = new TextLengthTimeoutPolicy();
        return new OpenAiCompatibleLlmCoarseChunkPlanClient(
                timeout -> createChatModel(properties, timeout),
                prompt -> timeoutPolicy.resolve(
                        prompt,
                        properties.getBaseTimeoutSeconds(),
                        properties.getTimeoutStepChars(),
                        properties.getTimeoutStepSeconds(),
                        properties.getMaxTimeoutSeconds()
                ),
                objectMapper
        );
    }

    @Bean
    @Primary
    public CoarseChunkPlanGenerator activeCoarseChunkPlanGenerator(LlmCoarseChunkPlanGenerator llmGenerator) {
        return llmGenerator;
    }

    private void validate(CoarseChunkPlanningLlmProperties properties) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Agent A 已切换为 LLM 粗划分主链，必须显式启用 coarse chunk planning llm 配置。\n请设置 quillloom.preprocess.coarse-chunk-planning.llm.enabled=true。\n");
        }
        if (isBlank(properties.getBaseUrl())) {
            throw new IllegalStateException("启用 Agent A LLM 粗划分时必须提供 baseUrl。");
        }
        if (isBlank(properties.getApiKey())) {
            throw new IllegalStateException("启用 Agent A LLM 粗划分时必须提供 apiKey。");
        }
        if (isBlank(properties.getModelName())) {
            throw new IllegalStateException("启用 Agent A LLM 粗划分时必须提供 modelName。");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ChatModel createChatModel(CoarseChunkPlanningLlmProperties properties, Duration timeout) {
        return OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(properties.getModelName())
                .strictJsonSchema(true)
                .timeout(timeout)
                .logRequests(properties.isLogRequests())
                .logResponses(properties.isLogResponses())
                .build();
    }
}
