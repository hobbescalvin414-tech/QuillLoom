package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.preprocess.ChunkAnnotation;

import java.util.List;

public interface KnowledgeNeedPlanner {

    default List<KnowledgeNeed> plan(ChunkAnnotation chunk) {
        return plan(chunk, "");
    }

    List<KnowledgeNeed> plan(ChunkAnnotation chunk, String targetLanguage);
}
