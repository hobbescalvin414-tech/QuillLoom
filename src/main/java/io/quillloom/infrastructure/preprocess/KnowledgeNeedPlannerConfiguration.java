package io.quillloom.infrastructure.preprocess;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quillloom.infrastructure.llm.WorkflowFixedLlmTimeouts;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(KnowledgeNeedPlanningLlmProperties.class)
public class KnowledgeNeedPlannerConfiguration {

    @Bean
    public LlmKnowledgeNeedPlannerClient llmKnowledgeNeedPlannerClient(KnowledgeNeedPlanningLlmProperties properties) {
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
        return new OpenAiCompatibleLlmKnowledgeNeedPlannerClient(chatModel);
    }

    @Bean
    @Primary
    public KnowledgeNeedPlanner knowledgeNeedPlanner(LlmKnowledgeNeedPlanner planner) {
        return planner;
    }

    private void validate(KnowledgeNeedPlanningLlmProperties properties) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("C0 knowledge need planner 已改为 LLM 主链，必须显式启用 quillloom.preprocess.knowledge-search.planner.llm.enabled=true。");
        }
        if (isBlank(properties.getBaseUrl())) {
            throw new IllegalStateException("C0 knowledge need planner 缺少 baseUrl。");
        }
        if (isBlank(properties.getApiKey())) {
            throw new IllegalStateException("C0 knowledge need planner 缺少 apiKey。");
        }
        if (isBlank(properties.getModelName())) {
            throw new IllegalStateException("C0 knowledge need planner 缺少 modelName。");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
