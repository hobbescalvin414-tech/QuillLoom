package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenAiCompatibleLlmCoarseChunkPlanClientIntegrationTest {

    @Test
    void shouldCallConfiguredModelWithJsonSchema() {
        Assumptions.assumeTrue(Boolean.getBoolean("quillloom.test.llm-integration.enabled"),
                "Skip real LLM integration test unless explicitly enabled.");

        String baseUrl = System.getenv("QUILLLOOM_PREPROCESS_COARSE_CHUNK_PLANNING_LLM_BASE_URL");
        String apiKey = System.getenv("QUILLLOOM_PREPROCESS_COARSE_CHUNK_PLANNING_LLM_API_KEY");
        String modelName = System.getenv("QUILLLOOM_PREPROCESS_COARSE_CHUNK_PLANNING_LLM_MODEL_NAME");

        Assumptions.assumeTrue(notBlank(baseUrl) && notBlank(apiKey) && notBlank(modelName),
                "未提供 Agent A 粗划分 LLM 集成测试所需环境变量。");

        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .strictJsonSchema(true)
                .timeout(Duration.ofSeconds(60))
                .build();

        OpenAiCompatibleLlmCoarseChunkPlanClient client =
                new OpenAiCompatibleLlmCoarseChunkPlanClient(chatModel, new ObjectMapper());
        CoarseChunkPlanningPromptRenderer renderer = new CoarseChunkPlanningPromptRenderer();

        CoarseChunkPlanningLlmResult result = client.generate(renderer.render(new io.quillloom.application.preprocess.model.CoarseChunkPlanningTaskInput(
                "project-llm-it",
                "示例小说",
                "Alice met Bob in Paris near the river. Bob warned her that the docks were being watched.",
                "en",
                "zh"
        )));

        assertNotNull(result);
        assertNotNull(result.boundaries());
        assertFalse(result.boundaries().isEmpty());
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
