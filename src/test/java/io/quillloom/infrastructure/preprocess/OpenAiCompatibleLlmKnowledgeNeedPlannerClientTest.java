package io.quillloom.infrastructure.preprocess;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiCompatibleLlmKnowledgeNeedPlannerClientTest {

    @Test
    void shouldAttachJsonSchemaWithConstrainedCardTypeAndNeedKind() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"needs\":[]}"))
                .build());

        OpenAiCompatibleLlmKnowledgeNeedPlannerClient client =
                new OpenAiCompatibleLlmKnowledgeNeedPlannerClient(chatModel);

        String result = client.generate("prompt");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        org.mockito.Mockito.verify(chatModel).chat(captor.capture());
        ChatRequest capturedRequest = captor.getValue();

        assertEquals("{\"needs\":[]}", result);
        assertNotNull(capturedRequest);
        assertEquals(ResponseFormatType.JSON, capturedRequest.responseFormat().type());
        assertNotNull(capturedRequest.responseFormat().jsonSchema());
    }
}
