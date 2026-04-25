package io.quillloom.infrastructure.translation;

public class ChunkTranslationTransientException extends RuntimeException {

    public ChunkTranslationTransientException(String message) {
        super(message);
    }

    public ChunkTranslationTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
