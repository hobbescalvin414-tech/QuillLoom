package io.quillloom.infrastructure.preprocess.bookanalysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quillloom.infrastructure.preprocess.ResolvedTextTimeout;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiCompatibleLlmBookAnalysisClientTest {

    @Test
    void shouldCreateChatModelWithResolvedTimeoutBeforeCallingLlm() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"synopsis\":\"s\",\"narrativeOutline\":\"n\",\"styleProfile\":\"p\",\"globalRisks\":[],\"translationStrategyNotes\":[],\"globalConstraints\":[]}"))
                .build());

        AtomicReference<Duration> capturedTimeout = new AtomicReference<>();
        OpenAiCompatibleLlmBookAnalysisClient client = new OpenAiCompatibleLlmBookAnalysisClient(
                timeout -> {
                    capturedTimeout.set(timeout);
                    return chatModel;
                },
                prompt -> new ResolvedTextTimeout(prompt.length(), 240),
                new ObjectMapper()
        );

        client.generate("prompt");

        assertEquals(Duration.ofSeconds(240), capturedTimeout.get());
    }
}
