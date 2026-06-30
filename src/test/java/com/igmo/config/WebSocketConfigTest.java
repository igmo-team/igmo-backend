package com.igmo.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@SpringBootTest
class WebSocketConfigTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("STOMP 브로커 설정으로 SimpMessagingTemplate 빈이 등록된다.")
    void simpMessagingTemplate_빈이_등록된다() {
        assertThat(context.getBeanNamesForType(SimpMessagingTemplate.class)).isNotEmpty();
    }
}
