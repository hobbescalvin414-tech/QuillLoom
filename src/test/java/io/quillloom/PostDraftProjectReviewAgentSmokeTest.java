package io.quillloom;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quillloom.application.postdraft.assembler.PostDraftContinuationContextAssembler;
import io.quillloom.application.postdraft.port.out.PostDraftReviewPackageRepository;
import io.quillloom.application.postdraft.review.command.StartProjectPostDraftReviewAgentCommand;
import io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult;
import io.quillloom.application.postdraft.review.service.PostDraftReviewAgentService;
import io.quillloom.application.postdraft.review.service.ConsoleReviewRuntimeVisualizer;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProblemClassifier;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProcessSummaryAssembler;
import io.quillloom.application.postdraft.review.service.PostDraftReviewSessionFactory;
import io.quillloom.application.preprocess.port.out.ProjectKnowledgeBaseRepository;
import io.quillloom.infrastructure.postdraft.PostgresPostDraftReviewPackageRepository;
import io.quillloom.infrastructure.postdraft.review.InMemoryHumanInTheLoopGateway;
import io.quillloom.infrastructure.postdraft.review.PassThroughPostDraftReviewAgentWriter;
import io.quillloom.infrastructure.postdraft.review.PostDraftReviewAgentRuntimeConfiguration;
import io.quillloom.infrastructure.postdraft.review.RepositoryBackedPostDraftReviewAgentReader;
import io.quillloom.infrastructure.postdraft.review.RepositoryBackedPostDraftReviewAgentTermWriter;
import io.quillloom.infrastructure.postdraft.review.ReviewAgentLlmProperties;
import io.quillloom.infrastructure.preprocess.KnowledgeBaseStorageProperties;
import io.quillloom.infrastructure.preprocess.PostgresKnowledgeBaseSchemaInitializer;
import io.quillloom.infrastructure.preprocess.PostgresProjectKnowledgeBaseRepository;
import io.quillloom.support.PostDraftReviewSmokeSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostDraftProjectReviewAgentSmokeTest {

    private static final String ENABLED_PROPERTY = "quillloom.test.post-draft-project-review-smoke.enabled";
    private static final String PROJECT_ID_PROPERTY = "quillloom.test.post-draft-project-review-smoke.project-id";
    private static final String NOTE_PROPERTY = "quillloom.test.post-draft-project-review-smoke.note";
    private static final String DB_URL_PROPERTY = "quillloom.test.postgres.url";
    private static final String DB_USERNAME_PROPERTY = "quillloom.test.postgres.username";
    private static final String DB_PASSWORD_PROPERTY = "quillloom.test.postgres.password";
    private static final String REVIEW_LLM_ENABLED_PROPERTY = "quillloom.postdraft.review.llm.enabled";
    private static final String REVIEW_LLM_BASE_URL_PROPERTY = "quillloom.postdraft.review.llm.base-url";
    private static final String REVIEW_LLM_API_KEY_PROPERTY = "quillloom.postdraft.review.llm.api-key";
    private static final String REVIEW_LLM_MODEL_NAME_PROPERTY = "quillloom.postdraft.review.llm.model-name";
    private static final String DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/robot";
    private static final String DEFAULT_DB_USERNAME = "postgres";
    private static final String DEFAULT_DB_PASSWORD = "postgres";

    @Test
    void shouldRunProjectSmokeWithProjectIdOnly() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLED_PROPERTY),
                "Skip project-level post-draft review smoke test unless explicitly enabled.");
        Assumptions.assumeTrue(Boolean.getBoolean(REVIEW_LLM_ENABLED_PROPERTY),
                "Skip autonomy smoke unless review llm runtime is explicitly enabled.");

        JdbcTemplate jdbcTemplate = jdbcTemplate();
        new PostgresKnowledgeBaseSchemaInitializer(jdbcTemplate, storageProperties()).initialize();

        String projectId = requireProjectId(jdbcTemplate);
        String operatorNote = System.getProperty(NOTE_PROPERTY, "").trim();

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ProjectKnowledgeBaseRepository knowledgeBaseRepository = new PostgresProjectKnowledgeBaseRepository(jdbcTemplate);
        PostDraftReviewPackageRepository reviewPackageRepository = new PostgresPostDraftReviewPackageRepository(
                jdbcTemplate,
                objectMapper
        );
        RepositoryBackedPostDraftReviewAgentReader reader = new RepositoryBackedPostDraftReviewAgentReader(
                reviewPackageRepository,
                knowledgeBaseRepository,
                new PostDraftContinuationContextAssembler(),
                new io.quillloom.application.translation.port.out.KnowledgeRetrievalService() {
                    @Override
                    public io.quillloom.application.translation.model.KnowledgeRetrievalResult retrieve(
                            String projectId,
                            io.quillloom.domain.knowledge.ProjectKnowledgeBase preferredKnowledgeBase,
                            io.quillloom.application.translation.model.KnowledgeRetrievalQuery query) {
                        return new io.quillloom.application.translation.model.KnowledgeRetrievalResult(List.of());
                    }
                }
        );
        ReviewAgentLlmProperties reviewAgentLlmProperties = reviewAgentLlmProperties();
        PostDraftReviewAgentService service = new PostDraftReviewAgentService(
                reader,
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new PostDraftReviewProcessSummaryAssembler(),
                new InMemoryHumanInTheLoopGateway(),
                new PassThroughPostDraftReviewAgentWriter(),
                new RepositoryBackedPostDraftReviewAgentTermWriter(
                        reviewPackageRepository,
                        new io.quillloom.application.postdraft.assembler.PostDraftReviewPackageAssembler(),
                        reader
                ),
                new PostDraftReviewAgentRuntimeConfiguration().reviewAgentStructuredGenerationPort(
                        reviewAgentLlmProperties,
                        objectMapper
                ),
                io.quillloom.application.postdraft.review.port.out.ReviewSessionStore.noop(),
                new ConsoleReviewRuntimeVisualizer()
        );

        PostDraftReviewAgentResult result = service.reviewProject(
                new StartProjectPostDraftReviewAgentCommand(projectId, operatorNote)
        );

        PostDraftReviewSmokeSupport smokeSupport = new PostDraftReviewSmokeSupport(objectMapper);
        Path outputDir = smokeSupport.prepareProjectOutputDir(projectId);
        smokeSupport.writeProjectReport(outputDir, projectId, operatorNote, result);

        assertNotNull(result.processSummary());
        assertTrue(
                result.completedChunkResults().size() > 0
                        || result.humanReviewRequest().isPresent(),
                "Autonomy smoke should either complete chunks or stop on an explicit human boundary."
        );
        assertTrue(
                result.completedChunkResults().size() > 0
                        || result.humanReviewRequest()
                        .map(request -> !request.requestNote().contains("retranslationFailed"))
                        .orElse(false),
                "Autonomy smoke should not stop because default retranslation provider is unwired."
        );
        assertTrue(Files.isDirectory(outputDir), "Smoke output directory should exist.");
        assertTrue(Files.isRegularFile(outputDir.resolve("result-summary.txt")), "Summary report should exist.");
        assertTrue(Files.isRegularFile(outputDir.resolve("result-debug.txt")), "Debug report should exist.");
        assertTrue(Files.isRegularFile(outputDir.resolve("result.json")), "JSON report should exist.");

        System.out.println("[PostDraftProjectReviewAgentSmokeTest] projectId=" + projectId);
        System.out.println("[PostDraftProjectReviewAgentSmokeTest] completedChunkCount=" + result.completedChunkResults().size());
        System.out.println("[PostDraftProjectReviewAgentSmokeTest] outputDir=" + outputDir.toAbsolutePath());
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getProperty(DB_URL_PROPERTY, DEFAULT_DB_URL));
        dataSource.setUsername(System.getProperty(DB_USERNAME_PROPERTY, DEFAULT_DB_USERNAME));
        dataSource.setPassword(System.getProperty(DB_PASSWORD_PROPERTY, DEFAULT_DB_PASSWORD));
        return new JdbcTemplate(dataSource);
    }

    private KnowledgeBaseStorageProperties storageProperties() {
        KnowledgeBaseStorageProperties properties = new KnowledgeBaseStorageProperties();
        properties.setStorage("postgres");
        properties.getPostgres().setUrl(System.getProperty(DB_URL_PROPERTY, DEFAULT_DB_URL));
        properties.getPostgres().setUsername(System.getProperty(DB_USERNAME_PROPERTY, DEFAULT_DB_USERNAME));
        properties.getPostgres().setPassword(System.getProperty(DB_PASSWORD_PROPERTY, DEFAULT_DB_PASSWORD));
        properties.getPostgres().setInitializeSchema(true);
        return properties;
    }

    private String requireProjectId(JdbcTemplate jdbcTemplate) {
        String value = System.getProperty(PROJECT_ID_PROPERTY, "").trim();
        if (!value.isEmpty()) {
            return value;
        }
        List<String> projectIds = jdbcTemplate.query(
                "select project_id from ql_post_draft_review_package order by created_at desc limit 10",
                (rs, rowNum) -> rs.getString(1)
        );
        throw new IllegalStateException(
                "Missing required system property: " + PROJECT_ID_PROPERTY
                        + ". Available recent projectIds=" + projectIds
        );
    }

    private ReviewAgentLlmProperties reviewAgentLlmProperties() {
        ReviewAgentLlmProperties properties = new ReviewAgentLlmProperties();
        properties.setEnabled(Boolean.getBoolean(REVIEW_LLM_ENABLED_PROPERTY));
        properties.setBaseUrl(System.getProperty(REVIEW_LLM_BASE_URL_PROPERTY, "").trim());
        properties.setApiKey(System.getProperty(REVIEW_LLM_API_KEY_PROPERTY, "").trim());
        properties.setModelName(System.getProperty(REVIEW_LLM_MODEL_NAME_PROPERTY, "").trim());
        return properties;
    }
}
