package com.igmo.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.igmo.support.AbstractSpringBootTest;
import com.igmo.web.PlayerSessionInterceptor;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.AbstractMessageChannel;

class WebSocketConfigTest extends AbstractSpringBootTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("clientInboundChannel")
    private AbstractMessageChannel clientInboundChannel;

    @Autowired
    private SimpleBrokerMessageHandler simpleBrokerMessageHandler;

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

    @Test
    @DisplayName("STOMP 브로커는 공용 topic과 사용자별 queue destination을 처리한다.")
    void 브로커가_topic과_queue_destination을_처리한다() {
        assertThat(simpleBrokerMessageHandler.getDestinationPrefixes())
                .containsExactlyInAnyOrder("/topic", "/queue");
    }

    @Test
    @DisplayName("브로커에 서버 송신 10초, 클라이언트 수신 기대 10초의 heartbeat와 TaskScheduler가 설정된다.")
    void 브로커에_heartbeat와_TaskScheduler가_설정된다() {
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(simpleBrokerMessageHandler.getHeartbeatValue())
                    .containsExactly(10000L, 10000L);
            softly.assertThat(simpleBrokerMessageHandler.getTaskScheduler()).isNotNull();
        });
    }
}
