package com.igmo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IgmoApplicationTests {

    @Value("${spring.application.name}")
    private String applicationName;

    @Test
    void contextLoads() {
    }

    @Test
    void applicationNameIsIgmo() {
        assertThat(applicationName).isEqualTo("igmo");
    }

}
