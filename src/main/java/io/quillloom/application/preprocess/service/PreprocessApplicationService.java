package io.quillloom.application.preprocess.service;

import io.quillloom.application.preprocess.assembler.PreprocessDossierAssembler;
import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.preprocess.port.out.BookAnalyzer;
import io.quillloom.application.preprocess.port.out.ChunkAnnotator;
import io.quillloom.application.preprocess.port.out.KnowledgeEnricher;
import io.quillloom.domain.preprocess.ChunkAnnotationBundle;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import io.quillloom.domain.preprocess.KnowledgeEnrichmentBundle;
import io.quillloom.domain.preprocess.PreprocessDossier;
import org.springframework.stereotype.Service;

@Service
public class PreprocessApplicationService {

    //这里结构其实很清晰，预处理阶段三个主要的部分
    private final BookAnalyzer bookAnalyzer;
    private final ChunkAnnotator chunkAnnotator;
    private final KnowledgeEnricher knowledgeEnricher;
    private final PreprocessDossierAssembler dossierAssembler;

    public PreprocessApplicationService(BookAnalyzer bookAnalyzer,
                                        ChunkAnnotator chunkAnnotator,
                                        KnowledgeEnricher knowledgeEnricher,
                                        PreprocessDossierAssembler dossierAssembler) {
        this.bookAnalyzer = bookAnalyzer;
        this.chunkAnnotator = chunkAnnotator;
        this.knowledgeEnricher = knowledgeEnricher;
        this.dossierAssembler = dossierAssembler;
    }

    public PreprocessDossier preprocess(PreprocessBookCommand command) {
        GlobalAnalysisBundle globalAnalysis = bookAnalyzer.analyze(command);
        ChunkAnnotationBundle chunkAnnotations = chunkAnnotator.annotate(command, globalAnalysis);
        KnowledgeEnrichmentBundle knowledgeEnrichment = knowledgeEnricher.enrich(command, globalAnalysis, chunkAnnotations);
        return dossierAssembler.assemble(command, globalAnalysis, chunkAnnotations, knowledgeEnrichment);
    }
}
