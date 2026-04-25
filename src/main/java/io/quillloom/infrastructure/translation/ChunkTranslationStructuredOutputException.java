package io.quillloom.infrastructure.translation;

public class ChunkTranslationStructuredOutputException extends RuntimeException {

    public ChunkTranslationStructuredOutputException(String message) {
        super(message);
    }

    public ChunkTranslationStructuredOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
