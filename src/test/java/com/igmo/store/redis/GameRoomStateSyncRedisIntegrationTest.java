package com.igmo.store.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GameRoomState;
import com.igmo.domain.Player;
import com.igmo.monitoring.GameMetrics;
import com.igmo.store.GameRegistry;
import com.igmo.store.GameRoomRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class GameRoomStateSyncRedisIntegrationTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")
    ).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisGameRoomStateRepository redisRepository;
    private static RedisMessageListenerContainer listenerContainer;

    @BeforeAll
    static void setUpRedis() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                redis.getHost(),
                redis.getMappedPort(6379)
        ));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisRepository = new RedisGameRoomStateRepository(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                new GameMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                        new GameRegistry(),
                        "test",
                        "0")
        );
    }

    @BeforeEach
    void clearRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterAll
    static void tearDownRedis() {
        if (listenerContainer != null) {
            listenerContainer.stop();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("Blue와 Green이 Pub/Sub으로 서로의 로컬 GameRoom을 Redis 상태로 동기화한다.")
    void 양방향상태동기화() throws Exception {
        // given
        GameRegistry blueRegistry = new GameRegistry();
        GameRegistry greenRegistry = new GameRegistry();
        GameRoomStateChangePublisher bluePublisher = new GameRoomStateChangePublisher(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules()
        );
        GameRoomStateChangePublisher greenPublisher = new GameRoomStateChangePublisher(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules()
        );
        GameRoomRepository blueRepository = new GameRoomRepository(
                blueRegistry,
                Optional.of(redisRepository),
                Optional.of(bluePublisher),
                mock(ApplicationEventPublisher.class)
        );
        GameRoomRepository greenRepository = new GameRoomRepository(
                greenRegistry,
                Optional.of(redisRepository),
                Optional.of(greenPublisher),
                mock(ApplicationEventPublisher.class)
        );
        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        listenerContainer.addMessageListener(
                new GameRoomStateChangeSubscriber(
                        new ObjectMapper().findAndRegisterModules(),
                        bluePublisher,
                        blueRepository
                ),
                new ChannelTopic(GameRoomStateChangePublisher.CHANNEL)
        );
        listenerContainer.addMessageListener(
                new GameRoomStateChangeSubscriber(
                        new ObjectMapper().findAndRegisterModules(),
                        greenPublisher,
                        greenRepository
                ),
                new ChannelTopic(GameRoomStateChangePublisher.CHANNEL)
        );
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
        await(() -> listenerContainer.isListening());

        GameRoom blueRoom = GameRoom.create("ABCD", new Player("Blue 호스트"), Duration.ofMinutes(10));

        // when
        blueRepository.saveIfAbsent(blueRoom);
        await(() -> greenRegistry.find("ABCD").isPresent());

        greenRepository.update("ABCD", room -> {
            room.addPlayer(new Player("Green 참가자"));
            return null;
        });
        await(() -> blueRegistry.find("ABCD")
                .map(room -> room.getPlayers().size() == 2)
                .orElse(false));

        // then
        assertThat(GameRoomState.from(blueRegistry.find("ABCD").orElseThrow()))
                .isEqualTo(GameRoomState.from(greenRegistry.find("ABCD").orElseThrow()));
    }

    private static void await(Check check) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!check.matches()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Redis Pub/Sub 상태 동기화가 시간 내 완료되지 않았습니다.");
            }
            Thread.sleep(25);
        }
    }

    @FunctionalInterface
    private interface Check {

        boolean matches();
    }
}
