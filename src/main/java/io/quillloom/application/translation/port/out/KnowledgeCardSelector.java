package io.quillloom.application.translation.port.out;

import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import io.quillloom.domain.preprocess.ChunkAnnotation;
import io.quillloom.domain.translation.TranslationRuntimeOptions;

import java.util.List;

/**
 * 当前 chunk 首批知识卡筛选端口。
 */
public interface KnowledgeCardSelector {

    List<KnowledgeCard> selectForChunk(ChunkAnnotation chunk,
                                       ProjectKnowledgeBase knowledgeBase,
                                       TranslationRuntimeOptions runtimeOptions);
}