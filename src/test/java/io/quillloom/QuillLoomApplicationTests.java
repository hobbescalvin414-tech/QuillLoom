package io.quillloom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfSystemProperty(named = "quillloom.test.spring-context.enabled", matches = "true")
class QuillLoomApplicationTests {

    @Test
    void contextLoads() {
    }
}
