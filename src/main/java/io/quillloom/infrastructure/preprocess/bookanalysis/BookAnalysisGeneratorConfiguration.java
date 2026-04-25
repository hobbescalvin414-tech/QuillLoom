package io.quillloom.infrastructure.preprocess.bookanalysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quillloom.application.preprocess.port.out.BookAnalysisGenerator;
import io.quillloom.infrastructure.preprocess.TextLengthTimeoutPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(BookAnalysisLlmProperties.class)
public class BookAnalysisGeneratorConfiguration {

    @Bean
    public LlmBookAnalysisClient llmBookAnalysisClient(BookAnalysisLlmProperties properties,
                                                       ObjectMapper objectMapper) {
        validate(properties);

        TextLengthTimeoutPolicy timeoutPolicy = new TextLengthTimeoutPolicy();
        return new OpenAiCompatibleLlmBookAnalysisClient(
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
    public BookAnalysisGenerator activeBookAnalysisGenerator(LlmBookAnalysisGenerator llmGenerator) {
        return llmGenerator;
    }

    private void validate(BookAnalysisLlmProperties properties) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Agent A 已切换为 LLM 主链，必须显式启用 book analysis llm 配置。\n请设置 quillloom.preprocess.book-analysis.llm.enabled=true。\n");
        }
        if (isBlank(properties.getBaseUrl())) {
            throw new IllegalStateException("启用 Agent A LLM 全书分析时必须提供 baseUrl。");
        }
        if (isBlank(properties.getApiKey())) {
            throw new IllegalStateException("启用 Agent A LLM 全书分析时必须提供 apiKey。");
        }
        if (isBlank(properties.getModelName())) {
            throw new IllegalStateException("启用 Agent A LLM 全书分析时必须提供 modelName。");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ChatModel createChatModel(BookAnalysisLlmProperties properties, Duration timeout) {
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
