package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.port.out.LlmStructuredOutputException;

public class ReviewAgentNextStepStructuredOutputException extends LlmStructuredOutputException {

    public ReviewAgentNextStepStructuredOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
