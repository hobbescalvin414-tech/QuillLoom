package io.quillloom.application.postdraft.review.port.out;

public class LlmStructuredOutputException extends RuntimeException {

    public LlmStructuredOutputException(String message) {
        super(message);
    }

    public LlmStructuredOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
