package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.preprocess.port.out.ProjectKnowledgeBaseRepository;
import io.quillloom.domain.knowledge.CandidateTerm;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 项目知识库 PostgreSQL 实现。
 * 当前只负责稳定卡片与候选术语持久化，不引入向量索引细节。
 */
@Component
@ConditionalOnProperty(prefix = "quillloom.preprocess.knowledge-base", name = "storage", havingValue = "postgres")
public class PostgresProjectKnowledgeBaseRepository implements ProjectKnowledgeBaseRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresProjectKnowledgeBaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ProjectKnowledgeBase> load(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }

        List<KnowledgeCard> cards = loadCards(projectId);
        List<CandidateTerm> candidateTerms = loadCandidateTerms(projectId);
        if (cards.isEmpty() && candidateTerms.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ProjectKnowledgeBase(projectId, cards, candidateTerms));
    }

    @Override
    @Transactional(transactionManager = "knowledgeBaseTransactionManager")
    public void save(ProjectKnowledgeBase knowledgeBase) {
        if (knowledgeBase == null || knowledgeBase.projectId() == null || knowledgeBase.projectId().isBlank()) {
            throw new IllegalArgumentException("knowledgeBase must have a projectId.");
        }

        String projectId = knowledgeBase.projectId();
        deleteProjectKnowledgeBase(projectId);
        insertCards(projectId, knowledgeBase.cards());
        insertCandidateTerms(projectId, knowledgeBase.candidateTerms());
    }

    private List<KnowledgeCard> loadCards(String projectId) {
        List<CardRow> cardRows = jdbcTemplate.query(
                "select card_id, card_type, title, content, scope from ql_project_knowledge_card where project_id = ? order by card_id",
                (rs, rowNum) -> new CardRow(
                        rs.getString("card_id"),
                        KnowledgeCardType.valueOf(rs.getString("card_type")),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getString("scope")
                ),
                projectId
        );
        if (cardRows.isEmpty()) {
            return List.of();
        }

        Map<String, List<String>> keywordMap = loadOrderedValues(projectId,
                "select card_id, order_index, keyword as value from ql_project_knowledge_card_keyword where project_id = ? order by card_id, order_index");
        Map<String, List<String>> anchorMap = loadOrderedValues(projectId,
                "select card_id, order_index, anchor_name as value from ql_project_knowledge_card_anchor where project_id = ? order by card_id, order_index");
        Map<String, List<String>> sourceMap = loadOrderedValues(projectId,
                "select card_id, order_index, source_ref as value from ql_project_knowledge_card_source where project_id = ? order by card_id, order_index");
        Map<String, List<String>> chunkMap = loadOrderedValues(projectId,
                "select card_id, order_index, chunk_id as value from ql_project_knowledge_card_chunk where project_id = ? order by card_id, order_index");

        List<KnowledgeCard> cards = new ArrayList<>();
        for (CardRow row : cardRows) {
            cards.add(new KnowledgeCard(
                    row.cardId(),
                    row.cardType(),
                    row.title(),
                    row.content(),
                    keywordMap.getOrDefault(row.cardId(), List.of()),
                    anchorMap.getOrDefault(row.cardId(), List.of()),
                    sourceMap.getOrDefault(row.cardId(), List.of()),
                    row.scope(),
                    chunkMap.getOrDefault(row.cardId(), List.of())
            ));
        }
        return List.copyOf(cards);
    }

    private List<CandidateTerm> loadCandidateTerms(String projectId) {
        List<CandidateTermRow> rows = jdbcTemplate.query(
                "select source_term, category, rationale from ql_candidate_term where project_id = ? order by source_term",
                (rs, rowNum) -> new CandidateTermRow(
                        rs.getString("source_term"),
                        rs.getString("category"),
                        rs.getString("rationale")
                ),
                projectId
        );
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> translationMap = loadOrderedValues(projectId,
                "select source_term as card_id, order_index, candidate_translation as value from ql_candidate_term_translation where project_id = ? order by source_term, order_index");

        List<CandidateTerm> terms = new ArrayList<>();
        for (CandidateTermRow row : rows) {
            terms.add(new CandidateTerm(
                    row.sourceTerm(),
                    translationMap.getOrDefault(row.sourceTerm(), List.of()),
                    row.category(),
                    row.rationale()
            ));
        }
        return List.copyOf(terms);
    }

    private Map<String, List<String>> loadOrderedValues(String projectId, String sql) {
        List<OrderedValueRow> rows = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new OrderedValueRow(
                        rs.getString("card_id"),
                        rs.getInt("order_index"),
                        rs.getString("value")
                ),
                projectId
        );
        Map<String, List<OrderedValueRow>> grouped = new LinkedHashMap<>();
        for (OrderedValueRow row : rows) {
            grouped.computeIfAbsent(row.ownerId(), ignored -> new ArrayList<>()).add(row);
        }

        Map<String, List<String>> result = new LinkedHashMap<>();
        grouped.forEach((ownerId, groupedRows) -> {
            groupedRows.sort(Comparator.comparingInt(OrderedValueRow::orderIndex));
            result.put(ownerId, groupedRows.stream().map(OrderedValueRow::value).toList());
        });
        return result;
    }

    private void deleteProjectKnowledgeBase(String projectId) {
        jdbcTemplate.update("delete from ql_project_knowledge_card_keyword where project_id = ?", projectId);
        jdbcTemplate.update("delete from ql_project_knowledge_card_anchor where project_id = ?", projectId);
        jdbcTemplate.update("delete from ql_project_knowledge_card_source where project_id = ?", projectId);
        jdbcTemplate.update("delete from ql_project_knowledge_card_chunk where project_id = ?", projectId);
        jdbcTemplate.update("delete from ql_project_knowledge_card where project_id = ?", projectId);
        jdbcTemplate.update("delete from ql_candidate_term_translation where project_id = ?", projectId);
        jdbcTemplate.update("delete from ql_candidate_term where project_id = ?", projectId);
    }

    private void insertCards(String projectId, List<KnowledgeCard> cards) {
        for (KnowledgeCard card : cards) {
            jdbcTemplate.update(
                    "insert into ql_project_knowledge_card(project_id, card_id, card_type, title, content, scope) values (?, ?, ?, ?, ?, ?)",
                    projectId,
                    card.cardId(),
                    card.cardType().name(),
                    card.title(),
                    card.content(),
                    card.scope()
            );
            insertOrderedValues(projectId, card.cardId(), card.keywords(),
                    "insert into ql_project_knowledge_card_keyword(project_id, card_id, order_index, keyword) values (?, ?, ?, ?)");
            insertOrderedValues(projectId, card.cardId(), card.anchorNames(),
                    "insert into ql_project_knowledge_card_anchor(project_id, card_id, order_index, anchor_name) values (?, ?, ?, ?)");
            insertOrderedValues(projectId, card.cardId(), card.sourceRefs(),
                    "insert into ql_project_knowledge_card_source(project_id, card_id, order_index, source_ref) values (?, ?, ?, ?)");
            insertOrderedValues(projectId, card.cardId(), card.applicableChunkIds(),
                    "insert into ql_project_knowledge_card_chunk(project_id, card_id, order_index, chunk_id) values (?, ?, ?, ?)");
        }
    }

    private void insertCandidateTerms(String projectId, List<CandidateTerm> candidateTerms) {
        for (CandidateTerm term : candidateTerms) {
            jdbcTemplate.update(
                    "insert into ql_candidate_term(project_id, source_term, category, rationale) values (?, ?, ?, ?)",
                    projectId,
                    term.sourceTerm(),
                    term.category(),
                    term.rationale()
            );
            insertOrderedValues(projectId, term.sourceTerm(), term.candidateTranslations(),
                    "insert into ql_candidate_term_translation(project_id, source_term, order_index, candidate_translation) values (?, ?, ?, ?)");
        }
    }

    private void insertOrderedValues(String projectId,
                                     String ownerId,
                                     List<String> values,
                                     String sql) {
        if (values == null) {
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            jdbcTemplate.update(sql, projectId, ownerId, i, values.get(i));
        }
    }

    private record CardRow(String cardId,
                           KnowledgeCardType cardType,
                           String title,
                           String content,
                           String scope) {
    }

    private record CandidateTermRow(String sourceTerm,
                                    String category,
                                    String rationale) {
    }

    private record OrderedValueRow(String ownerId,
                                   int orderIndex,
                                   String value) {
    }
}
