package io.quillloom.infrastructure.preprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextLengthTimeoutPolicyTest {

    private final TextLengthTimeoutPolicy policy = new TextLengthTimeoutPolicy();

    @Test
    void shouldUseBaseTimeoutForShortText() {
        ResolvedTextTimeout timeout = policy.resolve("short", 180, 10000, 60, 900);

        assertEquals(5, timeout.charCount());
        assertEquals(180, timeout.timeoutSeconds());
    }

    @Test
    void shouldIncreaseTimeoutWhenTextExceedsStepThreshold() {
        String text = "a".repeat(25001);

        ResolvedTextTimeout timeout = policy.resolve(text, 180, 10000, 60, 900);

        assertEquals(25001, timeout.charCount());
        assertEquals(360, timeout.timeoutSeconds());
    }

    @Test
    void shouldCapTimeoutAtConfiguredMaximum() {
        String text = "a".repeat(300000);

        ResolvedTextTimeout timeout = policy.resolve(text, 180, 10000, 60, 360);

        assertEquals(360, timeout.timeoutSeconds());
    }

    @Test
    void shouldReachHigherTimeoutEarlierForVeryLongText() {
        String text = "a".repeat(120000);

        ResolvedTextTimeout timeout = policy.resolve(text, 180, 10000, 60, 900);

        assertEquals(120000, timeout.charCount());
        assertEquals(900, timeout.timeoutSeconds());
    }
}
