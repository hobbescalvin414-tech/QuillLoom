package io.quillloom.infrastructure.postdraft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quillloom.application.postdraft.port.out.PostDraftReviewPackageRepository;
import io.quillloom.domain.memory.DraftStageGlobalGlossary;
import io.quillloom.domain.memory.GlobalAliasConsistencyTable;
import io.quillloom.domain.postdraft.PostDraftBlockIndex;
import io.quillloom.domain.postdraft.PostDraftChunkRecord;
import io.quillloom.domain.postdraft.PostDraftReviewPackage;
import io.quillloom.domain.postdraft.PostDraftTermState;
import io.quillloom.domain.translation.TranslationCandidateUpdate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "quillloom.post-draft-review-package", name = "storage", havingValue = "postgres")
public class PostgresPostDraftReviewPackageRepository implements PostDraftReviewPackageRepository {

    private static final TypeReference<List<PostDraftChunkRecord>> CHUNK_RECORD_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<PostDraftBlockIndex>> BLOCK_INDEX_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> CONFIRMED_TERM_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<TranslationCandidateUpdate>> CANDIDATE_UPDATE_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresPostDraftReviewPackageRepository(JdbcTemplate jdbcTemplate,
                                                    ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<PostDraftReviewPackage> load(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        List<PostDraftReviewPackage> packages = jdbcTemplate.query(
                """
                select project_id, package_version, source_language, target_language, source_document_digest,
                       created_at, chunks_json, block_indexes_json, effective_confirmed_terms_json,
                       effective_candidate_terms_json, glossary_snapshot_json, alias_snapshot_json, merged_draft_text
                from ql_post_draft_review_package
                where project_id = ?
                """,
                (rs, rowNum) -> new PostDraftReviewPackage(
                        rs.getString("project_id"),
                        rs.getString("package_version"),
                        rs.getString("source_language"),
                        rs.getString("target_language"),
                        rs.getString("source_document_digest"),
                        rs.getTimestamp("created_at").toInstant(),
                        readJson(rs.getString("chunks_json"), CHUNK_RECORD_LIST),
                        readJson(rs.getString("block_indexes_json"), BLOCK_INDEX_LIST),
                        new PostDraftTermState(
                                readJson(rs.getString("effective_confirmed_terms_json"), CONFIRMED_TERM_MAP),
                                readJson(rs.getString("effective_candidate_terms_json"), CANDIDATE_UPDATE_LIST)
                        ),
                        readJson(rs.getString("glossary_snapshot_json"), DraftStageGlobalGlossary.class),
                        readJson(rs.getString("alias_snapshot_json"), GlobalAliasConsistencyTable.class),
                        rs.getString("merged_draft_text")
                ),
                projectId
        );
        if (packages.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(packages.get(0));
    }

    @Override
    @Transactional(transactionManager = "knowledgeBaseTransactionManager")
    public void save(PostDraftReviewPackage reviewPackage) {
        if (reviewPackage == null || reviewPackage.projectId() == null || reviewPackage.projectId().isBlank()) {
            throw new IllegalArgumentException("reviewPackage must have a projectId.");
        }
        jdbcTemplate.update("delete from ql_post_draft_review_package where project_id = ?", reviewPackage.projectId());
        jdbcTemplate.update(
                """
                insert into ql_post_draft_review_package(
                    project_id, package_version, source_language, target_language, source_document_digest,
                    created_at, chunks_json, block_indexes_json, effective_confirmed_terms_json,
                    effective_candidate_terms_json, glossary_snapshot_json, alias_snapshot_json, merged_draft_text
                ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?)
                """,
                reviewPackage.projectId(),
                reviewPackage.packageVersion(),
                reviewPackage.sourceLanguage(),
                reviewPackage.targetLanguage(),
                reviewPackage.sourceDocumentDigest(),
                Timestamp.from(resolveCreatedAt(reviewPackage.createdAt())),
                writeJson(reviewPackage.chunks()),
                writeJson(reviewPackage.blockIndexes()),
                writeJson(reviewPackage.termState().effectiveConfirmedTerms()),
                writeJson(reviewPackage.termState().effectiveCandidateTerms()),
                writeJson(reviewPackage.glossarySnapshot()),
                writeJson(reviewPackage.aliasSnapshot()),
                reviewPackage.mergedDraftText()
        );
    }

    private Instant resolveCreatedAt(Instant createdAt) {
        return createdAt == null ? Instant.now() : createdAt;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize post-draft review package payload.", exception);
        }
    }

    private <T> T readJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize post-draft review package payload.", exception);
        }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize post-draft review package payload.", exception);
        }
    }
}
