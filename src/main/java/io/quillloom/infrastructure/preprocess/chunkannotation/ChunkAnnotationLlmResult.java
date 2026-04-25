package io.quillloom.infrastructure.preprocess.chunkannotation;

import io.quillloom.domain.preprocess.PersonAliasHint;

import java.util.List;

public record ChunkAnnotationLlmResult(
        String summary,
        List<String> entities,
        List<String> backgroundQuestions,
        List<String> translationRisks,
        List<String> keyExpressions,
        List<PersonAliasHint> personAliasHints
) {

    public ChunkAnnotationLlmResult(String summary,
                                    List<String> entities,
                                    List<String> backgroundQuestions,
                                    List<String> translationRisks,
                                    List<String> keyExpressions) {
        this(summary, entities, backgroundQuestions, translationRisks, keyExpressions, List.of());
    }
}
