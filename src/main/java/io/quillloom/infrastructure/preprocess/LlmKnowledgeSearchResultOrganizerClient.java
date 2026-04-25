package io.quillloom.infrastructure.preprocess;

public interface LlmKnowledgeSearchResultOrganizerClient {

    KnowledgeSearchOrganizerLlmResult generate(String prompt);
}