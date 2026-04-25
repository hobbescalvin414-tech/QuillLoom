package io.quillloom.infrastructure.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenAiCompatibleLlmChunkTranslationClientTest {

    @Test
    void shouldDescribeTranslatedTextAsTargetLanguageDraftInsteadOfChineseOnly() {
        assertFalse(OpenAiCompatibleLlmChunkTranslationClient.responseSchema()
                .toString()
                .contains("中文翻译草稿"));
    }
}
