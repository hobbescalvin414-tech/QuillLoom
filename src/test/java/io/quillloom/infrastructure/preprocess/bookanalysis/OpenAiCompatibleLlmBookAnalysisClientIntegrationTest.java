package io.quillloom.infrastructure.preprocess.bookanalysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quillloom.application.preprocess.model.BookAnalysisTaskInput;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenAiCompatibleLlmBookAnalysisClientIntegrationTest {

    @Test
    void shouldCallConfiguredModelWithJsonSchema() {
        Assumptions.assumeTrue(Boolean.getBoolean("quillloom.test.llm-integration.enabled"),
                "Skip real LLM integration test unless explicitly enabled.");

        String baseUrl = System.getenv("QUILLLOOM_PREPROCESS_BOOK_ANALYSIS_LLM_BASE_URL");
        String apiKey = System.getenv("QUILLLOOM_PREPROCESS_BOOK_ANALYSIS_LLM_API_KEY");
        String modelName = System.getenv("QUILLLOOM_PREPROCESS_BOOK_ANALYSIS_LLM_MODEL_NAME");

        Assumptions.assumeTrue(notBlank(baseUrl) && notBlank(apiKey) && notBlank(modelName),
                "未提供 Agent A LLM 集成测试所需环境变量。");

        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .strictJsonSchema(true)
                .timeout(Duration.ofSeconds(60))
                .build();

        OpenAiCompatibleLlmBookAnalysisClient client =
                new OpenAiCompatibleLlmBookAnalysisClient(chatModel, new ObjectMapper());
        BookAnalysisPromptRenderer renderer = new BookAnalysisPromptRenderer();

        BookAnalysisLlmResult result = client.generate(renderer.render(createTaskInput()));

        assertNotNull(result);
        assertFalse(result.synopsis() == null || result.synopsis().isBlank());
        assertFalse(result.narrativeOutline() == null || result.narrativeOutline().isBlank());
        assertFalse(result.styleProfile() == null || result.styleProfile().isBlank());
        assertNotNull(result.globalRisks());
        assertNotNull(result.translationStrategyNotes());
        assertNotNull(result.globalConstraints());
    }

    private BookAnalysisTaskInput createTaskInput() {
        return new BookAnalysisTaskInput(
                "project-llm-it",
                "示例小说",
                "Alice met Bob in Paris near the river. Bob warned her that the docks were being watched.",
                "en",
                "zh"
        );
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
