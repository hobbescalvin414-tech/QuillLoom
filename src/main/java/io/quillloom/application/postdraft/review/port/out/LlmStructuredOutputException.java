package io.quillloom.application.postdraft.review.port.out;

public class LlmStructuredOutputException extends RuntimeException {

    private final ReviewAgentErrorContext reviewAgentErrorContext;

    public LlmStructuredOutputException(String message) {
        this(message, null, null);
    }

    public LlmStructuredOutputException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public LlmStructuredOutputException(String message, ReviewAgentErrorContext reviewAgentErrorContext) {
        this(message, null, reviewAgentErrorContext);
    }

    public LlmStructuredOutputException(String message,
                                        Throwable cause,
                                        ReviewAgentErrorContext reviewAgentErrorContext) {
        super(message, cause);
        this.reviewAgentErrorContext = reviewAgentErrorContext;
    }

    public ReviewAgentErrorContext reviewAgentErrorContext() {
        return reviewAgentErrorContext;
    }

    public record ReviewAgentErrorContext(String validationError, String previousInvalidToolName) {
    }
}
