package io.quillloom;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quillloom.application.postdraft.assembler.PostDraftContinuationContextAssembler;
import io.quillloom.application.postdraft.port.out.PostDraftReviewPackageRepository;
import io.quillloom.application.postdraft.review.command.StartPostDraftReviewAgentCommand;
import io.quillloom.application.postdraft.review.model.PostDraftReviewAgentResult;
import io.quillloom.application.postdraft.review.model.ReviewFocus;
import io.quillloom.application.postdraft.review.service.PostDraftReviewAgentService;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProblemClassifier;
import io.quillloom.application.postdraft.review.service.PostDraftReviewProcessSummaryAssembler;
import io.quillloom.application.postdraft.review.service.PostDraftReviewSessionFactory;
import io.quillloom.application.postdraft.review.service.PostDraftReviewStrategyResolver;
import io.quillloom.application.preprocess.port.out.ProjectKnowledgeBaseRepository;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.infrastructure.postdraft.PostgresPostDraftReviewPackageRepository;
import io.quillloom.infrastructure.postdraft.review.InMemoryHumanInTheLoopGateway;
import io.quillloom.infrastructure.postdraft.review.PassThroughPostDraftReviewAgentWriter;
import io.quillloom.infrastructure.postdraft.review.RepositoryBackedPostDraftReviewAgentReader;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostDraftReviewAgentSmokeTest {

    private static final String ENABLED_PROPERTY = "quillloom.test.post-draft-review-smoke.enabled";
    private static final String PROJECT_ID_PROPERTY = "quillloom.test.post-draft-review-smoke.project-id";
    private static final String CHUNK_ID_PROPERTY = "quillloom.test.post-draft-review-smoke.chunk-id";
    private static final String NOTE_PROPERTY = "quillloom.test.post-draft-review-smoke.note";
    private static final String DB_URL_PROPERTY = "quillloom.test.postgres.url";
    private static final String DB_USERNAME_PROPERTY = "quillloom.test.postgres.username";
    private static final String DB_PASSWORD_PROPERTY = "quillloom.test.postgres.password";
    private static final String DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/robot";
    private static final String DEFAULT_DB_USERNAME = "postgres";
    private static final String DEFAULT_DB_PASSWORD = "postgres";

    @Test
    void shouldRunPostDraftReviewAgentAgainstExistingProjectData() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLED_PROPERTY),
                "Skip post-draft review smoke test unless explicitly enabled.");

        JdbcTemplate jdbcTemplate = jdbcTemplate();
        new PostgresKnowledgeBaseSchemaInitializer(jdbcTemplate, storageProperties()).initialize();

        String projectId = requireProjectId(jdbcTemplate);
        String chunkId = requireChunkId(jdbcTemplate, projectId);
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
        PostDraftReviewAgentService service = new PostDraftReviewAgentService(
                reader,
                new PostDraftReviewSessionFactory(),
                new PostDraftReviewProblemClassifier(),
                new PostDraftReviewStrategyResolver(),
                new PostDraftReviewProcessSummaryAssembler(),
                new InMemoryHumanInTheLoopGateway(),
                new PassThroughPostDraftReviewAgentWriter()
        );

        PostDraftChunkRecord chunk = reader.loadContinuationContext(projectId, ReviewFocus.forChunk(chunkId)).chunks().stream()
                .filter(candidate -> chunkId.equals(candidate.chunkId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Chunk not found for smoke test: " + chunkId));

        PostDraftReviewAgentResult result = service.review(
                new StartPostDraftReviewAgentCommand(projectId, ReviewFocus.forChunk(chunkId), operatorNote)
        );

        PostDraftReviewSmokeSupport smokeSupport = new PostDraftReviewSmokeSupport(objectMapper);
        Path outputDir = smokeSupport.prepareOutputDir(projectId, chunkId);
        smokeSupport.writeReport(outputDir, projectId, chunkId, operatorNote, chunk, result, false);

        assertNotNull(result.processSummary());
        assertTrue(Files.isDirectory(outputDir), "Smoke output directory should exist.");
        assertTrue(Files.isRegularFile(outputDir.resolve("result-summary.txt")), "Summary report should exist.");
        assertTrue(Files.isRegularFile(outputDir.resolve("result-debug.txt")), "Debug report should exist.");
        assertTrue(Files.isRegularFile(outputDir.resolve("result.json")), "JSON report should exist.");
        if (result.humanReviewRequest().isPresent()) {
            assertFalse(result.humanReviewRequest().orElseThrow().requestReason().isBlank(),
                    "Human review result must carry requestReason.");
        }
        if (!result.finalTranslatedText().isBlank()) {
            assertFalse(smokeSupport.preview(result.finalTranslatedText()).isBlank(),
                    "Final translated text preview should not be blank.");
        }

        System.out.println("[PostDraftReviewAgentSmokeTest] projectId=" + projectId);
        System.out.println("[PostDraftReviewAgentSmokeTest] chunkId=" + chunkId);
        System.out.println("[PostDraftReviewAgentSmokeTest] strategy=" + result.processSummary().strategy());
        System.out.println("[PostDraftReviewAgentSmokeTest] outputDir=" + outputDir.toAbsolutePath());
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

    private String requireChunkId(JdbcTemplate jdbcTemplate, String projectId) {
        String value = System.getProperty(CHUNK_ID_PROPERTY, "").trim();
        if (!value.isEmpty()) {
            return value;
        }
        String chunksJson = jdbcTemplate.query(
                "select chunks_json::text from ql_post_draft_review_package where project_id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                projectId
        );
        if (chunksJson == null || chunksJson.isBlank()) {
            throw new IllegalStateException("No post-draft review package found for projectId=" + projectId);
        }
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        try {
            List<PostDraftChunkRecord> chunks = objectMapper.readerForListOf(PostDraftChunkRecord.class).readValue(chunksJson);
            List<String> chunkIds = chunks.stream()
                    .map(PostDraftChunkRecord::chunkId)
                    .limit(20)
                    .toList();
            throw new IllegalStateException(
                    "Missing required system property: " + CHUNK_ID_PROPERTY
                            + ". Available chunkIds for projectId=" + projectId + " -> " + chunkIds
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to inspect chunk ids for projectId=" + projectId, exception);
        }
    }
}
