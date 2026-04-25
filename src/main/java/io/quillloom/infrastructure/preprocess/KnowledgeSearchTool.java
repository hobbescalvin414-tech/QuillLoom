package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.preprocess.ChunkAnnotation;

import java.util.List;

/**
 * C0 调用的搜索工具端口。
 */
public interface KnowledgeSearchTool {

    List<KnowledgeSearchOutcome> search(ChunkAnnotation chunk,
                                        List<KnowledgeNeed> needs);

    default List<KnowledgeSearchOutcome> search(ChunkAnnotation chunk) {
        return search(chunk, List.of());
    }
}
