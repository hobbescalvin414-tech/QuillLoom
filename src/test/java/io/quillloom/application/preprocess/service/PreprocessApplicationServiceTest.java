package io.quillloom.application.preprocess.service;

import io.quillloom.application.preprocess.assembler.PreprocessDossierAssembler;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.support.BookAnalysisTestSupport;
import io.quillloom.support.PreprocessTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PreprocessApplicationServiceTest {

    @Test
    void shouldBuildSeparatedPreprocessBundles() {
        PreprocessApplicationService service = new PreprocessApplicationService(
                BookAnalysisTestSupport.createBookAnalyzer(),
                PreprocessTestSupport.createChunkAnnotator(),
                PreprocessTestSupport.createKnowledgeEnricher(),
                new PreprocessDossierAssembler()
        );

        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-1",
                "sample-novel",
                "Alice met Bob in Paris.\n\nThey walked along the river and talked about the old house.",
                "en",
                "zh"
        );

        var dossier = service.preprocess(command);

        assertNotNull(dossier.globalAnalysis());
        assertNotNull(dossier.chunkAnnotations());
        assertNotNull(dossier.knowledgeEnrichment());
        assertFalse(dossier.chunkAnnotations().chunks().isEmpty());
    }
}
