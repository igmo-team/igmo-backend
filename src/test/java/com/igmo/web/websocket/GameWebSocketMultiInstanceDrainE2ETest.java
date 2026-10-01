package com.igmo.web.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.igmo.IgmoApplication;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GameRoomState;
import com.igmo.domain.GamePhase;
import com.igmo.domain.PromptEntryStatus;
import com.igmo.domain.PromptSubmissionType;
import com.igmo.imagegeneration.GeneratedImage;
import com.igmo.imagegeneration.ImageGenerationRequest;
import com.igmo.imagegeneration.ImageGenerator;
import com.igmo.service.GameDrainLifecycle;
import com.igmo.service.GameRoomStateSyncService;
import com.igmo.service.ImageStorageClient;
import com.igmo.service.lobby.GameLobbyService;
import com.igmo.service.phase.PromptPhaseService;
import com.igmo.service.presence.PlayerKey;
import com.igmo.service.presence.PlayerSessionRegistry;
import com.igmo.store.GameRegistry;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.http.response.CreateGameResponse;
import com.igmo.web.http.response.JoinGameResponse;
import com.igmo.web.websocket.request.PromptRequest;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GameWebSocketMultiInstanceDrainE2ETest {

    private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DUPLICATE_CHECK_WINDOW = Duration.ofMillis(500);

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private ConfigurableApplicationContext instanceAContext;
    private ConfigurableApplicationContext instanceBContext;
    private Node instanceA;
    private Node instanceB;
    private CountDownLatch drainComplete;

    @BeforeAll
    void startInstances() {
        instanceAContext = startInstance("A");
        instanceBContext = startInstance("B");
        instanceA = node(instanceAContext);
        instanceB = node(instanceBContext);
        awaitCondition(() -> listenerIsReady(instanceAContext) && listenerIsReady(instanceBContext),
                "두 인스턴스의 Redis 구독 시작");
    }

    @AfterEach
    void clearRoomsAndRedis() {
        instanceA.registry().snapshot().forEach(room -> instanceA.repository().remove(room));
        instanceB.registry().snapshot().forEach(room -> instanceB.registry().remove(room.getCode()));
        if (drainComplete != null) {
            awaitCondition(() -> drainComplete.getCount() == 0, "Drain 종료 콜백");
        }
        instanceA.redisTemplate().getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterAll
    void stopInstances() {
        if (instanceAContext != null) {
            instanceAContext.close();
        }
        if (instanceBContext != null) {
            instanceBContext.close();
        }
    }

    @Test
    @DisplayName("Drain 중 A의 이미지 완료 상태와 메시지가 B의 실제 STOMP 구독에 전달된다.")
    void Drain중_이미지완료가_다른인스턴스의실제웹소켓구독에_한번전달된다() throws Exception {
        // given
        CreateGameResponse host = instanceA.lobbyService().createGame("A 호스트");
        JoinGameResponse guest = instanceA.lobbyService().joinGame(host.roomCode(), "B 참가자");
        String roomCode = host.roomCode();

        try (PlayerSocket hostOnA = connect(instanceA, host.roomCode(), host.playerId(), host.secret());
             PlayerSocket guestOnB = connect(instanceB, host.roomCode(), guest.playerId(), guest.secret())) {
            synchronizeSubscriptions(instanceA, hostOnA);
            synchronizeSubscriptions(instanceB, guestOnB);
            hostOnA.clear();
            guestOnB.clear();

            instanceA.lobbyService().changeReady(roomCode, guest.playerId(), true);
            assertExactlyOne(hostOnA.topicMessages(), message -> isType(message, "LOBBY_SNAPSHOT"));
            assertExactlyOne(guestOnB.topicMessages(), message -> isType(message, "LOBBY_SNAPSHOT"));
            instanceA.promptService().startGame(roomCode, host.playerId());
            assertExactlyOne(hostOnA.topicMessages(), message -> isType(message, "PROMPT_SUBMISSION_SNAPSHOT"));
            assertExactlyOne(guestOnB.topicMessages(), message -> isType(message, "PROMPT_SUBMISSION_SNAPSHOT"));

            // A는 진행 중 방을 둔 채 Drain에 진입한다. 기존 세션과 콜백 처리는 살아 있어야 한다.
            drainComplete = new CountDownLatch(1);
            instanceA.drainLifecycle().stop(drainComplete::countDown);
            assertThat(drainComplete.getCount()).isEqualTo(1);
            hostOnA.clear();
            guestOnB.clear();

            // when: A에서 이미지 생성을 시작한 뒤 플레이어가 A 연결을 끊고 B로 재연결한다.
            hostOnA.send(
                    "/app/rooms/" + roomCode + "/prompts",
                    new PromptRequest("파란 고래", PromptSubmissionType.NORMAL));
            ControlledImageGenerator.PendingGeneration pending = instanceA.imageGenerator().awaitNextGeneration();
            assertExactlyOne(hostOnA.imageGenerationMessages(),
                    message -> message.path("status").asText().equals("GENERATING"));
            awaitCondition(() -> instanceB.registry().find(roomCode)
                            .map(room -> room.getPhase() == GamePhase.GENERATING)
                            .orElse(false),
                    "B가 생성 중 상태를 Redis에서 복원");

            hostOnA.close();
            awaitCondition(() -> !instanceA.playerSessions().hasActiveSession(new PlayerKey(roomCode, host.playerId())),
                    "A에서 플레이어 세션 연결 종료");
            try (PlayerSocket hostOnB = connect(instanceB, roomCode, host.playerId(), host.secret())) {
                synchronizeSubscriptions(instanceB, hostOnB);
                hostOnB.clear();
                guestOnB.clear();

                // when
                pending.complete(new GeneratedImage(new byte[]{1}, "image/png"));

                // then: private result은 P1만 받고, 같은 완료의 방 스냅샷은 B의 방 구독자들이 받는다.
                JsonNode imageResult = assertExactlyOne(
                        hostOnB.imageGenerationMessages(),
                        message -> message.path("status").asText().equals("READY"));
                assertThat(imageResult.path("imageUrl").asText()).isEqualTo("https://images.test/generated.png");
                assertExactlyOne(hostOnB.topicMessages(), message -> isType(message, "PROMPT_SUBMISSION_SNAPSHOT"));
                assertExactlyOne(guestOnB.topicMessages(), message -> isType(message, "PROMPT_SUBMISSION_SNAPSHOT"));
                assertNoMatching(guestOnB.imageGenerationMessages(),
                        message -> message.path("status").asText().equals("READY"));
                awaitCondition(() -> sameRoomState(instanceA, instanceB, host.roomCode()),
                        "이미지 완료 상태가 두 인스턴스에 동일하게 반영");
                assertThat(instanceA.registry().find(host.roomCode()).orElseThrow()
                        .findPromptEntry(host.playerId()).orElseThrow().getStatus())
                        .isEqualTo(PromptEntryStatus.READY);
            }
        }
    }

    private ConfigurableApplicationContext startInstance(String slot) {
        return new SpringApplicationBuilder(IgmoApplication.class, TestDependencies.class)
                .profiles("local", "drain-e2e")
                .run(
                        "--server.port=0",
                        "--spring.main.banner-mode=off",
                        "--spring.data.redis.host=" + redis.getHost(),
                        "--spring.data.redis.port=" + redis.getMappedPort(6379),
                        "--spring.lifecycle.timeout-per-shutdown-phase=6h",
                        "--igmo.deployment.slot=" + slot,
                        "--igmo.deployment.port=0",
                        "--igmo.game.disconnect-grace=5m",
                        "--igmo.game.lobby-duration=10m",
                        "--igmo.game.prompt-duration=2m",
                        "--igmo.game.guess-duration=2m",
                        "--igmo.game.vote-duration=2m",
                        "--igmo.game.vote-skipped-duration=2m",
                        "--igmo.game.result-duration=2m",
                        "--igmo.game.image-generation-completion-delay=10m",
                        "--igmo.ai.gemini.api-key=drain-test-key",
                        "--igmo.ai.gemini.model=drain-test-model",
                        "--igmo.ai.gemini.image-size=1K",
                        "--igmo.admin.image-generation.username=test-admin",
                        "--igmo.admin.image-generation.password=test-password",
                        "--igmo.admin.image-generation.storage.s3.bucket=test-admin-bucket",
                        "--igmo.admin.image-generation.storage.s3.key-prefix=test-admin-images",
                        "--igmo.image-storage.s3.bucket=test-bucket",
                        "--igmo.image-storage.s3.region=us-east-1",
                        "--igmo.image-storage.s3.key-prefix=test-images",
                        "--igmo.image-storage.s3.public-base-url=https://images.test"
                );
    }

    private Node node(ConfigurableApplicationContext context) {
        WebServerApplicationContext webContext = (WebServerApplicationContext) context;
        return new Node(
                webContext.getWebServer().getPort(),
                context.getBean(GameLobbyService.class),
                context.getBean(PromptPhaseService.class),
                context.getBean(GameRoomStateSyncService.class),
                context.getBean(GameRegistry.class),
                context.getBean(GameRoomRepository.class),
                context.getBean(PlayerSessionRegistry.class),
                context.getBean(GameDrainLifecycle.class),
                context.getBean(ControlledImageGenerator.class),
                context.getBean(StringRedisTemplate.class)
        );
    }

    private boolean listenerIsReady(ConfigurableApplicationContext context) {
        return context.getBean(org.springframework.data.redis.listener.RedisMessageListenerContainer.class)
                .isListening();
    }

    private PlayerSocket connect(Node node, String roomCode, String playerId, String secret) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(PlayerSessionInterceptor.ROOM_CODE_ATTRIBUTE, roomCode);
        connectHeaders.add(PlayerSessionInterceptor.PLAYER_ID_ATTRIBUTE, playerId);
        connectHeaders.add(PlayerSessionInterceptor.SECRET_HEADER, secret);
        StompSession session = client.connectAsync(
                        URI.create("ws://localhost:" + node.port() + "/ws").toString(),
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(MESSAGE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        PlayerSocket socket = new PlayerSocket(playerId, client, session);
        session.subscribe("/topic/rooms/" + roomCode, new JsonNodeFrameHandler(socket.topicMessages()));
        session.subscribe("/user/queue/image-generation",
                new JsonNodeFrameHandler(socket.imageGenerationMessages()));
        session.subscribe("/user/queue/room-state", new JsonNodeFrameHandler(socket.roomStateMessages()));
        return socket;
    }

    private void synchronizeSubscriptions(Node node, PlayerSocket socket) throws Exception {
        long deadline = System.nanoTime() + MESSAGE_TIMEOUT.toNanos();
        boolean roomStateReady = false;
        while (!roomStateReady && System.nanoTime() < deadline) {
            node.stateSyncService().sync(findRoomCode(node, socket.playerId()), socket.playerId());
            roomStateReady = socket.roomStateMessages().poll(200, TimeUnit.MILLISECONDS) != null;
        }
        assertThat(roomStateReady).as("개인 상태 큐 구독 준비").isTrue();
        socket.clear();
    }

    private String findRoomCode(Node node, String playerId) {
        return node.registry().snapshot().stream()
                .filter(room -> room.hasPlayer(playerId))
                .map(GameRoom::getCode)
                .findFirst()
                .orElseThrow(() -> new AssertionError("로컬 인스턴스에서 플레이어의 게임방을 찾지 못했습니다."));
    }

    private JsonNode assertExactlyOne(BlockingQueue<JsonNode> queue, Predicate<JsonNode> predicate)
            throws InterruptedException {
        JsonNode message = awaitMessage(queue, predicate, "기대 메시지");
        assertNoMatching(queue, predicate);
        return message;
    }

    private JsonNode awaitMessage(
            BlockingQueue<JsonNode> queue,
            Predicate<JsonNode> predicate,
            String description
    ) throws InterruptedException {
        List<JsonNode> observed = new ArrayList<>();
        long deadline = System.nanoTime() + MESSAGE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode message = queue.poll(100, TimeUnit.MILLISECONDS);
            if (message == null) {
                continue;
            }
            observed.add(message);
            if (predicate.test(message)) {
                return message;
            }
        }
        throw new AssertionError("기대한 STOMP 메시지를 받지 못했습니다: " + description + ", observed=" + observed);
    }

    private void assertNoMatching(BlockingQueue<JsonNode> queue, Predicate<JsonNode> predicate)
            throws InterruptedException {
        long deadline = System.nanoTime() + DUPLICATE_CHECK_WINDOW.toNanos();
        List<JsonNode> duplicates = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            JsonNode message = queue.poll(50, TimeUnit.MILLISECONDS);
            if (message != null && predicate.test(message)) {
                duplicates.add(message);
            }
        }
        assertThat(duplicates).as("중복 STOMP 메시지").isEmpty();
    }

    private boolean sameRoomState(Node first, Node second, String roomCode) {
        return first.registry().find(roomCode).isPresent()
                && second.registry().find(roomCode).isPresent()
                && GameRoomState.from(first.registry().find(roomCode).orElseThrow())
                        .equals(GameRoomState.from(second.registry().find(roomCode).orElseThrow()));
    }

    private void awaitCondition(Check check, String description) {
        long deadline = System.nanoTime() + MESSAGE_TIMEOUT.toNanos();
        while (!check.matches() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("대기 중 인터럽트되었습니다: " + description, exception);
            }
        }
        assertThat(check.matches()).as(description).isTrue();
    }

    private static boolean isType(JsonNode message, String expectedType) {
        return message.path("type").asText().equals(expectedType);
    }

    private record Node(
            int port,
            GameLobbyService lobbyService,
            PromptPhaseService promptService,
            GameRoomStateSyncService stateSyncService,
            GameRegistry registry,
            GameRoomRepository repository,
            PlayerSessionRegistry playerSessions,
            GameDrainLifecycle drainLifecycle,
            ControlledImageGenerator imageGenerator,
            StringRedisTemplate redisTemplate
    ) {
    }

    private static final class PlayerSocket implements AutoCloseable {

        private final String playerId;
        private final WebSocketStompClient client;
        private final StompSession session;
        private final BlockingQueue<JsonNode> topicMessages = new LinkedBlockingQueue<>();
        private final BlockingQueue<JsonNode> imageGenerationMessages = new LinkedBlockingQueue<>();
        private final BlockingQueue<JsonNode> roomStateMessages = new LinkedBlockingQueue<>();

        private PlayerSocket(String playerId, WebSocketStompClient client, StompSession session) {
            this.playerId = playerId;
            this.client = client;
            this.session = session;
        }

        private String playerId() {
            return playerId;
        }

        private BlockingQueue<JsonNode> topicMessages() {
            return topicMessages;
        }

        private BlockingQueue<JsonNode> imageGenerationMessages() {
            return imageGenerationMessages;
        }

        private BlockingQueue<JsonNode> roomStateMessages() {
            return roomStateMessages;
        }

        private void send(String destination, Object payload) {
            session.send(destination, payload);
        }

        private void clear() {
            topicMessages.clear();
            imageGenerationMessages.clear();
            roomStateMessages.clear();
        }

        @Override
        public void close() {
            if (session.isConnected()) {
                session.disconnect();
            }
            client.stop();
        }
    }

    private static final class JsonNodeFrameHandler implements StompFrameHandler {

        private final BlockingQueue<JsonNode> messages;

        private JsonNodeFrameHandler(BlockingQueue<JsonNode> messages) {
            this.messages = messages;
        }

        @Override
        public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
            return JsonNode.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            messages.add((JsonNode) payload);
        }
    }

    private static final class ControlledImageGenerator implements ImageGenerator {

        private final BlockingQueue<PendingGeneration> pendingGenerations = new LinkedBlockingQueue<>();

        @Override
        public GeneratedImage generate(ImageGenerationRequest request) {
            PendingGeneration pending = new PendingGeneration(request, new java.util.concurrent.CompletableFuture<>());
            pendingGenerations.add(pending);
            try {
                return pending.result().get(2, TimeUnit.MINUTES);
            } catch (Exception exception) {
                throw new IllegalStateException("테스트 이미지 생성이 완료되지 않았습니다.", exception);
            }
        }

        private PendingGeneration awaitNextGeneration() throws InterruptedException {
            PendingGeneration pending = pendingGenerations.poll(MESSAGE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (pending == null) {
                throw new AssertionError("A에서 이미지 생성 요청이 시작되지 않았습니다.");
            }
            return pending;
        }

        private record PendingGeneration(
                ImageGenerationRequest request,
                java.util.concurrent.CompletableFuture<GeneratedImage> result
        ) {
            private void complete(GeneratedImage image) {
                result.complete(image);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDependencies {

        @Bean
        @Primary
        ControlledImageGenerator controlledImageGenerator() {
            return new ControlledImageGenerator();
        }

        @Bean
        @Primary
        ImageStorageClient imageStorageClient() {
            return (image, contentType) -> "https://images.test/generated.png";
        }
    }

    @FunctionalInterface
    private interface Check {

        boolean matches();
    }
}
