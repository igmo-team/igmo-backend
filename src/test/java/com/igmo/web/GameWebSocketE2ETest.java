package com.igmo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igmo.imagegeneration.GeneratedImage;
import com.igmo.imagegeneration.ImageGenerator;
import com.igmo.support.websocketdocs.AsyncApiGenerator;
import com.igmo.support.websocketdocs.WebSocketSnippetWriter;
import com.igmo.web.dto.CreateGameRequest;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.ErrorResponse;
import com.igmo.web.dto.GameResultSnapshot;
import com.igmo.web.dto.GuessRequest;
import com.igmo.web.dto.GuessSubmissionSnapshot;
import com.igmo.web.dto.ImageGenerationEvent;
import com.igmo.web.dto.JoinGameRequest;
import com.igmo.web.dto.JoinGameResponse;
import com.igmo.web.dto.LobbySnapshot;
import com.igmo.web.dto.OwnVoteOptionNotice;
import com.igmo.web.dto.PromptRequest;
import com.igmo.web.dto.PromptSubmissionSnapshot;
import com.igmo.web.dto.ReadyRequest;
import com.igmo.web.dto.RoomMessage;
import com.igmo.web.dto.RoomMessageType;
import com.igmo.web.dto.RoundResultSnapshot;
import com.igmo.web.dto.RoundSnapshot;
import com.igmo.web.dto.VoteRequest;
import com.igmo.web.dto.VoteSnapshot;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "igmo.game.prompt-duration=30s",
                "igmo.game.guess-duration=30s",
                "igmo.game.vote-duration=30s",
                "igmo.game.result-duration=100ms",
                "igmo.game.image-generation-completion-delay=10ms"
        }
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GameWebSocketE2ETest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final Path SNIPPET_DIRECTORY = Path.of("build", "generated-snippets", "websocket");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ImageGenerator imageGenerator;

    @MockitoBean
    private com.igmo.service.ImageStorageClient imageStorageClient;

    @LocalServerPort
    private int port;

    @BeforeAll
    void clearOldWebSocketSnippets() throws IOException {
        if (!Files.exists(SNIPPET_DIRECTORY)) {
            return;
        }
        try (var paths = Files.walk(SNIPPET_DIRECTORY)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::delete);
        }
    }

    @BeforeEach
    void stubImageGeneration() {
        given(imageGenerator.generate(any())).willReturn(new GeneratedImage(new byte[]{1}, "image/png"));
        given(imageStorageClient.store(any(), any())).willReturn("https://example.com/generated/image.png");
    }

    @AfterAll
    void generateAsyncApiDocument() throws Exception {
        Path asyncApiPath = new AsyncApiGenerator(objectMapper).generate();
        JsonNode document = objectMapper.readTree(asyncApiPath.toFile());

        assertThat(document.path("asyncapi").asText()).isEqualTo("3.1.0");
        assertThat(document.path("servers").path("websocket").path("description").asText())
                .isEqualTo("WebSocket endpoint: `" + System.getProperty("websocket.docs.server-url",
                        "ws://localhost:8080/ws") + "`");
        assertThat(document.path("operations").path("sendChangeReady").path("action").asText()).isEqualTo("send");
        assertThat(document.path("operations").path("sendStartGame").path("action").asText()).isEqualTo("send");
        assertThat(document.path("operations").path("sendSubmitPrompt").path("action").asText()).isEqualTo("send");
        assertThat(document.path("operations").path("sendSubmitGuess").path("action").asText()).isEqualTo("send");
        assertThat(document.path("operations").path("sendSubmitVote").path("action").asText()).isEqualTo("send");
        JsonNode roomTopic = document.path("channels").path("topicTopicRoomsRoomCode");
        assertThat(roomTopic.path("address").asText()).isEqualTo("/topic/rooms/{roomCode}");
        assertThat(roomTopic.path("messages").size()).isEqualTo(RoomMessageType.values().length);
        assertThat(document.path("operations").path("receiveTopicTopicRoomsRoomCode").path("action").asText())
                .isEqualTo("receive");
        assertThat(document.path("operations").path("receiveUserUserQueueImageGeneration").path("action").asText())
                .isEqualTo("receive");
        assertThat(document.path("components").path("messages").has("GameResultSnapshotMessage")).isTrue();
        assertThat(document.path("components").path("messages").has("ErrorMessage")).isTrue();
        assertThat(enumValues(
                document.at("/components/schemas/GuessSubmissionRejectedMessageSchema/properties/status/enum")))
                .containsExactly("REJECTED");
        assertThat(
                enumValues(document.at("/components/schemas/ImageGenerationReadyMessageSchema/properties/status/enum")))
                .containsExactly("READY");
        assertThat(enumValues(document.at("/components/schemas/RoundSnapshotMessageSchema/properties/type/enum")))
                .containsExactly("ROUND_SNAPSHOT");
        assertThat(enumValues(document.at("/components/schemas/LobbySnapshotMessageSchema/properties/type/enum")))
                .containsExactly("LOBBY_SNAPSHOT");
        assertThat(enumValues(
                document.at("/components/schemas/PromptSubmissionSnapshotMessageSchema/properties/type/enum")))
                .containsExactly("PROMPT_SUBMISSION_SNAPSHOT");
        assertThat(enumValues(document.at("/components/schemas/VoteSnapshotMessageSchema/properties/type/enum")))
                .containsExactly("VOTE_SNAPSHOT");
        assertThat(enumValues(document.at("/components/schemas/RoundResultSnapshotMessageSchema/properties/type/enum")))
                .containsExactly("ROUND_RESULT_SNAPSHOT");
        assertThat(enumValues(document.at(
                "/components/schemas/RoundResultSnapshotMessageSchema/properties/payload/properties/voteSkippedReason/enum")))
                .containsExactly("ALL_PERFECT", "null");
        assertThat(enumValues(document.at("/components/schemas/GameResultSnapshotMessageSchema/properties/type/enum")))
                .containsExactly("GAME_RESULT_SNAPSHOT");
    }

    @Test
    @DisplayName("준비 상태를 전송하면 LOBBY_SNAPSHOT을 문서화한다.")
    void changeReady_로비스냅샷을문서화한다() throws Exception {
        // given
        GameScenario scenario = createScenario();
        try {
            PlayerConnection guest = scenario.players().get(1);
            scenario.clearTopic();

            // when
            ReadyRequest request = new ReadyRequest(true);
            guest.session().send(sendDestination(scenario, "ready"), request);

            // then
            JsonNode lobby = awaitTopic(scenario, RoomMessageType.LOBBY_SNAPSHOT.name());
            assertThat(lobby.path("payload").path("players"))
                    .anySatisfy(player -> assertThat(player.path("id").asText()).isEqualTo(guest.playerId()));

            writeSnippet("change-ready", snippet(
                    "changeReady", "준비 상태 변경", List.of("lobby"),
                    "게임 시작 전, 현재 플레이어의 준비 상태를 바꿀 때 보냅니다.",
                    request("/app/rooms/{roomCode}/ready", "ReadyRequest", "플레이어의 준비 상태를 변경합니다.", request),
                    triggered("LobbySnapshotMessage", "LOBBY_SNAPSHOT", "/topic/rooms/{roomCode}", "BROADCAST",
                            "직접 응답", "최신 로비 상태", "로비 UI의 플레이어 준비 상태를 최신 payload로 갱신합니다.", lobby,
                            List.of("lobby"), roomMessage(LobbySnapshot.class))
            ));
        } finally {
            scenario.close();
        }
    }

    @Test
    @DisplayName("게임 시작 요청을 전송하면 PROMPT_SUBMISSION_SNAPSHOT을 문서화한다.")
    void startGame_프롬프트제출스냅샷을문서화한다() throws Exception {
        // given
        GameScenario scenario = createScenario();
        try {
            readyGuests(scenario);
            scenario.clearTopic();

            // when
            scenario.host().session().send(sendDestination(scenario, "start"), null);

            // then
            JsonNode promptSnapshot = awaitTopic(scenario, RoomMessageType.PROMPT_SUBMISSION_SNAPSHOT.name());
            assertThat(promptSnapshot.path("payload").path("phase").asText()).isEqualTo("GENERATING");

            writeSnippet("start-game", snippet(
                    "startGame", "게임 시작", List.of("lobby", "prompt"),
                    "방장만 보냅니다. 최소 인원이 충족되고 다른 플레이어가 준비 완료한 뒤 보냅니다.",
                    request("/app/rooms/{roomCode}/start", "StartRequest", "게임을 시작합니다. 요청 body가 없습니다.", null),
                    triggered("PromptSubmissionSnapshotMessage", "PROMPT_SUBMISSION_SNAPSHOT",
                            "/topic/rooms/{roomCode}", "BROADCAST",
                            "DIRECT", "프롬프트 제출 단계 상태", "프롬프트 입력 화면과 마감 시각을 표시합니다.", promptSnapshot,
                            List.of("prompt"), roomMessage(PromptSubmissionSnapshot.class))
            ));
        } finally {
            scenario.close();
        }
    }

    @Test
    @DisplayName("프롬프트 전송으로 이미지 생성과 라운드 시작 메시지를 문서화한다.")
    void submitPrompt_이미지생성과후속라운드를문서화한다() throws Exception {
        // given
        GameScenario scenario = prepareGeneratingScenario();
        try {
            submitPromptAndAwaitReady(scenario, scenario.players().get(1), "guest-one prompt");
            submitPromptAndAwaitReady(scenario, scenario.players().get(2), "guest-two prompt");
            scenario.clearAllQueues();
            PlayerConnection host = scenario.host();
            PromptRequest request = new PromptRequest("host prompt");

            // when
            host.session().send(sendDestination(scenario, "prompts"), request);

            // then
            JsonNode generating = awaitMessage(host.imageGenerationMessages(), message ->
                    message.path("status").asText().equals("GENERATING"), "image GENERATING");
            JsonNode ready = awaitMessage(host.imageGenerationMessages(), message ->
                    message.path("status").asText().equals("READY"), "image READY");
            JsonNode promptSnapshot = awaitTopic(scenario, RoomMessageType.PROMPT_SUBMISSION_SNAPSHOT.name());
            JsonNode roundSnapshot = awaitTopic(scenario, RoomMessageType.ROUND_SNAPSHOT.name());
            assertThat(roundSnapshot.path("payload").path("phase").asText()).isEqualTo("PLAYING");

            writeSnippet("submit-prompt", snippet(
                    "submitPrompt", "프롬프트 제출", List.of("prompt"),
                    "게임이 프롬프트 입력 단계일 때 현재 플레이어의 이미지 생성 프롬프트를 보냅니다.",
                    request("/app/rooms/{roomCode}/prompts", "PromptRequest", "이미지 생성에 사용할 프롬프트를 제출합니다.", request),
                    triggered("ImageGenerationGeneratingMessage", "GENERATING", "/user/queue/image-generation", "USER",
                            "DIRECT", "이미지 생성 시작 상태", "현재 플레이어의 이미지 생성 중 상태를 표시합니다.", generating, List.of("prompt"),
                            payload(ImageGenerationEvent.class)),
                    triggered("ImageGenerationReadyMessage", "READY", "/user/queue/image-generation", "USER",
                            "FOLLOW_UP", "이미지 생성 완료 상태", "이미지 URL을 사용해 현재 플레이어의 생성 결과를 표시합니다.", ready,
                            List.of("prompt"), payload(ImageGenerationEvent.class)),
                    triggered("PromptSubmissionSnapshotMessage", "PROMPT_SUBMISSION_SNAPSHOT",
                            "/topic/rooms/{roomCode}", "BROADCAST",
                            "FOLLOW_UP", "프롬프트 제출 상태", "모든 플레이어의 프롬프트·이미지 상태를 최신 payload로 갱신합니다.", promptSnapshot,
                            List.of("prompt"), roomMessage(PromptSubmissionSnapshot.class)),
                    triggered("RoundSnapshotMessage", "ROUND_SNAPSHOT", "/topic/rooms/{roomCode}", "BROADCAST",
                            "FOLLOW_UP", "첫 라운드 시작 상태", "이미지 생성이 모두 완료된 뒤 라운드 추측 UI를 표시합니다.", roundSnapshot,
                            List.of("guess"), roomMessage(RoundSnapshot.class))
            ));
        } finally {
            scenario.close();
        }
    }

    @Test
    @DisplayName("이미지 생성 실패 응답을 실제 개인 큐에서 문서화한다.")
    void submitPrompt_이미지생성실패를문서화한다() throws Exception {
        // given
        given(imageGenerator.generate(any())).willThrow(new IllegalStateException("이미지 생성 실패"));
        GameScenario scenario = prepareGeneratingScenario();
        try {
            PlayerConnection host = scenario.host();
            PromptRequest request = new PromptRequest("failing prompt");
            scenario.clearAllQueues();

            // when
            host.session().send(sendDestination(scenario, "prompts"), request);

            // then
            JsonNode generating = awaitMessage(host.imageGenerationMessages(), message ->
                    message.path("status").asText().equals("GENERATING"), "image GENERATING");
            JsonNode failed = awaitMessage(host.imageGenerationMessages(), message ->
                    message.path("status").asText().equals("FAILED"), "image FAILED");
            assertThat(failed.path("errorMessage").asText()).isEqualTo("이미지 생성 실패");

            writeSnippet("submit-prompt-failed", snippet(
                    "submitPrompt", "프롬프트 제출", List.of("prompt"),
                    "게임이 프롬프트 입력 단계일 때 현재 플레이어의 이미지 생성 프롬프트를 보냅니다.",
                    request("/app/rooms/{roomCode}/prompts", "PromptRequest", "이미지 생성에 사용할 프롬프트를 제출합니다.", request),
                    triggered("ImageGenerationGeneratingMessage", "GENERATING", "/user/queue/image-generation", "USER",
                            "DIRECT", "이미지 생성 시작 상태", "현재 플레이어의 이미지 생성 중 상태를 표시합니다.", generating, List.of("prompt"),
                            payload(ImageGenerationEvent.class)),
                    triggered("ImageGenerationFailedMessage", "FAILED", "/user/queue/image-generation", "USER",
                            "FOLLOW_UP", "이미지 생성 실패 상태", "errorMessage를 표시하고 재시도 또는 안내 UI를 제공합니다.", failed,
                            List.of("prompt", "error"), payload(ImageGenerationEvent.class))
            ));
        } finally {
            scenario.close();
        }
    }

    @Test
    @DisplayName("추측 전송으로 개인 결과와 방 상태를 문서화한다.")
    void submitGuess_개인결과와방상태를문서화한다() throws Exception {
        // given
        PlayingScenario playing = preparePlayingScenario();
        GameScenario scenario = playing.scenario();
        try {
            PlayerConnection guesser = firstNonQuestioner(scenario, playing.roundSnapshot());
            GuessRequest request = new GuessRequest("normal guess");
            scenario.clearAllQueues();

            // when
            guesser.session().send(sendDestination(scenario, "guesses"), request);

            // then
            JsonNode submitted = awaitMessage(guesser.guessSubmissionMessages(), message ->
                    message.path("status").asText().equals("SUBMITTED"), "guess SUBMITTED");
            JsonNode round = awaitTopic(scenario, RoomMessageType.ROUND_SNAPSHOT.name());
            assertThat(round.path("payload").path("guessEntries")).isNotEmpty();

            writeSnippet("submit-guess", snippet(
                    "submitGuess", "추측 제출", List.of("guess"),
                    "라운드 추측 단계에서 출제자가 아닌 플레이어가 추측 문장을 보냅니다.",
                    request("/app/rooms/{roomCode}/guesses", "GuessRequest", "현재 라운드의 추측을 제출합니다.", request),
                    triggered("GuessSubmissionSubmittedMessage", "SUBMITTED", "/user/queue/guess-submission", "USER",
                            "DIRECT", "추측 제출 결과", "현재 플레이어의 제출 완료 상태를 표시하고 중복 전송을 막습니다.", submitted,
                            List.of("guess"), payload(GuessSubmissionSnapshot.class)),
                    triggered("RoundSnapshotMessage", "ROUND_SNAPSHOT", "/topic/rooms/{roomCode}", "BROADCAST",
                            "DIRECT", "라운드 추측 진행 상태", "다른 플레이어의 제출 여부를 최신 payload로 갱신합니다.", round, List.of("guess"),
                            roomMessage(RoundSnapshot.class))
            ));
        } finally {
            scenario.close();
        }
    }

    @Test
    @DisplayName("추측의 상태별 개인 응답과 투표 전환을 문서화한다.")
    void submitGuess_상태별개인응답과투표전환을문서화한다() throws Exception {
        // given
        PlayingScenario playing = preparePlayingScenario();
        GameScenario scenario = playing.scenario();
        try {
            PlayerConnection first = firstNonQuestioner(scenario, playing.roundSnapshot());
            PlayerConnection second = otherNonQuestioner(scenario, playing.roundSnapshot(), first);
            String answer = promptForQuestioner(scenario, playing.roundSnapshot());
            scenario.clearAllQueues();

            first.session().send(sendDestination(scenario, "guesses"), new GuessRequest("duplicate guess"));
            awaitMessage(first.guessSubmissionMessages(),
                    message -> message.path("status").asText().equals("SUBMITTED"), "initial guess");
            first.session().send(sendDestination(scenario, "guesses"), new GuessRequest("duplicate guess"));
            JsonNode rejected = awaitMessage(first.guessSubmissionMessages(), message ->
                    message.path("status").asText().equals("REJECTED"), "guess REJECTED");

            second.session().send(sendDestination(scenario, "guesses"), new GuessRequest(answer));
            JsonNode perfect = awaitMessage(second.guessSubmissionMessages(), message ->
                    message.path("status").asText().equals("PERFECT_RETRY_REQUIRED"), "guess PERFECT_RETRY_REQUIRED");
            second.session().send(sendDestination(scenario, "guesses"), new GuessRequest("last normal guess"));
            JsonNode vote = awaitTopic(scenario, RoomMessageType.VOTE_SNAPSHOT.name());
            JsonNode ownVoteOption = awaitMessage(first.ownVoteOptionMessages(), ignored -> true, "own vote option");

            writeSnippet("submit-guess-statuses", snippet(
                    "submitGuess", "추측 제출", List.of("guess", "vote"),
                    "라운드 추측 단계에서 출제자가 아닌 플레이어가 추측 문장을 보냅니다.",
                    request("/app/rooms/{roomCode}/guesses", "GuessRequest", "현재 라운드의 추측을 제출합니다.",
                            new GuessRequest("duplicate guess")),
                    triggered("GuessSubmissionRejectedMessage", "REJECTED", "/user/queue/guess-submission", "USER",
                            "DIRECT", "추측 거절 결과", "message를 표시하고 입력을 수정해 다시 제출할 수 있게 합니다.", rejected, List.of("guess"),
                            payload(GuessSubmissionSnapshot.class)),
                    triggered("GuessSubmissionPerfectMessage", "PERFECT_RETRY_REQUIRED", "/user/queue/guess-submission",
                            "USER",
                            "DIRECT", "정답 추측 결과", "확정 점수를 표시하고 안내에 따라 다른 추측을 다시 제출하게 합니다.", perfect, List.of("guess"),
                            payload(GuessSubmissionSnapshot.class)),
                    triggered("VoteSnapshotMessage", "VOTE_SNAPSHOT", "/topic/rooms/{roomCode}", "BROADCAST",
                            "FOLLOW_UP", "투표 시작 상태", "투표 보기와 마감 시각을 표시합니다.", vote, List.of("vote"),
                            roomMessage(VoteSnapshot.class)),
                    triggered("OwnVoteOptionNoticeMessage", "OWN_VOTE_OPTION", "/user/queue/vote-own-option", "USER",
                            "FOLLOW_UP", "본인 투표 보기 안내", "voteAllowed와 optionId에 따라 선택 불가 보기를 처리합니다.", ownVoteOption,
                            List.of("vote"), payload(OwnVoteOptionNotice.class))
            ));
        } finally {
            scenario.close();
        }
    }

    @Test
    @DisplayName("투표 전송으로 진행 상태와 라운드 결과를 문서화한다.")
    void submitVote_진행상태와라운드결과를문서화한다() throws Exception {
        // given
        VotingScenario voting = prepareVotingScenario();
        GameScenario scenario = voting.scenario();
        try {
            PlayerConnection first = voting.voters().getFirst();
            PlayerConnection second = voting.voters().get(1);
            VoteRequest firstRequest = new VoteRequest(selectVoteOption(voting, first));
            VoteRequest secondRequest = new VoteRequest(selectVoteOption(voting, second));
            scenario.clearTopic();

            first.session().send(sendDestination(scenario, "votes"), firstRequest);
            JsonNode voteSnapshot = awaitTopic(scenario, RoomMessageType.VOTE_SNAPSHOT.name());
            second.session().send(sendDestination(scenario, "votes"), secondRequest);
            JsonNode result = awaitTopic(scenario, RoomMessageType.ROUND_RESULT_SNAPSHOT.name());
            assertThat(result.path("payload").path("phase").asText()).isEqualTo("RESULTS");

            writeSnippet("submit-vote", snippet(
                    "submitVote", "투표 제출", List.of("vote", "result"),
                    "투표 단계에서 voteAllowed가 true인 현재 플레이어가 본인 보기가 아닌 optionId를 보냅니다.",
                    request("/app/rooms/{roomCode}/votes", "VoteRequest", "투표할 보기의 optionId를 제출합니다.", firstRequest),
                    triggered("VoteSnapshotMessage", "VOTE_SNAPSHOT", "/topic/rooms/{roomCode}", "BROADCAST",
                            "DIRECT", "투표 진행 상태", "투표 완료 수를 최신 payload로 갱신합니다.", voteSnapshot, List.of("vote"),
                            roomMessage(VoteSnapshot.class)),
                    triggered("RoundResultSnapshotMessage", "ROUND_RESULT_SNAPSHOT", "/topic/rooms/{roomCode}",
                            "BROADCAST",
                            "DIRECT", "라운드 결과", "정답, 득표, 점수와 투표 생략 사유를 payload로 갱신합니다.", result, List.of("result"),
                            roomMessage(RoundResultSnapshot.class))
            ));
        } finally {
            scenario.close();
        }
    }

    @Test
    @DisplayName("마지막 라운드 결과 뒤 GAME_RESULT_SNAPSHOT을 문서화한다.")
    void finalRound_게임결과후속이벤트를문서화한다() throws Exception {
        // given
        PlayingScenario playing = preparePlayingScenario();
        GameScenario scenario = playing.scenario();
        try {
            JsonNode currentRound = playing.roundSnapshot();
            JsonNode gameResult = null;
            VoteRequest lastVoteRequest = null;
            for (int round = 1; round <= 3; round++) {
                VotingScenario voting = completeGuessesForVoting(scenario, currentRound);
                lastVoteRequest = submitAllVotes(scenario, voting);
                awaitTopic(scenario, RoomMessageType.ROUND_RESULT_SNAPSHOT.name());
                gameResult = awaitTopicOrNextRound(scenario);
                if (gameResult.path("type").asText().equals(RoomMessageType.GAME_RESULT_SNAPSHOT.name())) {
                    break;
                }
                currentRound = gameResult;
            }
            assertThat(gameResult).isNotNull();
            assertThat(gameResult.path("type").asText()).isEqualTo(RoomMessageType.GAME_RESULT_SNAPSHOT.name());

            writeSnippet("game-result-follow-up", snippet(
                    "submitVote", "투표 제출", List.of("vote", "result"),
                    "투표 단계에서 voteAllowed가 true인 현재 플레이어가 본인 보기가 아닌 optionId를 보냅니다.",
                    request("/app/rooms/{roomCode}/votes", "VoteRequest", "투표할 보기의 optionId를 제출합니다.", lastVoteRequest),
                    triggered("GameResultSnapshotMessage", "GAME_RESULT_SNAPSHOT", "/topic/rooms/{roomCode}",
                            "BROADCAST",
                            "FOLLOW_UP", "게임 최종 결과", "마지막 라운드 결과 시간이 지난 뒤 최종 순위 화면으로 전환합니다.", gameResult,
                            List.of("result"), roomMessage(GameResultSnapshot.class))
            ));
        } finally {
            scenario.close();
        }
    }

    @Test
    @DisplayName("게임 시작 조건 불충족 오류를 개인 오류 큐에서 문서화한다.")
    void startGame_오류응답을문서화한다() throws Exception {
        // given
        GameScenario scenario = createSinglePlayerScenario();
        try {
            scenario.host().session().send(sendDestination(scenario, "start"), null);

            // then
            JsonNode error = awaitMessage(scenario.host().errorMessages(), ignored -> true, "start error");
            assertThat(error.path("message").asText()).contains("최소");

            writeSnippet("start-game-error", snippet(
                    "startGame", "게임 시작", List.of("lobby", "prompt", "error"),
                    "방장만 보냅니다. 최소 인원이 충족되고 다른 플레이어가 준비 완료한 뒤 보냅니다.",
                    request("/app/rooms/{roomCode}/start", "StartRequest", "게임을 시작합니다. 요청 body가 없습니다.", null),
                    triggered("ErrorMessage", "ERROR", "/user/queue/errors", "USER",
                            "DIRECT", "게임 시작 오류", "message를 표시하고 현재 상태에서 가능한 행동을 유지합니다.", error, List.of("error"),
                            payload(ErrorResponse.class))
            ));
        } finally {
            scenario.close();
        }
    }

    private GameScenario prepareGeneratingScenario() throws Exception {
        GameScenario scenario = createScenario();
        readyGuests(scenario);
        scenario.clearTopic();
        scenario.host().session().send(sendDestination(scenario, "start"), null);
        awaitTopic(scenario, RoomMessageType.PROMPT_SUBMISSION_SNAPSHOT.name());
        scenario.clearAllQueues();
        return scenario;
    }

    private PlayingScenario preparePlayingScenario() throws Exception {
        GameScenario scenario = prepareGeneratingScenario();
        try {
            for (PlayerConnection player : scenario.players()) {
                String prompt = promptOf(player);
                scenario.promptsByPlayerId().put(player.playerId(), prompt);
                submitPromptAndAwaitReady(scenario, player, prompt);
            }
            JsonNode round = awaitTopic(scenario, RoomMessageType.ROUND_SNAPSHOT.name());
            scenario.clearAllQueues();
            return new PlayingScenario(scenario, round);
        } catch (Exception exception) {
            scenario.close();
            throw exception;
        }
    }

    private VotingScenario prepareVotingScenario() throws Exception {
        PlayingScenario playing = preparePlayingScenario();
        try {
            return completeGuessesForVoting(playing.scenario(), playing.roundSnapshot());
        } catch (Exception exception) {
            playing.scenario().close();
            throw exception;
        }
    }

    private VotingScenario completeGuessesForVoting(GameScenario scenario, JsonNode roundSnapshot) throws Exception {
        List<PlayerConnection> guessers = nonQuestioners(scenario, roundSnapshot);
        scenario.clearAllQueues();
        for (int index = 0; index < guessers.size(); index++) {
            PlayerConnection guesser = guessers.get(index);
            guesser.session().send(sendDestination(scenario, "guesses"), new GuessRequest("round guess " + index));
        }
        JsonNode vote = awaitTopic(scenario, RoomMessageType.VOTE_SNAPSHOT.name());
        Map<String, JsonNode> notices = new HashMap<>();
        for (PlayerConnection player : scenario.players()) {
            notices.put(player.playerId(),
                    awaitMessage(player.ownVoteOptionMessages(), ignored -> true, "own vote option"));
        }
        List<PlayerConnection> voters = scenario.players().stream()
                .filter(player -> notices.get(player.playerId()).path("voteAllowed").asBoolean())
                .toList();
        assertThat(voters).hasSize(2);
        return new VotingScenario(scenario, vote, notices, voters);
    }

    private VoteRequest submitAllVotes(GameScenario scenario, VotingScenario voting) throws Exception {
        scenario.clearTopic();
        VoteRequest lastVoteRequest = null;
        for (PlayerConnection voter : voting.voters()) {
            lastVoteRequest = new VoteRequest(selectVoteOption(voting, voter));
            voter.session().send(sendDestination(scenario, "votes"), lastVoteRequest);
        }
        return lastVoteRequest;
    }

    private JsonNode awaitTopicOrNextRound(GameScenario scenario) throws Exception {
        return awaitMessage(scenario.host().topicMessages(), message -> {
            String type = message.path("type").asText();
            return type.equals(RoomMessageType.ROUND_SNAPSHOT.name()) || type.equals(
                    RoomMessageType.GAME_RESULT_SNAPSHOT.name());
        }, "next round or game result");
    }

    private void submitPromptAndAwaitReady(GameScenario scenario, PlayerConnection player, String prompt)
            throws Exception {
        player.session().send(sendDestination(scenario, "prompts"), new PromptRequest(prompt));
        awaitMessage(player.imageGenerationMessages(), message -> message.path("status").asText().equals("READY"),
                "image READY");
    }

    private void readyGuests(GameScenario scenario) throws Exception {
        for (PlayerConnection player : scenario.players().subList(1, scenario.players().size())) {
            player.session().send(sendDestination(scenario, "ready"), new ReadyRequest(true));
            awaitTopic(scenario, RoomMessageType.LOBBY_SNAPSHOT.name());
        }
        scenario.clearTopic();
    }

    private PlayerConnection firstNonQuestioner(GameScenario scenario, JsonNode roundSnapshot) {
        return nonQuestioners(scenario, roundSnapshot).getFirst();
    }

    private PlayerConnection otherNonQuestioner(GameScenario scenario, JsonNode roundSnapshot,
                                                PlayerConnection player) {
        return nonQuestioners(scenario, roundSnapshot).stream()
                .filter(candidate -> !candidate.playerId().equals(player.playerId()))
                .findFirst()
                .orElseThrow();
    }

    private List<PlayerConnection> nonQuestioners(GameScenario scenario, JsonNode roundSnapshot) {
        String questionerId = roundSnapshot.path("payload").path("questioner").path("id").asText();
        return scenario.players().stream().filter(player -> !player.playerId().equals(questionerId)).toList();
    }

    private String promptForQuestioner(GameScenario scenario, JsonNode roundSnapshot) {
        String questionerId = roundSnapshot.path("payload").path("questioner").path("id").asText();
        return scenario.promptsByPlayerId().get(questionerId);
    }

    private String selectVoteOption(VotingScenario voting, PlayerConnection voter) {
        String ownOptionId = voting.notices().get(voter.playerId()).path("optionId").asText();
        return voting.voteSnapshot().path("payload").path("voteOptions").findValues("optionId").stream()
                .map(JsonNode::asText)
                .filter(optionId -> !optionId.equals(ownOptionId))
                .findFirst()
                .orElseThrow();
    }

    private GameScenario createScenario() throws Exception {
        CreateGameResponse host = createGame("host");
        JoinGameResponse guestOne = joinGame(host.roomCode(), "guest-one");
        JoinGameResponse guestTwo = joinGame(host.roomCode(), "guest-two");
        return connectScenario(host, guestOne, guestTwo);
    }

    private GameScenario createSinglePlayerScenario() throws Exception {
        CreateGameResponse host = createGame("host");
        return connectScenario(host);
    }

    private GameScenario connectScenario(CreateGameResponse host, JoinGameResponse... guests) throws Exception {
        List<PlayerConnection> players = new ArrayList<>();
        players.add(connect(host.roomCode(), host.playerId(), host.secret(), "host"));
        for (int index = 0; index < guests.length; index++) {
            JoinGameResponse guest = guests[index];
            players.add(connect(host.roomCode(), guest.playerId(), guest.secret(), "guest-" + index));
        }
        return new GameScenario(host.roomCode(), players, new HashMap<>());
    }

    private CreateGameResponse createGame(String nickname) {
        ResponseEntity<CreateGameResponse> response = restTemplate.postForEntity(
                "/games", new CreateGameRequest(nickname), CreateGameResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private JoinGameResponse joinGame(String roomCode, String nickname) {
        ResponseEntity<JoinGameResponse> response = restTemplate.postForEntity(
                "/games/" + roomCode + "/players", new JoinGameRequest(nickname), JoinGameResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private PlayerConnection connect(String roomCode, String playerId, String secret, String nickname)
            throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders headers = new StompHeaders();
        headers.add(PlayerSessionInterceptor.ROOM_CODE_ATTRIBUTE, roomCode);
        headers.add(PlayerSessionInterceptor.PLAYER_ID_ATTRIBUTE, playerId);
        headers.add(PlayerSessionInterceptor.SECRET_HEADER, secret);
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                        headers,
                        new StompSessionHandlerAdapter() {
                        })
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        PlayerConnection connection = new PlayerConnection(playerId, nickname, client, session);
        session.subscribe("/topic/rooms/" + roomCode, new JsonNodeStompFrameHandler(connection.topicMessages()));
        session.subscribe("/user/queue/image-generation",
                new JsonNodeStompFrameHandler(connection.imageGenerationMessages()));
        session.subscribe("/user/queue/guess-submission",
                new JsonNodeStompFrameHandler(connection.guessSubmissionMessages()));
        session.subscribe("/user/queue/vote-own-option",
                new JsonNodeStompFrameHandler(connection.ownVoteOptionMessages()));
        session.subscribe("/user/queue/errors", new JsonNodeStompFrameHandler(connection.errorMessages()));
        return connection;
    }

    private JsonNode awaitTopic(GameScenario scenario, String type) throws Exception {
        return awaitMessage(scenario.host().topicMessages(), message -> message.path("type").asText().equals(type),
                type);
    }

    private JsonNode awaitMessage(BlockingQueue<JsonNode> queue, java.util.function.Predicate<JsonNode> predicate,
                                  String expected)
            throws Exception {
        List<JsonNode> observed = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
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
        throw new AssertionError("기대한 STOMP frame을 받지 못했습니다: " + expected + ", observed=" + observed);
    }

    private String sendDestination(GameScenario scenario, String action) {
        return "/app/rooms/" + scenario.roomCode() + "/" + action;
    }

    private ObjectNode snippet(
            String operationId,
            String title,
            List<String> tags,
            String whenToSend,
            ObjectNode request,
            ObjectNode... messages
    ) {
        ObjectNode snippet = objectMapper.createObjectNode();
        snippet.put("operationId", operationId);
        snippet.put("title", title);
        snippet.put("whenToSend", whenToSend);
        ArrayNode tagNodes = snippet.putArray("tags");
        tags.forEach(tagNodes::add);
        ObjectNode connection = snippet.putObject("connection");
        connection.put("endpoint", "/ws");
        ObjectNode headers = snippet.putObject("connectHeaders");
        headers.put("roomCode", "<roomCode>");
        headers.put("playerId", "<playerId>");
        headers.put("secret", "<player-secret>");
        snippet.set("request", request);
        ArrayNode triggered = snippet.putArray("triggeredMessages");
        for (ObjectNode message : messages) {
            triggered.add(message);
        }
        return snippet;
    }

    private ObjectNode request(String destination, String messageId, String description, Object payload) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("destination", destination);
        request.put("messageId", messageId);
        request.put("description", description);
        if (payload != null) {
            request.set("example", objectMapper.valueToTree(payload));
        }
        return request;
    }

    private ObjectNode triggered(
            String messageId,
            String messageType,
            String destination,
            String scope,
            String relationship,
            String description,
            String clientAction,
            JsonNode frame,
            List<String> tags,
            PayloadType payloadType
    ) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("messageId", messageId);
        message.put("title", messageType);
        message.put("destination", destination);
        message.put("scope", scope);
        message.put("relationship", relationship);
        message.put("description", description);
        message.put("clientAction", clientAction);
        ArrayNode tagNodes = message.putArray("tags");
        tags.forEach(tagNodes::add);
        message.set("example", frame.deepCopy());
        ObjectNode payloadTypeNode = message.putObject("payloadType");
        payloadTypeNode.put("rawType", payloadType.rawType().getName());
        if (payloadType.typeArgument() != null) {
            payloadTypeNode.put("typeArgument", payloadType.typeArgument().getName());
        }
        return message;
    }

    private static PayloadType payload(Class<?> rawType) {
        return new PayloadType(rawType, null);
    }

    private static PayloadType roomMessage(Class<?> payloadType) {
        return new PayloadType(RoomMessage.class, payloadType);
    }

    private List<String> enumValues(JsonNode enumNode) {
        List<String> values = new ArrayList<>();
        enumNode.forEach(value -> values.add(value.asText()));
        return values;
    }

    private void writeSnippet(String fileName, ObjectNode snippet) throws IOException {
        Path path = new WebSocketSnippetWriter(objectMapper).write(fileName, snippet);
        assertThat(path).exists();
    }

    private String promptOf(PlayerConnection player) {
        return player.nickname() + " prompt";
    }

    private void delete(Path path) {
        try {
            Files.delete(path);
        } catch (IOException exception) {
            throw new IllegalStateException("기존 WebSocket snippet을 삭제할 수 없습니다: " + path, exception);
        }
    }

    private record PlayingScenario(GameScenario scenario, JsonNode roundSnapshot) {
    }

    private record PayloadType(Class<?> rawType, Class<?> typeArgument) {
    }

    private record VotingScenario(
            GameScenario scenario,
            JsonNode voteSnapshot,
            Map<String, JsonNode> notices,
            List<PlayerConnection> voters
    ) {
    }

    private record GameScenario(String roomCode, List<PlayerConnection> players, Map<String, String> promptsByPlayerId)
            implements AutoCloseable {
        private PlayerConnection host() {
            return players.getFirst();
        }

        private void clearTopic() {
            players.forEach(player -> player.topicMessages().clear());
        }

        private void clearAllQueues() {
            players.forEach(PlayerConnection::clearQueues);
        }

        @Override
        public void close() {
            players.forEach(PlayerConnection::close);
        }
    }

    private record PlayerConnection(
            String playerId,
            String nickname,
            WebSocketStompClient client,
            StompSession session,
            BlockingQueue<JsonNode> topicMessages,
            BlockingQueue<JsonNode> imageGenerationMessages,
            BlockingQueue<JsonNode> guessSubmissionMessages,
            BlockingQueue<JsonNode> ownVoteOptionMessages,
            BlockingQueue<JsonNode> errorMessages
    ) implements AutoCloseable {
        private PlayerConnection(String playerId, String nickname, WebSocketStompClient client, StompSession session) {
            this(playerId, nickname, client, session, new LinkedBlockingQueue<>(), new LinkedBlockingQueue<>(),
                    new LinkedBlockingQueue<>(), new LinkedBlockingQueue<>(), new LinkedBlockingQueue<>());
        }

        private void clearQueues() {
            topicMessages.clear();
            imageGenerationMessages.clear();
            guessSubmissionMessages.clear();
            ownVoteOptionMessages.clear();
            errorMessages.clear();
        }

        @Override
        public void close() {
            session.disconnect();
            client.stop();
        }
    }

    private static class JsonNodeStompFrameHandler implements StompFrameHandler {

        private final BlockingQueue<JsonNode> messages;

        private JsonNodeStompFrameHandler(BlockingQueue<JsonNode> messages) {
            this.messages = messages;
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return JsonNode.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            messages.offer((JsonNode) payload);
        }
    }
}
