package io.quillloom.infrastructure.preprocess;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * PostgreSQL 知识库 schema 初始化器。
 * 仅负责数据库对象初始化，不承担仓储读写职责。
 */
@Component
@ConditionalOnProperty(prefix = "quillloom.preprocess.knowledge-base", name = "storage", havingValue = "postgres")
public class PostgresKnowledgeBaseSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeBaseStorageProperties properties;

    public PostgresKnowledgeBaseSchemaInitializer(JdbcTemplate jdbcTemplate,
                                                  KnowledgeBaseStorageProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        if (!properties.getPostgres().isInitializeSchema()) {
            return;
        }
        jdbcTemplate.execute("create extension if not exists vector");
        jdbcTemplate.execute("""
                create table if not exists ql_project_knowledge_card (
                    project_id varchar(255) not null,
                    card_id varchar(255) not null,
                    card_type varchar(64) not null,
                    title text not null,
                    content text not null,
                    scope varchar(64) not null,
                    primary key (project_id, card_id)
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists ql_project_knowledge_card_keyword (
                    project_id varchar(255) not null,
                    card_id varchar(255) not null,
                    order_index integer not null,
                    keyword text not null,
                    primary key (project_id, card_id, order_index)
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists ql_project_knowledge_card_anchor (
                    project_id varchar(255) not null,
                    card_id varchar(255) not null,
                    order_index integer not null,
                    anchor_name text not null,
                    primary key (project_id, card_id, order_index)
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists ql_project_knowledge_card_source (
                    project_id varchar(255) not null,
                    card_id varchar(255) not null,
                    order_index integer not null,
                    source_ref text not null,
                    primary key (project_id, card_id, order_index)
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists ql_project_knowledge_card_chunk (
                    project_id varchar(255) not null,
                    card_id varchar(255) not null,
                    order_index integer not null,
                    chunk_id varchar(255) not null,
                    primary key (project_id, card_id, order_index)
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists ql_project_knowledge_card_index (
                    project_id varchar(255) not null,
                    card_id varchar(255) not null,
                    retrieval_text text not null,
                    embedding vector,
                    embedding_model varchar(255) not null,
                    embedding_version varchar(255) not null,
                    primary key (project_id, card_id)
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists ql_candidate_term (
                    project_id varchar(255) not null,
                    source_term varchar(255) not null,
                    category varchar(255) not null,
                    rationale text not null,
                    primary key (project_id, source_term)
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists ql_candidate_term_translation (
                    project_id varchar(255) not null,
                    source_term varchar(255) not null,
                    order_index integer not null,
                    candidate_translation text not null,
                    primary key (project_id, source_term, order_index)
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists ql_post_draft_review_package (
                    project_id varchar(255) not null,
                    package_version varchar(64) not null,
                    source_language varchar(64) not null,
                    target_language varchar(64) not null,
                    source_document_digest varchar(255) not null,
                    created_at timestamp not null,
                    chunks_json jsonb not null,
                    block_indexes_json jsonb not null,
                    effective_confirmed_terms_json jsonb not null,
                    effective_candidate_terms_json jsonb not null,
                    glossary_snapshot_json jsonb not null,
                    alias_snapshot_json jsonb not null,
                    merged_draft_text text not null,
                    primary key (project_id)
                )
                """);
    }
}
