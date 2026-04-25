package io.quillloom.application.preprocess.port.out;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.domain.preprocess.ChunkAnnotationBundle;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import io.quillloom.domain.preprocess.KnowledgeEnrichmentBundle;

public interface KnowledgeEnricher {

    KnowledgeEnrichmentBundle enrich(PreprocessBookCommand command,
                                     GlobalAnalysisBundle globalAnalysis,
                                     ChunkAnnotationBundle chunkAnnotations);
}
