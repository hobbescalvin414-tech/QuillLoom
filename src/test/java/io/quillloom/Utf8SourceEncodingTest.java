package io.quillloom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Utf8SourceEncodingTest {

    @Test
    void shouldPreserveChineseLiteralsInSourceFiles() {
        String prompt = "当前为启发式全书分析骨架";
        String note = "保留上下文衔接提示";

        assertEquals("当前为启发式全书分析骨架", prompt);
        assertEquals("保留上下文衔接提示", note);
    }
}
