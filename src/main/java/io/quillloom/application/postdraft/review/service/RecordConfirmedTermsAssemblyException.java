package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.port.out.LlmStructuredOutputException;

public class RecordConfirmedTermsAssemblyException extends LlmStructuredOutputException {

    public RecordConfirmedTermsAssemblyException(String message) {
        super(message);
    }
}
