package com.igmo.config;

import com.igmo.web.PlayerSessionInterceptor;
import com.igmo.web.WebSocketSessionDecoratorFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final PlayerSessionInterceptor playerSessionInterceptor;
    private final WebSocketSessionDecoratorFactory webSocketSessionDecoratorFactory;

    private static final String[] ALLOWED_ORIGIN_PATTERNS = {
            "http://localhost:*",
            "https://igmo.co.kr",
            "https://www.igmo.co.kr"
    };

    private static final long[] HEARTBEAT_MILLIS = {2_000, 2_000};

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(HEARTBEAT_MILLIS)
                .setTaskScheduler(webSocketHeartbeatScheduler());
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(playerSessionInterceptor);
        registration.taskExecutor(createMessageChannelExecutor("ws-inbound-"));
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor(createMessageChannelExecutor("ws-outbound-"));
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(webSocketSessionDecoratorFactory);
    }

    @Bean
    public TaskScheduler webSocketHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setAcceptTasksAfterContextClose(true);
        return scheduler;
    }

    @Bean
    public TaskScheduler disconnectGraceScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("disconnect-grace-");
        scheduler.setAcceptTasksAfterContextClose(true);
        return scheduler;
    }

    @Bean
    public TaskScheduler gamePhaseDeadlineScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("game-phase-deadline-");
        scheduler.setAcceptTasksAfterContextClose(true);
        return scheduler;
    }

    @Bean
    public TaskScheduler imageGenerationCompletionScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("image-generation-completion-");
        scheduler.setAcceptTasksAfterContextClose(true);
        return scheduler;
    }

    private ThreadPoolTaskExecutor createMessageChannelExecutor(String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Runtime.getRuntime().availableProcessors() * 2);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setAcceptTasksAfterContextClose(true);
        return executor;
    }
}
