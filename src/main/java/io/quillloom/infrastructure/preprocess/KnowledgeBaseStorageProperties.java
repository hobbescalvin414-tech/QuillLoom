package io.quillloom.infrastructure.preprocess;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 项目知识库存储配置。
 */
@ConfigurationProperties(prefix = "quillloom.preprocess.knowledge-base")
public class KnowledgeBaseStorageProperties {

    private String storage = "memory";
    private final Postgres postgres = new Postgres();

    public String getStorage() {
        return storage;
    }

    public void setStorage(String storage) {
        this.storage = storage;
    }

    public Postgres getPostgres() {
        return postgres;
    }

    public static class Postgres {

        private String url = "jdbc:postgresql://localhost:5432/robot";
        private String username = "postgres";
        private String password = "postgres";
        private String schema = "public";
        private boolean initializeSchema = true;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public boolean isInitializeSchema() {
            return initializeSchema;
        }

        public void setInitializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
        }
    }
}
