package io.quillloom.infrastructure.preprocess.chunkannotation;

public class ChunkAnnotationStructuredOutputException extends IllegalStateException {

    private final String reasonCode;
    private final String detail;
    private final String rawResponse;
    private final boolean recoverable;

    public ChunkAnnotationStructuredOutputException(String reasonCode,
                                                    String detail,
                                                    String rawResponse,
                                                    boolean recoverable,
                                                    Throwable cause) {
        super(detail, cause);
        this.reasonCode = reasonCode;
        this.detail = detail;
        this.rawResponse = rawResponse;
        this.recoverable = recoverable;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String detail() {
        return detail;
    }

    public String rawResponse() {
        return rawResponse;
    }

    public boolean recoverable() {
        return recoverable;
    }
}
