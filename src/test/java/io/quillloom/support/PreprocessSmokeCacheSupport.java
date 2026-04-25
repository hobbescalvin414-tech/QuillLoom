package io.quillloom.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quillloom.domain.preprocess.PreprocessDossier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 仅供测试使用的 A/B/C0 完成态缓存。
 * 不进入正式主链路，后续正式持久化落地后可整体删除。
 */
public class PreprocessSmokeCacheSupport {

    private final Path rootDir;
    private final String cacheVersion;
    private final ObjectMapper objectMapper;

    public PreprocessSmokeCacheSupport(Path rootDir, String cacheVersion) {
        this.rootDir = Objects.requireNonNull(rootDir);
        this.cacheVersion = Objects.requireNonNull(cacheVersion);
        this.objectMapper = new ObjectMapper();
    }

    public CachedPreprocess loadOrCompute(String sourceTextHash,
                                          String sourceLanguage,
                                          String targetLanguage,
                                          Supplier<PreprocessDossier> loader) {
        Path cacheFile = resolveCacheFile(sourceTextHash, sourceLanguage, targetLanguage);
        if (Files.isRegularFile(cacheFile)) {
            try {
                return new CachedPreprocess(objectMapper.readValue(cacheFile.toFile(), PreprocessDossier.class), true, cacheFile);
            } catch (IOException ignored) {
                // 显式重算，不静默伪造缓存命中。
            }
        }

        PreprocessDossier dossier = loader.get();
        try {
            Files.createDirectories(cacheFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), dossier);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write preprocess smoke cache: " + cacheFile, ex);
        }
        return new CachedPreprocess(dossier, false, cacheFile);
    }

    Path resolveCacheFile(String sourceTextHash, String sourceLanguage, String targetLanguage) {
        return rootDir
                .resolve(cacheVersion)
                .resolve(sourceLanguage + "-to-" + targetLanguage)
                .resolve(sourceTextHash + ".json");
    }

    public record CachedPreprocess(
            PreprocessDossier dossier,
            boolean cacheHit,
            Path cacheFile
    ) {
    }
}
