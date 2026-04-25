package io.quillloom.infrastructure.preprocess;

import java.time.Duration;

public record ResolvedTextTimeout(
        int charCount,
        int timeoutSeconds
) {
    public Duration toDuration() {
        return Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }
}
