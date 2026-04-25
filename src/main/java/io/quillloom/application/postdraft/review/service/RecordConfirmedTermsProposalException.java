package io.quillloom.application.postdraft.review.service;

import io.quillloom.application.postdraft.review.port.out.LlmStructuredOutputException;

public class RecordConfirmedTermsProposalException extends LlmStructuredOutputException {

    public RecordConfirmedTermsProposalException(String message, Throwable cause) {
        super(message, cause);
    }
}
