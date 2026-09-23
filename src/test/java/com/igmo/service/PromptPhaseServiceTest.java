package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.PromptEntry;
import com.igmo.domain.PromptEntryStatus;
import com.igmo.domain.PromptSubmissionType;
import com.igmo.domain.SamplePrompt;
import com.igmo.domain.exception.DuplicatePromptSubmissionException;
import com.igmo.domain.exception.NotHostException;
import com.igmo.domain.exception.PromptSubmissionExpiredException;
import com.igmo.domain.exception.PromptSubmissionNotAllowedException;
import com.igmo.imagegeneration.exception.GeminiResponseException;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.GuessEntryView;
import com.igmo.web.dto.ImageGenerationEvent;
import com.igmo.web.dto.JoinGameResponse;
import com.igmo.web.dto.PromptEntryView;
import com.igmo.web.dto.PromptSubmissionSnapshot;
import com.igmo.web.dto.RoomMessage;
import com.igmo.web.dto.RoomMessageType;
import com.igmo.web.dto.RoundSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class PromptPhaseServiceTest extends GamePhaseServiceTestSupport {

    @Test
    @DisplayName("방장이 시작하면 GENERATING 단계로 진행한 스냅샷을 브로드캐스트한다.")
    void startGame_방장이_시작하면_다음_단계_스냅샷을_브로드캐스트한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);

        // when
        gamePhaseService.startGame("ABCD", created.playerId());

        // then
        List<RoomMessage> messages = captureRoomBroadcasts(5);
        RoomMessage lastMessage = messages.getLast();
        PromptSubmissionSnapshot promptSnapshot = (PromptSubmissionSnapshot) lastMessage.payload();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(lastMessage.type()).isEqualTo(RoomMessageType.PROMPT_SUBMISSION_SNAPSHOT);
            softly.assertThat(messages)
                    .filteredOn(message -> message.type() == RoomMessageType.LOBBY_SNAPSHOT)
                    .hasSize(4);
            softly.assertThat(promptSnapshot.phase()).isEqualTo(GamePhase.GENERATING);
            softly.assertThat(promptSnapshot.promptStartedAt()).isNotNull();
            softly.assertThat(promptSnapshot.promptDeadline())
                    .isEqualTo(promptSnapshot.promptStartedAt().plusSeconds(30));
            softly.assertThat(promptSnapshot.promptEntries())
                    .extracting(promptEntry -> promptEntry.player().id(),
                            PromptEntryView::status)
                    .containsExactly(
                            tuple(created.playerId(), PromptEntryStatus.WAITING),
                            tuple(guest1.playerId(), PromptEntryStatus.WAITING),
                            tuple(guest2.playerId(), PromptEntryStatus.WAITING)
                    );
        });
        Instant finalSubmissionDeadline = gameRegistry.find("ABCD").orElseThrow().getFinalPromptSubmissionDeadline();
        verify(gamePhaseDeadlineScheduler).schedule(any(Runnable.class), eq(finalSubmissionDeadline));
    }


    @Test
    @DisplayName("게임 시작으로 단계가 바뀌면 완료 로그를 한 번 남긴다.")
    void startGame_단계가_바뀌면_완료로그를_한번_남긴다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);

        // when
        gamePhaseService.startGame("ABCD", created.playerId());

        // then
        ILoggingEvent logEvent = gamePhaseLogAppender.list.stream()
                .filter(event -> "game_phase_transition_completed".equals(keyValues(event).get("event")))
                .findFirst()
                .orElseThrow();
        assertThat(keyValues(logEvent))
                .containsEntry("event", "game_phase_transition_completed")
                .containsEntry("roomCode", "ABCD")
                .containsEntry("fromPhase", GamePhase.LOBBY)
                .containsEntry("toPhase", GamePhase.GENERATING);
        assertThat(keyValues(logEvent)).doesNotContainKey("outcome");
        assertThat(logEvent.getFormattedMessage()).isNullOrEmpty();
    }


    @Test
    @DisplayName("존재하지 않는 방을 시작하면 RoomNotFoundException을 던진다.")
    void startGame_없는_방이면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> gamePhaseService.startGame("ZZZZ", "player-id"))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("방을 찾을 수 없습니다.");
        assertThat(gamePhaseLogAppender.list)
                .noneMatch(event -> "game_phase_transition_completed".equals(keyValues(event).get("event")));
    }


    @Test
    @DisplayName("방장이 아닌 참가자가 시작하면 도메인의 NotHostException을 전파한다.")
    void startGame_방장이_아니면_예외를_전파한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        gameLobbyService.joinGame("ABCD", "참가자2");

        // when & then
        assertThatThrownBy(() -> gamePhaseService.startGame("ABCD", guest1.playerId()))
                .isInstanceOf(NotHostException.class)
                .hasMessage("방장만 게임을 시작할 수 있습니다.");
    }


    @Test
    @DisplayName("GENERATING 단계에서 프롬프트를 제출하면 제출자에게 생성중 상태를 개인 전송한다.")
    void submitPrompt_GENERATING_단계이면_프롬프트를_저장하고_생성중_상태를_개인_전송한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", created.playerId());
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "고양이가 피아노를 치는 장면", PromptSubmissionType.NORMAL);

        // then
        PromptEntry entry = findPromptEntry("ABCD", guest1.playerId());
        ImageGenerationEvent event = captureImageGenerationEvent(guest1.playerId());

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getPrompt()).isEqualTo("고양이가 피아노를 치는 장면");
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.GENERATING);
            softly.assertThat(entry.getSubmittedAt()).isNotNull();
            softly.assertThat(event.roomCode()).isEqualTo("ABCD");
            softly.assertThat(event.status()).isEqualTo(PromptEntryStatus.GENERATING);
            softly.assertThat(event.prompt()).isEqualTo("고양이가 피아노를 치는 장면");
            softly.assertThat(event.imageUrl()).isNull();
            softly.assertThat(event.errorMessage()).isNull();
            softly.assertThat(imageGenerationTask).isNotNull();
        });
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(RoomMessage.class));
    }


    @Test
    @DisplayName("이미지 생성이 성공하면 이미지 URL을 개인 전송하고 전체 상태를 브로드캐스트한다.")
    void imageGeneration_성공하면_이미지_URL을_개인_전송하고_전체_상태를_브로드캐스트한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        givenGeneratedImage("고양이가 피아노를 치는 장면", "https://cdn.example.com/prompt-1.png");
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", created.playerId());
        gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "고양이가 피아노를 치는 장면", PromptSubmissionType.NORMAL);
        clearInvocations(messagingTemplate);

        // when
        runImageGenerationTask();

        // then
        PromptEntry entry = findPromptEntry("ABCD", guest1.playerId());
        ImageGenerationEvent event = captureImageGenerationEvent(guest1.playerId());
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(entry.getImageUrl()).isEqualTo("https://cdn.example.com/prompt-1.png");
            softly.assertThat(event.roomCode()).isEqualTo("ABCD");
            softly.assertThat(event.status()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(event.prompt()).isEqualTo("고양이가 피아노를 치는 장면");
            softly.assertThat(event.imageUrl()).isEqualTo("https://cdn.example.com/prompt-1.png");
        });
        verifyGeneratedImage("고양이가 피아노를 치는 장면");
        PromptSubmissionSnapshot snapshot = captureLastPromptSubmissionBroadcast();
        assertThat(snapshot.promptEntries())
                .filteredOn(promptEntry -> promptEntry.player().id().equals(guest1.playerId()))
                .singleElement()
                .extracting(PromptEntryView::status)
                .isEqualTo(PromptEntryStatus.READY);
    }


    @Test
    @DisplayName("마지막 이미지 생성 전에는 PLAYING 전환을 예약하지 않는다.")
    void imageGeneration_마지막_이미지_생성_전에는_PLAYING_전환을_예약하지_않는다() {
        // given
        GameSession session = startGeneratingGame();

        // when
        submitPromptAndCompleteImage(session.host().playerId(), "호스트 프롬프트");
        submitPromptAndCompleteImage(session.guest1().playerId(), "참가자1 프롬프트");

        // then
        verify(imageGenerationCompletionScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }


    @Test
    @DisplayName("마지막 이미지 생성 시 3초 후 PLAYING 전환을 예약한다.")
    void imageGeneration_마지막_이미지_생성시_3초_후_PLAYING_전환을_예약한다() {
        // given
        GameSession session = startGeneratingGame();
        submitPromptAndCompleteImage(session.host().playerId(), "호스트 프롬프트");
        submitPromptAndCompleteImage(session.guest1().playerId(), "참가자1 프롬프트");

        // when
        Instant before = Instant.now();
        submitPromptAndCompleteImage(session.guest2().playerId(), "참가자2 프롬프트");
        Instant after = Instant.now();

        // then
        assertThat(gameRegistry.find("ABCD")).get()
                .extracting(GameRoom::getPhase)
                .isEqualTo(GamePhase.GENERATING);
        ArgumentCaptor<Instant> scheduledAt = ArgumentCaptor.forClass(Instant.class);
        verify(imageGenerationCompletionScheduler).schedule(any(Runnable.class), scheduledAt.capture());
        assertThat(scheduledAt.getValue()).isBetween(before.plusSeconds(3), after.plusSeconds(3));
    }


    @Test
    @DisplayName("예약된 PLAYING 전환 작업이 실행되면 phase를 PLAYING으로 변경한다.")
    void imageGeneration_예약된_PLAYING_전환_작업이_실행되면_phase를_PLAYING으로_변경한다() {
        // given
        GameSession session = startGeneratingGame();
        submitPromptAndCompleteImage(session.host().playerId(), "호스트 프롬프트");
        submitPromptAndCompleteImage(session.guest1().playerId(), "참가자1 프롬프트");
        submitPromptAndCompleteImage(session.guest2().playerId(), "참가자2 프롬프트");
        Runnable transition = captureScheduledPlayingTransition();

        // when
        clearInvocations(messagingTemplate);
        transition.run();

        // then
        RoundSnapshot snapshot = captureRoundSnapshotBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(gameRegistry.find("ABCD")).get()
                    .extracting(GameRoom::getPhase)
                    .isEqualTo(GamePhase.PLAYING);
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.PLAYING);
            softly.assertThat(snapshot.roundNumber()).isEqualTo(1);
            softly.assertThat(snapshot.totalRoundCount()).isEqualTo(3);
            softly.assertThat(snapshot.questioner().id()).isEqualTo(session.host().playerId());
            softly.assertThat(snapshot.imageUrl()).isEqualTo("https://cdn.example.com/host.png");
            softly.assertThat(snapshot.guessDeadline()).isNotNull();
            softly.assertThat(snapshot.guessEntries())
                    .extracting(GuessEntryView::submitted)
                    .containsOnly(false);
        });
        verify(messagingTemplate, times(1))
                .convertAndSend(eq("/topic/rooms/ABCD"), any(RoomMessage.class));
        verify(gamePhaseDeadlineScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }



    @Test
    @DisplayName("이미지 생성이 실패하면 개인 실패 결과를 전송하고 전체 상태를 브로드캐스트한다.")
    void imageGeneration_실패하면_개인_실패_결과를_전송하고_전체_상태를_브로드캐스트한다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        GeminiResponseException failure = new GeminiResponseException(
                "Gemini 응답이 이미지 대신 텍스트입니다.",
                List.of("text"),
                200,
                "gemini-3.1-flash-image",
                "2K");
        givenGenerationFailure("고양이가 피아노를 치는 장면", failure);
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", created.playerId());
        gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "고양이가 피아노를 치는 장면", PromptSubmissionType.NORMAL);
        clearInvocations(messagingTemplate);

        // when
        runImageGenerationTask();

        // then
        PromptEntry entry = findPromptEntry("ABCD", guest1.playerId());
        ImageGenerationEvent event = captureImageGenerationEvent(guest1.playerId());
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.FAILED);
            softly.assertThat(entry.getImageUrl()).isNull();
            softly.assertThat(event.roomCode()).isEqualTo("ABCD");
            softly.assertThat(event.status()).isEqualTo(PromptEntryStatus.FAILED);
            softly.assertThat(event.prompt()).isEqualTo("고양이가 피아노를 치는 장면");
            softly.assertThat(event.imageUrl()).isNull();
            softly.assertThat(event.errorMessage()).isEqualTo("Gemini 응답이 이미지 대신 텍스트입니다.");
        });
        verifyGeneratedImage("고양이가 피아노를 치는 장면");
        verify(imageGenerationCompletionScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        PromptSubmissionSnapshot snapshot = captureLastPromptSubmissionBroadcast();
        assertThat(snapshot.promptEntries())
                .filteredOn(promptEntry -> promptEntry.player().id().equals(guest1.playerId()))
                .singleElement()
                .extracting(PromptEntryView::status)
                .isEqualTo(PromptEntryStatus.FAILED);
    }


    @Test
    @DisplayName("이미지 생성에 실패한 프롬프트는 마감 전 다시 제출할 수 있다.")
    void submitPrompt_이미지_생성에_실패하면_마감_전_다시_제출할_수_있다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        givenGenerationFailure("실패한 프롬프트", new GeminiResponseException(
                "Gemini 응답이 이미지 대신 텍스트입니다.", List.of("text"), 200, "gemini-3.1-flash-image", "2K"));
        givenGeneratedImage("다시 입력한 프롬프트", "https://cdn.example.com/retried.png");
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", created.playerId());
        gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "실패한 프롬프트", PromptSubmissionType.NORMAL);
        runImageGenerationTask();
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "다시 입력한 프롬프트", PromptSubmissionType.NORMAL);
        runImageGenerationTask();

        // then
        PromptEntry entry = findPromptEntry("ABCD", guest1.playerId());
        ImageGenerationEvent event = captureImageGenerationEvent(guest1.playerId());
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(entry.getPrompt()).isEqualTo("다시 입력한 프롬프트");
            softly.assertThat(entry.getImageUrl()).isEqualTo("https://cdn.example.com/retried.png");
            softly.assertThat(event.status()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(event.errorMessage()).isNull();
        });
        verifyGeneratedImage("다시 입력한 프롬프트");
    }


    @Test
    @DisplayName("프롬프트 마감 작업은 WAITING 참가자만 샘플로 채우고 GENERATING 참가자는 유지한다.")
    void promptExpiration_WAITING만_샘플로_채우고_GENERATING은_유지한다() {
        // given
        GameSession session = startExpirationScenarioWithMissingImages();
        clearInvocations(messagingTemplate);
        imageGenerationTask = null;

        // when
        captureScheduledPromptExpiration().run();

        // then
        PromptEntry hostEntry = findPromptEntry("ABCD", session.host().playerId());
        PromptEntry guest1Entry = findPromptEntry("ABCD", session.guest1().playerId());
        PromptEntry guest2Entry = findPromptEntry("ABCD", session.guest2().playerId());
        List<SamplePrompt> pool = samplePromptProvider.getAll();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(hostEntry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(hostEntry.getPrompt()).isEqualTo("호스트 프롬프트");
            softly.assertThat(hostEntry.getImageUrl()).isEqualTo("https://cdn.example.com/host.png");
            softly.assertThat(guest1Entry.getStatus()).isEqualTo(PromptEntryStatus.GENERATING);
            softly.assertThat(guest1Entry.getPrompt()).isEqualTo("참가자1 프롬프트");
            softly.assertThat(guest1Entry.getImageUrl()).isNull();
            softly.assertThat(guest2Entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(pool).contains(new SamplePrompt(guest2Entry.getPrompt(), guest2Entry.getImageUrl()));
            softly.assertThat(imageGenerationTask).isNull();
        });
    }


    @Test
    @DisplayName("프롬프트 마감 시 WAITING 참가자에게만 샘플 이미지 결과를 개인 전송한다.")
    void promptExpiration_WAITING_참가자에게만_샘플_이미지_결과를_전송한다() {
        // given
        GameSession session = startExpirationScenarioWithMissingImages();
        clearInvocations(messagingTemplate);

        // when
        captureScheduledPromptExpiration().run();

        // then
        ImageGenerationEvent guest2Event = captureImageGenerationEvent(session.guest2().playerId());
        List<SamplePrompt> pool = samplePromptProvider.getAll();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(guest2Event.status()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(pool).contains(new SamplePrompt(guest2Event.prompt(), guest2Event.imageUrl()));
        });
        verify(messagingTemplate, never()).convertAndSendToUser(
                eq(session.guest1().playerId()), eq("/queue/image-generation"), any());
    }


    @Test
    @DisplayName("프롬프트 마감 후 GENERATING 참가자가 남아 있으면 PLAYING 전환을 예약하지 않는다.")
    void promptExpiration_GENERATING_참가자가_남아있으면_PLAYING_전환을_예약하지_않는다() {
        // given
        startExpirationScenarioWithMissingImages();
        clearInvocations(messagingTemplate);

        // when
        captureScheduledPromptExpiration().run();

        // then
        verify(imageGenerationCompletionScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }


    @Test
    @DisplayName("마감 후 마지막 GENERATING 참가자가 퇴장해 전원 READY가 되면 PLAYING 전환을 예약한다.")
    void onPlayerRemoved_마감_후_전원_READY가_되면_PLAYING_전환을_예약한다() {
        // given
        GameSession session = startExpirationScenarioWithMissingImages();
        captureScheduledPromptExpiration().run();
        gameRegistry.find("ABCD").orElseThrow().removePlayer(session.guest1().playerId());
        clearInvocations(imageGenerationCompletionScheduler, scheduledPromptExpiration);

        // when
        gamePhaseService.onPlayerRemoved("ABCD");

        // then
        verify(imageGenerationCompletionScheduler).schedule(any(Runnable.class), any(Instant.class));
    }


    @Test
    @DisplayName("모든 프롬프트를 제출해도 이미지가 준비되기 전에는 프롬프트 마감 작업을 취소하지 않는다.")
    void submitPrompt_이미지가_준비되기_전에는_프롬프트_마감을_취소하지_않는다() {
        // given
        GameSession session = startGeneratingGame();

        // when
        gamePhaseService.submitPrompt("ABCD", session.host().playerId(), "호스트 프롬프트", PromptSubmissionType.NORMAL);
        gamePhaseService.submitPrompt("ABCD", session.guest1().playerId(), "참가자1 프롬프트", PromptSubmissionType.NORMAL);
        gamePhaseService.submitPrompt("ABCD", session.guest2().playerId(), "참가자2 프롬프트", PromptSubmissionType.NORMAL);

        // then
        verify(scheduledPromptExpiration, never()).cancel(false);
    }


    @Test
    @DisplayName("전원 프롬프트 제출 후 일부 이미지 생성이 실패해도 마감 시 실패한 참가자를 샘플로 채워 전원 READY로 만든다.")
    void promptExpiration_이미지_생성_실패자를_샘플로_채운다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        givenGeneratedImage("호스트 프롬프트", "https://cdn.example.com/host.png");
        givenGeneratedImage("참가자1 프롬프트", "https://cdn.example.com/guest-1.png");
        givenGenerationFailure("참가자2 프롬프트", new RuntimeException("이미지 생성 실패"));
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", created.playerId());
        submitPromptAndCompleteImage(created.playerId(), "호스트 프롬프트");
        submitPromptAndCompleteImage(guest1.playerId(), "참가자1 프롬프트");
        submitPromptAndCompleteImage(guest2.playerId(), "참가자2 프롬프트");
        clearInvocations(messagingTemplate);

        // when
        captureScheduledPromptExpiration().run();

        // then
        PromptEntry guest2Entry = findPromptEntry("ABCD", guest2.playerId());
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(guest2Entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(samplePromptProvider.getAll())
                    .contains(new SamplePrompt(guest2Entry.getPrompt(), guest2Entry.getImageUrl()));
            softly.assertThat(gameRegistry.find("ABCD").orElseThrow().hasAllImagesGenerated()).isTrue();
        });
        verify(imageGenerationCompletionScheduler).schedule(any(Runnable.class), any(Instant.class));
    }


    @Test
    @DisplayName("프롬프트 마감 후 생성에 성공하면 실제 이미지 URL을 전송하고 마지막 이미지면 PLAYING 전환을 예약한다.")
    void imageGeneration_마감_후_성공하면_실제_URL을_전송하고_PLAYING_전환을_예약한다() {
        // given
        GameSession session = startExpirationScenarioWithMissingImages();
        Runnable lateGuest1Generation = imageGenerationTask;
        String generatedImageUrl = "https://cdn.example.com/guest-1.png";
        captureScheduledPromptExpiration().run();
        expirePromptDeadline();
        clearInvocations(messagingTemplate, imageGenerationCompletionScheduler);

        // when
        lateGuest1Generation.run();

        // then
        PromptEntry guest1Entry = findPromptEntry("ABCD", session.guest1().playerId());
        ImageGenerationEvent successEvent = captureImageGenerationEvent(session.guest1().playerId());
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(guest1Entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(guest1Entry.getImageUrl()).isEqualTo(generatedImageUrl);
            softly.assertThat(successEvent.status()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(successEvent.imageUrl()).isEqualTo(generatedImageUrl);
        });
        verify(imageGenerationCompletionScheduler).schedule(any(Runnable.class), any(Instant.class));
    }


    @Test
    @DisplayName("프롬프트 마감 후 이미지 생성에 실패하면 해당 참가자만 샘플로 채우고 PLAYING 전환을 예약한다.")
    void imageGeneration_마감_후_실패하면_해당_참가자만_샘플로_채우고_PLAYING_전환을_예약한다() {
        // given
        GameSession session = startExpirationScenarioWithMissingImages();
        givenGenerationFailure("참가자1 프롬프트", new RuntimeException("이미지 생성 실패"));
        Runnable lateGuest1Generation = imageGenerationTask;
        captureScheduledPromptExpiration().run();
        expirePromptDeadline();
        clearInvocations(messagingTemplate, imageGenerationCompletionScheduler);

        // when
        lateGuest1Generation.run();

        // then
        PromptEntry guest1Entry = findPromptEntry("ABCD", session.guest1().playerId());
        ImageGenerationEvent sampleEvent = captureImageGenerationEvent(session.guest1().playerId());
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(guest1Entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(samplePromptProvider.getAll())
                    .contains(new SamplePrompt(sampleEvent.prompt(), sampleEvent.imageUrl()));
            softly.assertThat(guest1Entry.getImageUrl()).isEqualTo(sampleEvent.imageUrl());
        });
        verify(imageGenerationCompletionScheduler).schedule(any(Runnable.class), any(Instant.class));
    }


    @Test
    @DisplayName("프롬프트 마감 후 한 참가자의 생성 실패는 다른 GENERATING 참가자를 샘플로 바꾸지 않는다.")
    void imageGeneration_마감_후_실패해도_다른_GENERATING_참가자는_유지한다() {
        // given
        GameSession session = startGeneratingGame();
        submitPromptAndCompleteImage(session.host().playerId(), "호스트 프롬프트");
        gamePhaseService.submitPrompt("ABCD", session.guest1().playerId(), "참가자1 프롬프트", PromptSubmissionType.NORMAL);
        Runnable lateGuest1Generation = imageGenerationTask;
        gamePhaseService.submitPrompt("ABCD", session.guest2().playerId(), "참가자2 프롬프트", PromptSubmissionType.NORMAL);
        givenGenerationFailure("참가자1 프롬프트", new RuntimeException("이미지 생성 실패"));
        captureScheduledPromptExpiration().run();
        expirePromptDeadline();
        clearInvocations(messagingTemplate, imageGenerationCompletionScheduler);

        // when
        lateGuest1Generation.run();

        // then
        PromptEntry guest1Entry = findPromptEntry("ABCD", session.guest1().playerId());
        PromptEntry guest2Entry = findPromptEntry("ABCD", session.guest2().playerId());
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(guest1Entry.getStatus()).isEqualTo(PromptEntryStatus.READY);
            softly.assertThat(samplePromptProvider.getAll())
                    .contains(new SamplePrompt(guest1Entry.getPrompt(), guest1Entry.getImageUrl()));
            softly.assertThat(guest2Entry.getStatus()).isEqualTo(PromptEntryStatus.GENERATING);
            softly.assertThat(guest2Entry.getImageUrl()).isNull();
        });
        verify(imageGenerationCompletionScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }


    @Test
    @DisplayName("존재하지 않는 방에 프롬프트를 제출하면 RoomNotFoundException을 던진다.")
    void submitPrompt_없는_방이면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitPrompt("ZZZZ", "player-id", "프롬프트", PromptSubmissionType.NORMAL))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("방을 찾을 수 없습니다.");
    }


    @Test
    @DisplayName("방에 없는 플레이어가 프롬프트를 제출하면 PlayerNotFoundException을 던진다.")
    void submitPrompt_방에_없는_플레이어이면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        gameLobbyService.createGame("호스트");

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitPrompt("ABCD", "unknown-player-id", "프롬프트", PromptSubmissionType.NORMAL))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessage("방에 없는 플레이어입니다.");
    }


    @Test
    @DisplayName("GENERATING 단계가 아니면 프롬프트 제출 시 PromptSubmissionNotAllowedException을 던진다.")
    void submitPrompt_GENERATING_단계가_아니면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameLobbyService.createGame("호스트");

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitPrompt("ABCD", created.playerId(), "프롬프트", PromptSubmissionType.NORMAL))
                .isInstanceOf(PromptSubmissionNotAllowedException.class)
                .hasMessage("프롬프트를 제출할 수 있는 단계가 아닙니다.");
    }


    @Test
    @DisplayName("이미 제출한 플레이어가 다시 제출하면 DuplicatePromptSubmissionException을 던진다.")
    void submitPrompt_이미_제출한_플레이어이면_예외를_던진다() {
        // given
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", created.playerId());
        gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "첫 번째 프롬프트", PromptSubmissionType.NORMAL);

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "두 번째 프롬프트", PromptSubmissionType.NORMAL))
                .isInstanceOf(DuplicatePromptSubmissionException.class)
                .hasMessage("이미 프롬프트를 제출했습니다.");
    }


    @Test
    @DisplayName("입력 마감 이후 NORMAL 제출이면 PromptSubmissionExpiredException을 전파한다.")
    void submitPrompt_입력_마감_이후_NORMAL이면_예외를_전파한다() {
        // given
        ReflectionTestUtils.setField(promptPhaseService, "promptDuration", Duration.ofMillis(-1));
        given(roomCodeGenerator.generate()).willReturn("ABCD");
        CreateGameResponse created = gameLobbyService.createGame("호스트");
        JoinGameResponse guest1 = gameLobbyService.joinGame("ABCD", "참가자1");
        JoinGameResponse guest2 = gameLobbyService.joinGame("ABCD", "참가자2");
        gameLobbyService.changeReady("ABCD", guest1.playerId(), true);
        gameLobbyService.changeReady("ABCD", guest2.playerId(), true);
        gamePhaseService.startGame("ABCD", created.playerId());

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitPrompt("ABCD", guest1.playerId(), "늦은 프롬프트", PromptSubmissionType.NORMAL))
                .isInstanceOf(PromptSubmissionExpiredException.class)
                .hasMessage("프롬프트 제출 시간이 만료되었습니다.");
    }


    @Test
    @DisplayName("Grace Period 내 DEADLINE 제출이면 기존 이미지 생성 흐름을 시작한다.")
    void submitPrompt_Grace_Period_내_DEADLINE이면_이미지_생성을_시작한다() {
        // given
        GameSession session = startGeneratingGame();
        GameRoom room = gameRegistry.find("ABCD").orElseThrow();
        ReflectionTestUtils.setField(room, "promptDeadline", Instant.now().minusMillis(500));
        ReflectionTestUtils.setField(room, "finalPromptSubmissionDeadline", Instant.now().plusSeconds(1));

        // when
        gamePhaseService.submitPrompt(
                "ABCD",
                session.guest1().playerId(),
                "마감 프롬프트",
                PromptSubmissionType.DEADLINE);

        // then
        PromptEntry entry = findPromptEntry("ABCD", session.guest1().playerId());
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.GENERATING);
            softly.assertThat(entry.getPrompt()).isEqualTo("마감 프롬프트");
            softly.assertThat(imageGenerationTask).isNotNull();
        });
    }


    @Test
    @DisplayName("최종 제출 마감 이후 DEADLINE 제출이면 이미지 생성을 시작하지 않는다.")
    void submitPrompt_최종_마감_이후_DEADLINE이면_이미지_생성을_시작하지_않는다() {
        // given
        GameSession session = startGeneratingGame();
        GameRoom room = gameRegistry.find("ABCD").orElseThrow();
        ReflectionTestUtils.setField(room, "promptDeadline", Instant.now().minusSeconds(2));
        ReflectionTestUtils.setField(room, "finalPromptSubmissionDeadline", Instant.now().minusSeconds(1));

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitPrompt(
                "ABCD",
                session.guest1().playerId(),
                "너무 늦은 프롬프트",
                PromptSubmissionType.DEADLINE))
                .isInstanceOf(PromptSubmissionExpiredException.class)
                .hasMessage("프롬프트 제출 시간이 만료되었습니다.");
        assertThat(imageGenerationTask).isNull();
    }


    @Test
    @DisplayName("DEADLINE 제출 후 fallback이 실행되어도 생성 중 이미지를 샘플로 바꾸지 않는다.")
    void promptExpiration_DEADLINE_제출_후에는_GENERATING을_유지한다() {
        // given
        GameSession session = startGeneratingGame();
        GameRoom room = gameRegistry.find("ABCD").orElseThrow();
        ReflectionTestUtils.setField(room, "promptDeadline", Instant.now().minusMillis(500));
        gamePhaseService.submitPrompt(
                "ABCD",
                session.guest1().playerId(),
                "마감 프롬프트",
                PromptSubmissionType.DEADLINE);

        // when
        captureScheduledPromptExpiration().run();

        // then
        PromptEntry entry = findPromptEntry("ABCD", session.guest1().playerId());
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.GENERATING);
            softly.assertThat(entry.getPrompt()).isEqualTo("마감 프롬프트");
            softly.assertThat(entry.getImageUrl()).isNull();
        });
    }


    @Test
    @DisplayName("같은 DEADLINE 제출을 연속 요청해도 이미지 생성은 한 번만 시작한다.")
    void submitPrompt_중복_DEADLINE이면_이미지_생성을_한번만_시작한다() {
        // given
        GameSession session = startGeneratingGame();
        GameRoom room = gameRegistry.find("ABCD").orElseThrow();
        ReflectionTestUtils.setField(room, "promptDeadline", Instant.now().minusMillis(500));
        gamePhaseService.submitPrompt(
                "ABCD",
                session.guest1().playerId(),
                "마감 프롬프트",
                PromptSubmissionType.DEADLINE);
        Runnable firstGeneration = imageGenerationTask;

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitPrompt(
                "ABCD",
                session.guest1().playerId(),
                "중복 마감 프롬프트",
                PromptSubmissionType.DEADLINE))
                .isInstanceOf(DuplicatePromptSubmissionException.class)
                .hasMessage("이미 프롬프트를 제출했습니다.");
        assertThat(imageGenerationTask).isSameAs(firstGeneration);
    }


    @Test
    @DisplayName("fallback이 먼저 샘플을 확정하면 뒤이은 DEADLINE 제출은 이미지 생성을 시작하지 않는다.")
    void submitPrompt_fallback_이후_DEADLINE이면_중복_이미지_생성을_막는다() {
        // given
        GameSession session = startGeneratingGame();
        captureScheduledPromptExpiration().run();
        imageGenerationTask = null;

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitPrompt(
                "ABCD",
                session.guest1().playerId(),
                "뒤늦은 마감 프롬프트",
                PromptSubmissionType.DEADLINE))
                .isInstanceOf(DuplicatePromptSubmissionException.class)
                .hasMessage("이미 프롬프트를 제출했습니다.");
        assertThat(imageGenerationTask).isNull();
    }


}
