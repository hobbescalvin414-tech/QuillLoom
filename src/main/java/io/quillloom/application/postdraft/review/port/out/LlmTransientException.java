package io.quillloom.application.postdraft.review.port.out;

public class LlmTransientException extends RuntimeException {

    public LlmTransientException(String message) {
        super(message);
    }

    public LlmTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
