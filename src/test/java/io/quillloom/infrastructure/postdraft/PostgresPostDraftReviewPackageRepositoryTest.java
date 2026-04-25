package io.quillloom.infrastructure.postdraft;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;
import io.quillloom.domain.postdraft.PostDraftBlockIndex;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import io.quillloom.domain.postdraft.PostDraftTermState;
import io.quillloom.domain.translation.ChunkTransitionNote;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import io.quillloom.infrastructure.preprocess.KnowledgeBaseStorageProperties;
import io.quillloom.infrastructure.preprocess.PostgresKnowledgeBaseSchemaInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresPostDraftReviewPackageRepositoryTest {

    private static final String ENABLED_PROPERTY = "quillloom.test.postgres.enabled";
    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/robot";
    private static final String DEFAULT_USERNAME = "postgres";
    private static final String DEFAULT_PASSWORD = "postgres";

    @AfterEach
    void cleanup() {
        if (!isEnabled()) {
            return;
        }
        jdbcTemplate().update("delete from ql_post_draft_review_package where project_id = ?", "test-post-draft-project");
    }

    @Test
    void shouldRoundTripPostDraftReviewPackageInPostgres() {
        Assumptions.assumeTrue(isEnabled(), "未启用 PostgreSQL 集成测试。设置 -Dquillloom.test.postgres.enabled=true 后执行。");

        JdbcTemplate jdbcTemplate = jdbcTemplate();
        new PostgresKnowledgeBaseSchemaInitializer(jdbcTemplate, properties()).initialize();
        PostgresPostDraftReviewPackageRepository repository = new PostgresPostDraftReviewPackageRepository(
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules()
        );

        PostDraftReviewPackage expected = new PostDraftReviewPackage(
                "test-post-draft-project",
                "v1",
                "fr",
                "zh",
                "digest-1",
                Instant.parse("2026-04-14T10:15:30Z"),
                List.of(new PostDraftChunkRecord(
                        "chunk-1",
                        1,
                        "block-1",
                        "source text",
                        "translated text",
                        "commentary",
                        List.of(),
                        Map.of("Louki", "露姬"),
                        List.of(new TranslationCandidateUpdate("Black Maria", "黑色马车", "候选", true)),
                        new ChunkTransitionNote("before", "after", false)
                )),
                List.of(new PostDraftBlockIndex("block-1", "夜行", List.of("chunk-1"))),
                new PostDraftTermState(
                        Map.of("Louki", "露姬"),
                        List.of(new TranslationCandidateUpdate("Black Maria", "黑色马车", "候选", true))
                ),
                DraftStageGlobalGlossary.empty(),
                GlobalAliasConsistencyTable.empty(),
                "merged text"
        );

        repository.save(expected);

        PostDraftReviewPackage actual = repository.load("test-post-draft-project").orElseThrow();
        assertEquals(expected.projectId(), actual.projectId());
        assertEquals(expected.packageVersion(), actual.packageVersion());
        assertEquals(1, actual.chunks().size());
        assertEquals("source text", actual.chunks().get(0).sourceText());
        assertEquals("merged text", actual.mergedDraftText());
        assertEquals("露姬", actual.termState().effectiveConfirmedTerms().get("Louki"));
        assertEquals(1, actual.termState().effectiveCandidateTerms().size());
    }

    @Test
    void shouldReturnEmptyWhenPackageDoesNotExist() {
        Assumptions.assumeTrue(isEnabled(), "未启用 PostgreSQL 集成测试。设置 -Dquillloom.test.postgres.enabled=true 后执行。");

        JdbcTemplate jdbcTemplate = jdbcTemplate();
        new PostgresKnowledgeBaseSchemaInitializer(jdbcTemplate, properties()).initialize();
        PostgresPostDraftReviewPackageRepository repository = new PostgresPostDraftReviewPackageRepository(
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules()
        );

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
