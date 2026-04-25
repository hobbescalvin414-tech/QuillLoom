package io.quillloom.infrastructure.preprocess;

import io.quillloom.domain.knowledge.CandidateTerm;
import io.quillloom.domain.knowledge.KnowledgeCard;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.knowledge.ProjectKnowledgeBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresProjectKnowledgeBaseRepositoryTest {

    private static final String ENABLED_PROPERTY = "quillloom.test.postgres.enabled";
    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/robot";
    private static final String DEFAULT_USERNAME = "postgres";
    private static final String DEFAULT_PASSWORD = "postgres";

    @AfterEach
    void cleanup() {
        if (!isEnabled()) {
            return;
        }
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update("delete from ql_project_knowledge_card_keyword where project_id = ?", "test-project");
        jdbcTemplate.update("delete from ql_project_knowledge_card_anchor where project_id = ?", "test-project");
        jdbcTemplate.update("delete from ql_project_knowledge_card_source where project_id = ?", "test-project");
        jdbcTemplate.update("delete from ql_project_knowledge_card_chunk where project_id = ?", "test-project");
        jdbcTemplate.update("delete from ql_project_knowledge_card where project_id = ?", "test-project");
        jdbcTemplate.update("delete from ql_candidate_term_translation where project_id = ?", "test-project");
        jdbcTemplate.update("delete from ql_candidate_term where project_id = ?", "test-project");
    }

    @Test
    void shouldRoundTripKnowledgeBaseInPostgres() {
        Assumptions.assumeTrue(isEnabled(), "未启用 PostgreSQL 集成测试。设置 -Dquillloom.test.postgres.enabled=true 后执行。 ");

        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeBaseStorageProperties properties = properties();
        new PostgresKnowledgeBaseSchemaInitializer(jdbcTemplate, properties).initialize();
        PostgresProjectKnowledgeBaseRepository repository = new PostgresProjectKnowledgeBaseRepository(jdbcTemplate);

        ProjectKnowledgeBase expected = new ProjectKnowledgeBase(
                "test-project",
                List.of(new KnowledgeCard(
                        "card-1",
                        KnowledgeCardType.CHARACTER_PROFILE,
                        "Alice",
                        "Alice 是当前段落中的关键人物。",
                        List.of("Alice", "heroine"),
                        List.of("Alice"),
                        List.of("source:test"),
                        "PROJECT",
                        List.of("chunk-1")
                )),
                List.of(new CandidateTerm(
                        "old house",
                        List.of("老宅", "旧居"),
                        "entity",
                        "来自测试样本"
                ))
        );

        repository.save(expected);
        ProjectKnowledgeBase actual = repository.load("test-project").orElseThrow();

        assertEquals(expected.projectId(), actual.projectId());
        assertEquals(1, actual.cards().size());
        assertEquals(1, actual.candidateTerms().size());
        assertEquals("Alice", actual.cards().get(0).title());
        assertEquals(List.of("Alice", "heroine"), actual.cards().get(0).keywords());
        assertEquals(List.of("chunk-1"), actual.cards().get(0).applicableChunkIds());
        assertEquals("old house", actual.candidateTerms().get(0).sourceTerm());
        assertEquals(List.of("老宅", "旧居"), actual.candidateTerms().get(0).candidateTranslations());
    }

    @Test
    void shouldReturnEmptyWhenProjectDoesNotExist() {
        Assumptions.assumeTrue(isEnabled(), "未启用 PostgreSQL 集成测试。设置 -Dquillloom.test.postgres.enabled=true 后执行。 ");

        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeBaseStorageProperties properties = properties();
        new PostgresKnowledgeBaseSchemaInitializer(jdbcTemplate, properties).initialize();
        PostgresProjectKnowledgeBaseRepository repository = new PostgresProjectKnowledgeBaseRepository(jdbcTemplate);

        assertTrue(repository.load("missing-project").isEmpty());
    }

    private boolean isEnabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getProperty("quillloom.test.postgres.url", DEFAULT_URL));
        dataSource.setUsername(System.getProperty("quillloom.test.postgres.username", DEFAULT_USERNAME));
        dataSource.setPassword(System.getProperty("quillloom.test.postgres.password", DEFAULT_PASSWORD));
        return new JdbcTemplate(dataSource);
    }

    private KnowledgeBaseStorageProperties properties() {
        KnowledgeBaseStorageProperties properties = new KnowledgeBaseStorageProperties();
        properties.setStorage("postgres");
        properties.getPostgres().setUrl(System.getProperty("quillloom.test.postgres.url", DEFAULT_URL));
        properties.getPostgres().setUsername(System.getProperty("quillloom.test.postgres.username", DEFAULT_USERNAME));
        properties.getPostgres().setPassword(System.getProperty("quillloom.test.postgres.password", DEFAULT_PASSWORD));
        properties.getPostgres().setInitializeSchema(true);
        return properties;
    }
}
