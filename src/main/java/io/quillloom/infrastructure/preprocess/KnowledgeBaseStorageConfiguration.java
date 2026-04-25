package io.quillloom.infrastructure.preprocess;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 项目知识库存储装配。
 */
@Configuration
@EnableConfigurationProperties(KnowledgeBaseStorageProperties.class)
public class KnowledgeBaseStorageConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "quillloom.preprocess.knowledge-base", name = "storage", havingValue = "postgres")
    public DataSource knowledgeBaseDataSource(KnowledgeBaseStorageProperties properties) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(properties.getPostgres().getUrl());
        dataSource.setUsername(properties.getPostgres().getUsername());
        dataSource.setPassword(properties.getPostgres().getPassword());
        return dataSource;
    }

    @Bean
    @ConditionalOnProperty(prefix = "quillloom.preprocess.knowledge-base", name = "storage", havingValue = "postgres")
    public JdbcTemplate knowledgeBaseJdbcTemplate(DataSource knowledgeBaseDataSource) {
        return new JdbcTemplate(knowledgeBaseDataSource);
    }

    @Bean
    @ConditionalOnProperty(prefix = "quillloom.preprocess.knowledge-base", name = "storage", havingValue = "postgres")
    public PlatformTransactionManager knowledgeBaseTransactionManager(DataSource knowledgeBaseDataSource) {
        return new DataSourceTransactionManager(knowledgeBaseDataSource);
    }
}
