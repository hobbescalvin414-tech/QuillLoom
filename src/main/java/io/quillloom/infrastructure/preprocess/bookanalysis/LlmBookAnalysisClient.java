package io.quillloom.infrastructure.preprocess.bookanalysis;

public interface LlmBookAnalysisClient {

    BookAnalysisLlmResult generate(String prompt);
}