package io.quillloom.infrastructure.preprocess.coarsechunkplanning;

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

class OpenAiCompatibleLlmCoarseChunkPlanClientTest {

    @Test
    void shouldCreateChatModelWithResolvedTimeoutBeforeCallingLlm() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"boundaries\":[]}"))
                .build());

        AtomicReference<Duration> capturedTimeout = new AtomicReference<>();
        OpenAiCompatibleLlmCoarseChunkPlanClient client = new OpenAiCompatibleLlmCoarseChunkPlanClient(
                timeout -> {
                    capturedTimeout.set(timeout);
                    return chatModel;
                },
                prompt -> new ResolvedTextTimeout(prompt.length(), 300),
                new ObjectMapper()
        );

        client.generate("prompt");

        assertEquals(Duration.ofSeconds(300), capturedTimeout.get());
    }
}
