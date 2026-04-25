package io.quillloom.infrastructure.translation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryingLlmChunkTranslationClientTest {

    @Test
    void shouldRetryTransientTranslationLlmFailure() {
        ScriptedClient delegate = new ScriptedClient(
                new ChunkTranslationTransientException("429 rate limit"),
                response("ok")
        );
        List<Long> sleeps = new ArrayList<>();
        RetryingLlmChunkTranslationClient client = new RetryingLlmChunkTranslationClient(
                delegate,
                3,
                Duration.ofMillis(5),
                sleeps::add
        );

        LlmChunkTranslationClientResponse response = client.generateDetailed("当前是第 2 轮");

        assertEquals("ok", response.result().translatedText());
        assertEquals(2, delegate.calls);
        assertEquals(List.of(5L), sleeps);
    }

    @Test
    void shouldNotRetryStructuredOutputFailure() {
        ScriptedClient delegate = new ScriptedClient(new ChunkTranslationStructuredOutputException("bad json"));
        RetryingLlmChunkTranslationClient client = new RetryingLlmChunkTranslationClient(delegate, 3, Duration.ZERO, millis -> {
        });

        assertThrows(ChunkTranslationStructuredOutputException.class, () -> client.generateDetailed("当前是第 2 轮"));
        assertEquals(1, delegate.calls);
    }

    @Test
    void shouldRethrowTransientFailureAfterRetryExhausted() {
        ScriptedClient delegate = new ScriptedClient(
                new ChunkTranslationTransientException("503 unavailable"),
                new ChunkTranslationTransientException("503 unavailable"),
                new ChunkTranslationTransientException("503 unavailable")
        );
        RetryingLlmChunkTranslationClient client = new RetryingLlmChunkTranslationClient(delegate, 3, Duration.ZERO, millis -> {
        });

        assertThrows(ChunkTranslationTransientException.class, () -> client.generateDetailed("当前是第 1 轮"));
        assertEquals(3, delegate.calls);
    }

    private static LlmChunkTranslationClientResponse response(String translatedText) {
        return new LlmChunkTranslationClientResponse(null, new ChunkTranslationLlmResult(
                translatedText,
                "commentary",
                List.of(),
                List.of(),
                List.of(),
                new ChunkTranslationTransitionNoteResult("", "", false)
        ));
    }

    private static final class ScriptedClient implements LlmChunkTranslationClient {
        private final List<Object> outputs;
        private int calls = 0;

        private ScriptedClient(Object... outputs) {
            this.outputs = List.of(outputs);
        }

        @Override
        public ChunkTranslationLlmResult generate(String prompt) {
            return generateDetailed(prompt).result();
        }

        @Override
        public LlmChunkTranslationClientResponse generateDetailed(String prompt) {
            Object output = outputs.get(calls++);
            if (output instanceof RuntimeException exception) {
                throw exception;
            }
            return (LlmChunkTranslationClientResponse) output;
        }
    }
}
