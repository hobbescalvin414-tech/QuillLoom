package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.preprocess.port.out.ProjectKnowledgeBaseRepository;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目知识库内存实现。
 * 后续可替换为外部数据库实现。
 */
@Component
@ConditionalOnProperty(prefix = "quillloom.preprocess.knowledge-base", name = "storage", havingValue = "memory", matchIfMissing = true)
public class InMemoryProjectKnowledgeBaseRepository implements ProjectKnowledgeBaseRepository {

    private final Map<String, ProjectKnowledgeBase> storage = new ConcurrentHashMap<>();

    @Override
    public Optional<ProjectKnowledgeBase> load(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(storage.get(projectId));
    }

    @Override
    public void save(ProjectKnowledgeBase knowledgeBase) {
        if (knowledgeBase == null || knowledgeBase.projectId() == null || knowledgeBase.projectId().isBlank()) {
            throw new IllegalArgumentException("knowledgeBase must have a projectId.");
        }
        storage.put(knowledgeBase.projectId(), knowledgeBase);
    }
}
