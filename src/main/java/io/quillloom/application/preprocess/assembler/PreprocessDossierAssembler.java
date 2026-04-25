package io.quillloom.application.preprocess.assembler;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.domain.book.BookProject;
import io.quillloom.domain.preprocess.ChunkAnnotationBundle;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import io.quillloom.domain.preprocess.KnowledgeEnrichmentBundle;
import io.quillloom.domain.preprocess.PreprocessDossier;
import org.springframework.stereotype.Component;

@Component
public class PreprocessDossierAssembler {

    public PreprocessDossier assemble(PreprocessBookCommand command,
                                      GlobalAnalysisBundle globalAnalysis,
                                      ChunkAnnotationBundle chunkAnnotations,
                                      KnowledgeEnrichmentBundle knowledgeEnrichment) {
        BookProject project = new BookProject(
                command.projectId(),
                command.title(),
                command.sourceLanguage(),
                command.targetLanguage()
        );

        return new PreprocessDossier(project, globalAnalysis, chunkAnnotations, knowledgeEnrichment);
    }
}
