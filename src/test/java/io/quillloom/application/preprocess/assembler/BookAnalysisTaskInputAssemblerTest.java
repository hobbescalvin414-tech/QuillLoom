package io.quillloom.application.preprocess.assembler;

import io.quillloom.application.preprocess.command.PreprocessBookCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BookAnalysisTaskInputAssemblerTest {

    @Test
    void shouldAssembleStableAgentATaskInput() {
        PreprocessBookCommand command = new PreprocessBookCommand(
                "project-1",
                "示例小说",
                "Alice met Bob in Paris.",
                "en",
                "zh"
        );

        BookAnalysisTaskInputAssembler assembler = new BookAnalysisTaskInputAssembler();
        var input = assembler.assemble(command);

        assertEquals("project-1", input.projectId());
        assertEquals("示例小说", input.title());
        assertEquals("Alice met Bob in Paris.", input.sourceText());
        assertEquals("en", input.sourceLanguage());
        assertEquals("zh", input.targetLanguage());
    }
}