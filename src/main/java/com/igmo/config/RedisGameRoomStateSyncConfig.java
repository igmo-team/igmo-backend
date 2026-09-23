package com.igmo.config;

import com.igmo.store.redis.GameRoomStateChangePublisher;
import com.igmo.store.redis.GameRoomStateChangeSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@Profile("!test")
public class RedisGameRoomStateSyncConfig {

    @Bean
    public ChannelTopic gameRoomStateChangedTopic() {
        return new ChannelTopic(GameRoomStateChangePublisher.CHANNEL);
    }

    @Bean
    public RedisMessageListenerContainer gameRoomStateMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            GameRoomStateChangeSubscriber subscriber,
            ChannelTopic topic
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, topic);
        return container;
    }
}
