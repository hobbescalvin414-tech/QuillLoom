package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.preprocess.ChunkAnnotation;

import java.util.List;

public interface KnowledgeSearchResultOrganizer {

    KnowledgeSearchOrganizationDecision organize(ChunkAnnotation chunk,
                                                 KnowledgeNeed need,
                                                 List<KnowledgeSearchHit> hits);
}
