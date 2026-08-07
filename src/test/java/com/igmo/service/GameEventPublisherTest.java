package com.igmo.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.igmo.domain.GamePhase;
import com.igmo.domain.PromptEntryStatus;
import com.igmo.monitoring.GameMetrics;
import com.igmo.monitoring.WebSocketChannelType;
import com.igmo.monitoring.WebSocketMessageOutcome;
import com.igmo.monitoring.WebSocketMessageType;
import com.igmo.web.dto.GuessSubmissionSnapshot;
import com.igmo.web.dto.GuessSubmissionStatus;
import com.igmo.web.dto.ImageGenerationEvent;
import com.igmo.web.dto.LobbySnapshot;
import com.igmo.web.dto.OwnVoteOptionNotice;
import com.igmo.web.dto.RoomMessage;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class GameEventPublisherTest {

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final GameMetrics gameMetrics = mock(GameMetrics.class);
    private final GameEventPublisher eventPublisher = new GameEventPublisher(messagingTemplate, gameMetrics);
    private final Logger eventPublisherLogger = (Logger) LoggerFactory.getLogger(GameEventPublisher.class);
    private ListAppender<ILoggingEvent> eventPublisherLogAppender;

    @BeforeEach
    void 전송_실패_로그_appender를_연결한다() {
        eventPublisherLogAppender = new ListAppender<>();
        eventPublisherLogAppender.start();
        eventPublisherLogger.addAppender(eventPublisherLogAppender);
    }

    @AfterEach
    void 전송_실패_로그_appender를_제거한다() {
        eventPublisherLogger.detachAppender(eventPublisherLogAppender);
    }

    @Test
    @DisplayName("방 브로드캐스트 성공 시 성공 카운터를 증가시킨다.")
    void publish_성공시_공개토픽_성공_메트릭을_한번_기록한다() {
        // given
        RoomMessage<?> message = RoomMessage.lobbySnapshot(mock(LobbySnapshot.class));

        // when
        eventPublisher.publish("ABCD", message);

        // then
        verify(gameMetrics).recordWebSocketMessageSend(
                WebSocketMessageType.LOBBY_SNAPSHOT,
                WebSocketChannelType.ROOM_TOPIC,
                WebSocketMessageOutcome.SUCCESS);
        verify(gameMetrics, never()).recordWebSocketMessageSend(
                WebSocketMessageType.LOBBY_SNAPSHOT,
                WebSocketChannelType.ROOM_TOPIC,
                WebSocketMessageOutcome.FAILURE);
    }

    @Test
    @DisplayName("방 브로드캐스트 실패 시 실패 카운터를 증가시키고 예외를 전파한다.")
    void publish_실패시_공개토픽_실패메트릭과_스택트레이스를_기록하고_예외를_전파한다() {
        // given
        RoomMessage<?> message = RoomMessage.lobbySnapshot(mock(LobbySnapshot.class));
        IllegalStateException exception = new IllegalStateException("broker unavailable");
        doThrow(exception)
                .when(messagingTemplate)
                .convertAndSend("/topic/rooms/ABCD", message);

        // when & then
        assertThatThrownBy(() -> eventPublisher.publish("ABCD", message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker unavailable");
        verify(gameMetrics).recordWebSocketMessageSend(
                WebSocketMessageType.LOBBY_SNAPSHOT,
                WebSocketChannelType.ROOM_TOPIC,
                WebSocketMessageOutcome.FAILURE);
        verify(gameMetrics, never()).recordWebSocketMessageSend(
                WebSocketMessageType.LOBBY_SNAPSHOT,
                WebSocketChannelType.ROOM_TOPIC,
                WebSocketMessageOutcome.SUCCESS);
        ILoggingEvent logEvent = eventPublisherLogAppender.list.getFirst();
        org.assertj.core.api.Assertions.assertThat(logEvent.getFormattedMessage())
                .isEqualTo("LOBBY_SNAPSHOT");
        org.assertj.core.api.Assertions.assertThat(keyValues(logEvent))
                .containsEntry("event", "room_broadcast_failed")
                .containsEntry("roomCode", "ABCD")
                .containsEntry("phase", GamePhase.LOBBY)
                .containsEntry("messageType", WebSocketMessageType.LOBBY_SNAPSHOT)
                .containsEntry("channelType", WebSocketChannelType.ROOM_TOPIC)
                .containsEntry("exceptionType", "IllegalStateException");
        org.assertj.core.api.Assertions.assertThat(keyValues(logEvent)).doesNotContainKey("outcome");
        org.assertj.core.api.Assertions.assertThat(logEvent.getThrowableProxy().getClassName())
                .isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    @DisplayName("본인 투표 보기를 해당 플레이어의 개인큐로 전송한다.")
    void sendOwnVoteOption_개인큐_성공_메트릭을_한번_기록한다() {
        // given
        OwnVoteOptionNotice notice = new OwnVoteOptionNotice("ABCD", 1, false, true, null, "option-1");

        // when
        eventPublisher.sendOwnVoteOption("player-1", notice);

        // then
        verify(messagingTemplate).convertAndSendToUser("player-1", "/queue/vote-own-option", notice);
        verify(gameMetrics).recordWebSocketMessageSend(
                WebSocketMessageType.OWN_VOTE_OPTION_NOTICE,
                WebSocketChannelType.PRIVATE_QUEUE,
                WebSocketMessageOutcome.SUCCESS);
    }

    @Test
    @DisplayName("이미지 생성 이벤트 개인큐 성공 시 성공 메트릭을 한 번 기록한다.")
    void sendImageGenerationEvent_개인큐_성공_메트릭을_한번_기록한다() {
        // given
        ImageGenerationEvent event = new ImageGenerationEvent("ABCD", PromptEntryStatus.GENERATING, "프롬프트", null);

        // when
        eventPublisher.sendImageGenerationEvent("player-1", event);

        // then
        verify(messagingTemplate).convertAndSendToUser("player-1", "/queue/image-generation", event);
        verify(gameMetrics).recordWebSocketMessageSend(
                WebSocketMessageType.IMAGE_GENERATION_EVENT,
                WebSocketChannelType.PRIVATE_QUEUE,
                WebSocketMessageOutcome.SUCCESS);
    }

    @Test
    @DisplayName("추측 제출 결과 개인큐 성공 시 성공 메트릭을 한 번 기록한다.")
    void sendGuessSubmission_개인큐_성공_메트릭을_한번_기록한다() {
        // given
        GuessSubmissionSnapshot snapshot = new GuessSubmissionSnapshot(
                "ABCD", 1, 3, GuessSubmissionStatus.SUBMITTED, "강아지가 기타를 치는 장면", null, null);

        // when
        eventPublisher.sendGuessSubmission("player-1", GamePhase.PLAYING, snapshot);

        // then
        verify(messagingTemplate).convertAndSendToUser("player-1", "/queue/guess-submission", snapshot);
        verify(gameMetrics).recordWebSocketMessageSend(
                WebSocketMessageType.GUESS_SUBMISSION_RESULT,
                WebSocketChannelType.PRIVATE_QUEUE,
                WebSocketMessageOutcome.SUCCESS);
    }

    @Test
    @DisplayName("추측 제출 결과를 해당 플레이어의 개인큐로 전송한다.")
    void sendGuessSubmission_개인큐_실패시_실패메트릭과_스택트레이스를_기록하고_예외를_전파한다() {
        // given
        GuessSubmissionSnapshot snapshot = new GuessSubmissionSnapshot(
                "ABCD", 1, 3, GuessSubmissionStatus.SUBMITTED, "강아지가 기타를 치는 장면", null, null);

        // when
        IllegalStateException exception = new IllegalStateException("private queue unavailable");
        doThrow(exception)
                .when(messagingTemplate)
                .convertAndSendToUser("player-1", "/queue/guess-submission", snapshot);

        // when & then
        assertThatThrownBy(() -> eventPublisher.sendGuessSubmission("player-1", GamePhase.VOTING, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("private queue unavailable");
        verify(gameMetrics).recordWebSocketMessageSend(
                WebSocketMessageType.GUESS_SUBMISSION_RESULT,
                WebSocketChannelType.PRIVATE_QUEUE,
                WebSocketMessageOutcome.FAILURE);
        verify(gameMetrics, never()).recordWebSocketMessageSend(
                WebSocketMessageType.GUESS_SUBMISSION_RESULT,
                WebSocketChannelType.PRIVATE_QUEUE,
                WebSocketMessageOutcome.SUCCESS);
        ILoggingEvent logEvent = eventPublisherLogAppender.list.getFirst();
        org.assertj.core.api.Assertions.assertThat(logEvent.getFormattedMessage())
                .isEqualTo("GUESS_SUBMISSION_RESULT");
        org.assertj.core.api.Assertions.assertThat(keyValues(logEvent))
                .containsEntry("event", "private_event_send_failed")
                .containsEntry("roomCode", "ABCD")
                .containsEntry("phase", GamePhase.VOTING)
                .containsEntry("messageType", WebSocketMessageType.GUESS_SUBMISSION_RESULT)
                .containsEntry("channelType", WebSocketChannelType.PRIVATE_QUEUE)
                .containsEntry("playerId", "player-1")
                .containsEntry("exceptionType", "IllegalStateException");
        org.assertj.core.api.Assertions.assertThat(keyValues(logEvent)).doesNotContainKey("outcome");
        org.assertj.core.api.Assertions.assertThat(logEvent.getThrowableProxy().getClassName())
                .isEqualTo(IllegalStateException.class.getName());
    }

    private Map<String, Object> keyValues(ILoggingEvent logEvent) {
        return logEvent.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }
}
