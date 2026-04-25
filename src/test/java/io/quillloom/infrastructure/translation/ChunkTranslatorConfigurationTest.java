package io.quillloom.infrastructure.translation;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkTranslatorConfigurationTest {

    @Test
    void shouldUseExtendedFixedTimeoutForChunkTranslation() {
        assertEquals(Duration.ofSeconds(600), ChunkTranslatorConfiguration.translationTimeout());
    }
}
