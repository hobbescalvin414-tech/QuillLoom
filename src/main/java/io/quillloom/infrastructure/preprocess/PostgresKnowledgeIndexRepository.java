package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.preprocess.model.KnowledgeEmbedding;
import io.quillloom.application.preprocess.model.KnowledgeIndexDocument;
import io.quillloom.application.preprocess.model.KnowledgeIndexMatch;
import io.quillloom.application.preprocess.port.out.KnowledgeIndexRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * PostgreSQL 知识索引存储实现。
 * 当前持久化 retrievalText，并在索引层提供 pgvector 相似召回能力。
 */
@Component
@ConditionalOnProperty(prefix = "quillloom.preprocess.knowledge-base", name = "storage", havingValue = "postgres")
public class PostgresKnowledgeIndexRepository implements KnowledgeIndexRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresKnowledgeIndexRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(transactionManager = "knowledgeBaseTransactionManager")
    public void replaceProjectIndex(String projectId,
                                    List<KnowledgeIndexDocument> documents) {
        jdbcTemplate.update("delete from ql_project_knowledge_card_index where project_id = ?", projectId);
        if (documents == null) {
            return;
        }
        for (KnowledgeIndexDocument document : documents) {
            if (document == null) {
                continue;
            }
            KnowledgeEmbedding embedding = document.embedding();
            jdbcTemplate.update(
                    "insert into ql_project_knowledge_card_index(project_id, card_id, retrieval_text, embedding, embedding_model, embedding_version) values (?, ?, ?, cast(? as vector), ?, ?)",
                    document.projectId(),
                    document.cardId(),
                    document.retrievalText(),
                    toVectorLiteral(embedding),
                    embedding == null ? "" : embedding.model(),
                    embedding == null ? "" : embedding.version()
            );
        }
    }

    @Override
    public List<KnowledgeIndexMatch> searchSimilar(String projectId,
                                                   KnowledgeEmbedding embedding,
                                                   int limit) {
        if (projectId == null || projectId.isBlank() || embedding == null || embedding.isEmpty() || limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                select card_id,
                       1 - (embedding <=> cast(? as vector)) as similarity_score
                from ql_project_knowledge_card_index
                where project_id = ?
                  and embedding is not null
                order by embedding <=> cast(? as vector)
                limit ?
                """,
                (rs, rowNum) -> new KnowledgeIndexMatch(
                        rs.getString("card_id"),
                        rs.getDouble("similarity_score")
                ),
                toVectorLiteral(embedding),
                projectId,
                toVectorLiteral(embedding),
                limit
        );
    }

    private String toVectorLiteral(KnowledgeEmbedding embedding) {
        if (embedding == null || embedding.isEmpty()) {
            return null;
        }
        return "[" + embedding.vector().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")) + "]";
    }
}
