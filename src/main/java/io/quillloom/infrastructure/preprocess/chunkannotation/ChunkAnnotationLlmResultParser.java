package io.quillloom.infrastructure.preprocess.chunkannotation;

import io.quillloom.application.preprocess.model.ChunkAnnotationTaskInput;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import org.springframework.stereotype.Component;

@Component
public class ChunkAnnotationLlmResultParser {

    public ChunkAnnotation parse(ChunkAnnotationTaskInput input, ChunkAnnotationLlmResult result) {
        return new ChunkAnnotation(
                input.chunk(),
                result.summary(),
                result.entities(),
                result.backgroundQuestions(),
                result.translationRisks(),
                result.keyExpressions(),
                result.personAliasHints()
        );
    }
}
