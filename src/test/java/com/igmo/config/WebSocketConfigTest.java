package com.igmo.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.igmo.web.PlayerSessionInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.AbstractMessageChannel;

@SpringBootTest
class WebSocketConfigTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("clientInboundChannel")
    private AbstractMessageChannel clientInboundChannel;

    @Test
    @DisplayName("STOMP 브로커 설정으로 SimpMessagingTemplate 빈이 등록된다.")
    void simpMessagingTemplate_빈이_등록된다() {
        assertThat(context.getBeanNamesForType(SimpMessagingTemplate.class)).isNotEmpty();
    }

    @Test
    @DisplayName("클라이언트 인바운드 채널에 PlayerSessionInterceptor가 등록된다.")
    void clientInboundChannel에_PlayerSessionInterceptor가_등록된다() {
        assertThat(clientInboundChannel.getInterceptors())
                .anyMatch(interceptor -> interceptor instanceof PlayerSessionInterceptor);
    }
}
