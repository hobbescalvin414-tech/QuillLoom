package io.quillloom.application.preprocess.port.out;

import io.quillloom.domain.knowledge.ProjectKnowledgeBase;

import java.util.Optional;

/**
 * 项目级知识库存取端口。
 */
public interface ProjectKnowledgeBaseRepository {

    Optional<ProjectKnowledgeBase> load(String projectId);

    void save(ProjectKnowledgeBase knowledgeBase);
}