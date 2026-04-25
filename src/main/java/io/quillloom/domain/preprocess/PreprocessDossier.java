package io.quillloom.domain.preprocess;

import io.quillloom.domain.book.BookProject;

/**
 * Canonical preprocessing result consumed by later assemblers and persisted as a stage artifact.
 */
public record PreprocessDossier(
        BookProject project,
        GlobalAnalysisBundle globalAnalysis,
        ChunkAnnotationBundle chunkAnnotations,
        KnowledgeEnrichmentBundle knowledgeEnrichment
) {
}
