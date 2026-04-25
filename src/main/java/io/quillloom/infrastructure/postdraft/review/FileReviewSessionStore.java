package io.quillloom.infrastructure.postdraft.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import io.quillloom.application.postdraft.review.model.ProjectReviewRuntimeSession;
import io.quillloom.application.postdraft.review.model.StoredReviewSession;
import io.quillloom.application.postdraft.review.port.out.ReviewSessionStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public class FileReviewSessionStore implements ReviewSessionStore {

    private final Path rootDirectory;
    private final ObjectMapper objectMapper;

    public FileReviewSessionStore(Path rootDirectory) {
        this(rootDirectory, new ObjectMapper().findAndRegisterModules().disable(MapperFeature.AUTO_DETECT_IS_GETTERS));
    }

    public FileReviewSessionStore(Path rootDirectory,
                                  ObjectMapper objectMapper) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .findAndRegisterModules()
                .disable(MapperFeature.AUTO_DETECT_IS_GETTERS);
    }

    @Override
    public void save(ProjectReviewRuntimeSession runtime) {
        Objects.requireNonNull(runtime, "runtime");
        try {
            Files.createDirectories(rootDirectory);
            Path target = rootDirectory.resolve(runtime.projectId() + ".json");
            Files.writeString(target, objectMapper.writeValueAsString(StoredReviewSession.from(runtime)));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to persist review session for project=" + runtime.projectId(), ex);
        }
    }

    @Override
    public Optional<StoredReviewSession> load(String projectId) {
        Objects.requireNonNull(projectId, "projectId");
        Path target = rootDirectory.resolve(projectId + ".json");
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(Files.readString(target), StoredReviewSession.class));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read review session for project=" + projectId, ex);
        }
    }

    @Override
    public void delete(String projectId) {
        Objects.requireNonNull(projectId, "projectId");
        Path target = rootDirectory.resolve(projectId + ".json");
        try {
            Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to delete review session for project=" + projectId, ex);
        }
    }
}
