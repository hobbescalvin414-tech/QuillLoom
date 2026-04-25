package io.quillloom.infrastructure.preprocess.chunkannotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.model.ChunkAnnotationTaskInput;
import io.quillloom.domain.knowledge.GlobalConstraint;
import io.quillloom.domain.preprocess.BookAnalysis;
import io.quillloom.domain.preprocess.ChunkDescriptor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenAiCompatibleLlmChunkAnnotationClientIntegrationTest {

    @Test
    void shouldCallQwenWithJsonSchema() {
        Assumptions.assumeTrue(Boolean.getBoolean("quillloom.test.llm-integration.enabled"),
                "Skip real LLM integration test unless explicitly enabled.");

        String baseUrl = System.getenv("QUILLLOOM_PREPROCESS_CHUNK_ANNOTATION_LLM_BASE_URL");
        String apiKey = System.getenv("QUILLLOOM_PREPROCESS_CHUNK_ANNOTATION_LLM_API_KEY");
        String modelName = System.getenv("QUILLLOOM_PREPROCESS_CHUNK_ANNOTATION_LLM_MODEL_NAME");

        Assumptions.assumeTrue(notBlank(baseUrl) && notBlank(apiKey) && notBlank(modelName),
                "未提供 Agent B LLM 集成测试所需环境变量。");

        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .strictJsonSchema(true)
                .timeout(Duration.ofSeconds(60))
                .build();

        OpenAiCompatibleLlmChunkAnnotationClient client =
                new OpenAiCompatibleLlmChunkAnnotationClient(chatModel, new ObjectMapper());
        ChunkAnnotationPromptRenderer renderer = new ChunkAnnotationPromptRenderer();

        ChunkAnnotationLlmResult result = client.generate(renderer.render(createTaskInput()));

        assertNotNull(result);
        assertFalse(result.summary() == null || result.summary().isBlank());
        assertNotNull(result.entities());
        assertNotNull(result.backgroundQuestions());
        assertNotNull(result.translationRisks());
        assertNotNull(result.keyExpressions());
    }

    private ChunkAnnotationTaskInput createTaskInput() {
        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-llm-it",
                "示例小说",
                "Alice met Bob in Paris near the river. Bob warned her that the docks were being watched.",
                "en",
                "zh"
        );
        return new ChunkAnnotationTaskInput(
                command.projectId(),
                command.title(),
                command.sourceLanguage(),
                command.targetLanguage(),
                new BookAnalysis("全书概要", "叙事结构", "冷静克制", List.of(), List.of()),
                List.of(new GlobalConstraint("c1", "style", "保持术语和人名译法一致")),
                new ChunkDescriptor("chunk-1", 1, 0, command.sourceText().length(), command.sourceText())
        );
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
