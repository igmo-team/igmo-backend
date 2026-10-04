package com.igmo.store.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import com.igmo.domain.PromptEntryStatus;
import com.igmo.monitoring.GameMetrics;
import com.igmo.service.GameEventPublisher;
import com.igmo.service.GameRoomStateSyncService;
import com.igmo.store.GameRegistry;
import com.igmo.store.GameRoomDelivery;
import com.igmo.store.GameRoomRepository;
import com.igmo.store.RoomUpdate;
import com.igmo.web.LobbySnapshot;
import com.igmo.web.websocket.message.ImageGenerationEvent;
import com.igmo.web.websocket.message.RoomMessage;
import com.igmo.web.websocket.message.RoomMessageType;
import com.igmo.web.websocket.snapshot.GameResultSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
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
                new GameMetrics(new SimpleMeterRegistry(), new GameRegistry(), "test", "0")
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
    @DisplayName("다른 인스턴스가 Redis 최신 상태를 동기화한 뒤 방 스냅샷을 한 번 방송한다.")
    void 상태변경PubSub을받은인스턴스가_동기화후_방스냅샷을한번방송한다() throws Exception {
        // given
        GameRegistry blueRegistry = new GameRegistry();
        GameRegistry greenRegistry = new GameRegistry();
        GameRoomStateChangePublisher blueStatePublisher = new GameRoomStateChangePublisher(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules()
        );
        GameRoomStateChangePublisher greenStatePublisher = new GameRoomStateChangePublisher(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules()
        );
        SimpMessagingTemplate blueLocalBroker = mock(SimpMessagingTemplate.class);
        SimpMessagingTemplate greenLocalBroker = mock(SimpMessagingTemplate.class);
        GameEventPublisher blueEventPublisher = eventPublisher(blueLocalBroker, blueRegistry, "blue");
        GameEventPublisher greenEventPublisher = eventPublisher(greenLocalBroker, greenRegistry, "green");
        GameRoomRepository blueRepository = repository(blueRegistry, blueStatePublisher);
        GameRoomRepository greenRepository = repository(greenRegistry, greenStatePublisher);
        GameRoomStateSyncService blueSyncService = new GameRoomStateSyncService(blueRepository, blueEventPublisher);
        GameRoomStateSyncService greenSyncService = new GameRoomStateSyncService(greenRepository, greenEventPublisher);

        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        listenerContainer.addMessageListener(
                subscriber(blueStatePublisher, blueRepository, blueSyncService, blueEventPublisher),
                new ChannelTopic(GameRoomStateChangePublisher.CHANNEL)
        );
        listenerContainer.addMessageListener(
                subscriber(greenStatePublisher, greenRepository, greenSyncService, greenEventPublisher),
                new ChannelTopic(GameRoomStateChangePublisher.CHANNEL)
        );
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
        await(() -> listenerContainer.isListening());

        GameRoom blueRoom = GameRoom.create("ABCD", new Player("Blue 호스트"), Duration.ofMinutes(10));
        blueRepository.saveIfAbsent(blueRoom);
        await(() -> greenRegistry.find("ABCD").isPresent());

        // when
        blueRepository.updateWithCas("ABCD", room -> {
            room.addPlayer(new Player("Green 참가자"));
            room.addPlayer(new Player("Yellow 참가자"));
            return RoomUpdate.changed("updated", List.of(GameRoomDelivery.roomSnapshot()));
        });
        blueEventPublisher.publishLobby("ABCD", LobbySnapshot.from(blueRoom));

        // then: 방 스냅샷은 상태를 동기화한 Green에서 한 번 전송된다.
        await(() -> greenRegistry.find("ABCD")
                .map(room -> room.getPlayers().size() == 3)
                .orElse(false));
        await(() -> org.mockito.Mockito.mockingDetails(greenLocalBroker).getInvocations().size() == 1);
        verify(blueLocalBroker, times(1)).convertAndSend(eq("/topic/rooms/ABCD"), any(RoomMessage.class));
        verify(greenLocalBroker, times(1)).convertAndSend(eq("/topic/rooms/ABCD"), any(RoomMessage.class));
        org.mockito.ArgumentCaptor<RoomMessage> roomMessageCaptor =
                org.mockito.ArgumentCaptor.forClass(RoomMessage.class);
        verify(greenLocalBroker).convertAndSend(eq("/topic/rooms/ABCD"), roomMessageCaptor.capture());
        LobbySnapshot lobbySnapshot = (LobbySnapshot) roomMessageCaptor.getValue().payload();
        assertThat(lobbySnapshot.players()).hasSize(3);

        // when: 개인 이미지 결과는 playerId를 유지해 B의 로컬 사용자 큐로 전달한다.
        String hostId = blueRoom.getHostId();
        blueRepository.updateWithCas("ABCD", room -> {
            room.getPlayers().stream()
                    .filter(player -> !player.getId().equals(room.getHostId()))
                    .forEach(player -> room.changePlayerReady(player.getId(), true));
            room.start(room.getHostId(), java.time.Instant.now(), Duration.ofMinutes(1));
            room.submitPrompt(hostId, "파란 고래", java.time.Instant.now());
            return RoomUpdate.changed("prompt submitted", List.of(GameRoomDelivery.imageResult(hostId)));
        });
        blueEventPublisher.sendImageGenerationEvent(
                hostId,
                new ImageGenerationEvent("ABCD", PromptEntryStatus.GENERATING, "파란 고래", null));

        // then: 개인 큐 결과도 B에서 한 번만 발행된다.
        await(() -> greenRegistry.find("ABCD")
                .map(room -> room.getPhase() == com.igmo.domain.GamePhase.GENERATING)
                .orElse(false));
        await(() -> org.mockito.Mockito.mockingDetails(greenLocalBroker).getInvocations().size() == 2);
        verify(blueLocalBroker, times(1)).convertAndSendToUser(
                eq(hostId), eq("/queue/image-generation"), any(ImageGenerationEvent.class));
        verify(greenLocalBroker, times(1)).convertAndSendToUser(
                eq(hostId), eq("/queue/image-generation"), any(ImageGenerationEvent.class));

        // when: 실패 사유도 Redis 상태로 보존되어 B에서 복원된다.
        blueRepository.updateWithCas("ABCD", room -> {
            room.failImageGeneration(hostId, "이미지 제공자 오류");
            return RoomUpdate.changed("image failed", List.of(GameRoomDelivery.imageResult(hostId)));
        });
        blueEventPublisher.sendImageGenerationEvent(
                hostId,
                new ImageGenerationEvent(
                        "ABCD", PromptEntryStatus.FAILED, "파란 고래", null, "이미지 제공자 오류"));

        // then
        await(() -> org.mockito.Mockito.mockingDetails(greenLocalBroker).getInvocations().size() == 3);
        org.mockito.ArgumentCaptor<ImageGenerationEvent> imageEventCaptor =
                org.mockito.ArgumentCaptor.forClass(ImageGenerationEvent.class);
        verify(greenLocalBroker, times(2)).convertAndSendToUser(
                eq(hostId), eq("/queue/image-generation"), imageEventCaptor.capture());
        assertThat(imageEventCaptor.getAllValues())
                .extracting(ImageGenerationEvent::status)
                .containsExactly(PromptEntryStatus.GENERATING, PromptEntryStatus.FAILED);
        assertThat(imageEventCaptor.getAllValues().getLast().errorMessage())
                .isEqualTo("이미지 제공자 오류");

        // when: 종료 스냅샷을 저장한 직후 방 키를 삭제한다.
        GameResultSnapshot finalSnapshot = blueRepository.updateWithCas("ABCD", room -> {
            ReflectionTestUtils.setField(room, "phase", GamePhase.ENDED);
            return RoomUpdate.changed(GameResultSnapshot.from(room), List.of());
        }).value();
        blueEventPublisher.publish("ABCD", RoomMessage.gameResultSnapshot(finalSnapshot));
        blueRepository.remove(blueRegistry.find("ABCD").orElseThrow());

        // then: Redis 키가 없어도 삭제 이벤트의 최종 결과를 B가 한 번 방송한다.
        await(() -> org.mockito.Mockito.mockingDetails(greenLocalBroker).getInvocations().size() == 4);
        org.mockito.ArgumentCaptor<RoomMessage> allRoomMessagesCaptor =
                org.mockito.ArgumentCaptor.forClass(RoomMessage.class);
        verify(greenLocalBroker, times(2)).convertAndSend(
                eq("/topic/rooms/ABCD"), allRoomMessagesCaptor.capture());
        assertThat(allRoomMessagesCaptor.getAllValues())
                .extracting(RoomMessage::type)
                .containsExactly(RoomMessageType.LOBBY_SNAPSHOT, RoomMessageType.GAME_RESULT_SNAPSHOT);
    }

    private GameRoomRepository repository(GameRegistry registry, GameRoomStateChangePublisher publisher) {
        return new GameRoomRepository(
                registry,
                Optional.of(redisRepository),
                Optional.of(publisher),
                mock(ApplicationEventPublisher.class)
        );
    }

    private GameRoomStateChangeSubscriber subscriber(
            GameRoomStateChangePublisher publisher,
            GameRoomRepository repository,
            GameRoomStateSyncService syncService,
            GameEventPublisher eventPublisher
    ) {
        return new GameRoomStateChangeSubscriber(
                new ObjectMapper().findAndRegisterModules(),
                publisher,
                repository,
                syncService,
                eventPublisher
        );
    }

    private GameEventPublisher eventPublisher(
            SimpMessagingTemplate localBroker,
            GameRegistry registry,
            String instanceId
    ) {
        return new GameEventPublisher(
                localBroker,
                new GameMetrics(new SimpleMeterRegistry(), registry, instanceId, "0")
        );
    }

    private static void await(Check check) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!check.matches()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Redis Pub/Sub 처리 결과가 시간 내 도착하지 않았습니다.");
            }
            Thread.sleep(25);
        }
    }

    @FunctionalInterface
    private interface Check {

        boolean matches();
    }
}
