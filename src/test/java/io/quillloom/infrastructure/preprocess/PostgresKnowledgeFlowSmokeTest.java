package io.quillloom.infrastructure.preprocess;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import io.quillloom.application.translation.model.KnowledgeRetrievalQuery;
import io.quillloom.application.translation.model.KnowledgeRetrievalUseCase;
import io.quillloom.application.translation.service.RuleBasedKnowledgeRetrievalService;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import io.quillloom.domain.preprocess.GlobalAnalysisBundle;
import io.quillloom.domain.preprocess.KnowledgeEnrichmentBundle;
import io.quillloom.support.BookAnalysisTestSupport;
import io.quillloom.support.PreprocessTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresKnowledgeFlowSmokeTest {

    private static final String ENABLED_PROPERTY = "quillloom.test.postgres.enabled";
    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/robot";
    private static final String DEFAULT_USERNAME = "postgres";
    private static final String DEFAULT_PASSWORD = "postgres";
    private static final String PROJECT_ID = "smoke-project";

    @AfterEach
    void cleanup() {
        if (!isEnabled()) {
            return;
        }
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update("delete from ql_project_knowledge_card_keyword where project_id = ?", PROJECT_ID);
        jdbcTemplate.update("delete from ql_project_knowledge_card_anchor where project_id = ?", PROJECT_ID);
        jdbcTemplate.update("delete from ql_project_knowledge_card_source where project_id = ?", PROJECT_ID);
        jdbcTemplate.update("delete from ql_project_knowledge_card_chunk where project_id = ?", PROJECT_ID);
        jdbcTemplate.update("delete from ql_project_knowledge_card_index where project_id = ?", PROJECT_ID);
        jdbcTemplate.update("delete from ql_project_knowledge_card where project_id = ?", PROJECT_ID);
        jdbcTemplate.update("delete from ql_candidate_term_translation where project_id = ?", PROJECT_ID);
        jdbcTemplate.update("delete from ql_candidate_term where project_id = ?", PROJECT_ID);
    }

    @Test
    void shouldRunKnowledgeEnrichmentPersistenceAndRetrievalAgainstPostgres() {
        Assumptions.assumeTrue(isEnabled(), "未启用 PostgreSQL 烟雾测试。设置 -Dquillloom.test.postgres.enabled=true 后执行。");

        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeBaseStorageProperties storageProperties = storageProperties();
        new PostgresKnowledgeBaseSchemaInitializer(jdbcTemplate, storageProperties).initialize();

        PostgresProjectKnowledgeBaseRepository repository = new PostgresProjectKnowledgeBaseRepository(jdbcTemplate);
        PostgresKnowledgeIndexRepository indexRepository = new PostgresKnowledgeIndexRepository(jdbcTemplate);
        ToolDrivenKnowledgeEnricher enricher = new ToolDrivenKnowledgeEnricher(
                (chunk, needs) -> List.of(
                        new KnowledgeSearchOutcome(
                                needs.get(0),
                                1,
                                1,
                                new OrganizedKnowledgeEvidence(
                                        KnowledgeCardType.CULTURAL_BACKGROUND,
                                        "Local customs",
                                        "Local customs affect forms of address.",
                                        List.of("Alice"),
                                        List.of("https://example.com/customs"),
                                        List.of("chunk:test#backgroundQuestion:1"),
                                        "test",
                                        "HIGH"
                                ),
                                "",
                                ""
                        )
                ),
                repository,
                (chunk, targetLanguage) -> List.of(
                        new KnowledgeNeed(
                                KnowledgeCardType.CULTURAL_BACKGROUND,
                                "local customs forms of address",
                                List.of("Alice"),
                                List.of("customs", "address"),
                                List.of("chunk:test#backgroundQuestion:1"),
                                "需要本地习俗背景",
                                1
                        )
                ),
                new KnowledgeSearchGate(new KnowledgeSearchGateProperties()),
                new KnowledgeCardDraftNormalizer(),
                new KnowledgeCardMergeService(new KnowledgeCardIdentityResolver()),
                new KnowledgeCardRetrievalTextBuilder(),
                new NoOpKnowledgeEmbeddingService(),
                indexRepository
        );

        PreprocessBookCommand command = new PreprocessBookCommand(
                PROJECT_ID,
                "smoke-sample",
                "Alice met Bob in Paris. They discussed the old house and local customs.",
                "en",
                "zh"
        );
        GlobalAnalysisBundle globalAnalysis = BookAnalysisTestSupport.createBookAnalyzer().analyze(command);
        var chunkBundle = PreprocessTestSupport.createChunkAnnotator().annotate(command, globalAnalysis);

        KnowledgeEnrichmentBundle enrichmentBundle = enricher.enrich(command, globalAnalysis, chunkBundle);

        var loaded = repository.load(PROJECT_ID).orElseThrow();
        assertFalse(loaded.cards().isEmpty());
        assertFalse(loaded.candidateTerms().isEmpty());

        Integer indexCount = jdbcTemplate.queryForObject(
                "select count(*) from ql_project_knowledge_card_index where project_id = ?",
                Integer.class,
                PROJECT_ID
        );
        assertTrue(indexCount != null && indexCount > 0);

        RuleBasedKnowledgeRetrievalService retrievalService = new RuleBasedKnowledgeRetrievalService(
                repository,
                new NoOpKnowledgeEmbeddingService(),
                indexRepository,
                new DefaultKnowledgeRetrievalPolicyResolver()
        );
        var result = retrievalService.retrieve(PROJECT_ID, loaded, new KnowledgeRetrievalQuery(
                KnowledgeRetrievalUseCase.ASSEMBLY,
                chunkBundle.chunks().get(0).chunk().chunkId(),
                List.of("Alice", "Paris", "old house"),
                List.of("Alice", "Bob"),
                List.of(KnowledgeCardType.CHARACTER_PROFILE, KnowledgeCardType.SETTING_ENTRY),
                List.of(),
                5,
                2
        ));

        assertFalse(result.cards().isEmpty());
        assertFalse(enrichmentBundle.projectKnowledgeBase().cards().isEmpty());
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

    private KnowledgeBaseStorageProperties storageProperties() {
        KnowledgeBaseStorageProperties properties = new KnowledgeBaseStorageProperties();
        properties.setStorage("postgres");
        properties.getPostgres().setUrl(System.getProperty("quillloom.test.postgres.url", DEFAULT_URL));
        properties.getPostgres().setUsername(System.getProperty("quillloom.test.postgres.username", DEFAULT_USERNAME));
        properties.getPostgres().setPassword(System.getProperty("quillloom.test.postgres.password", DEFAULT_PASSWORD));
        properties.getPostgres().setInitializeSchema(true);
        return properties;
    }
}
