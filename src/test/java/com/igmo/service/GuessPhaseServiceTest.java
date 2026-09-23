package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GuessSubmissionType;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.web.dto.GuessEntryView;
import com.igmo.web.dto.GuessSubmissionSnapshot;
import com.igmo.web.dto.GuessSubmissionStatus;
import com.igmo.web.dto.OwnVoteOptionNotice;
import com.igmo.web.dto.RoomMessage;
import com.igmo.web.dto.RoundResultSnapshot;
import com.igmo.web.dto.RoundSnapshot;
import com.igmo.web.dto.VoteOptionView;
import com.igmo.web.dto.VoteDisabledReason;
import com.igmo.web.dto.VoteSkippedReason;
import com.igmo.web.dto.VoteSkippedSnapshot;
import com.igmo.web.dto.VoteSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class GuessPhaseServiceTest extends GamePhaseServiceTestSupport {

    @Test
    @DisplayName("추측을 제출하면 제출 현황이 담긴 라운드 스냅샷을 브로드캐스트한다.")
    void submitGuess_추측을_제출하면_현황을_브로드캐스트한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "강아지가 기타를 치는 장면");

        // then
        RoundSnapshot snapshot = captureRoundSnapshotBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.PLAYING);
            softly.assertThat(snapshot.guessEntries())
                    .extracting(entry -> entry.player().id(), GuessEntryView::submitted)
                    .containsExactly(
                            tuple(playerIds.get(1), true),
                            tuple(playerIds.get(2), false)
                    );
        });
    }


    @Test
    @DisplayName("추측을 제출하면 제출자 개인큐에 제출 결과를 전송한다.")
    void submitGuess_추측을_제출하면_개인큐에_제출_결과를_전송한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "강아지가 기타를 치는 장면");

        // then
        GuessSubmissionSnapshot snapshot = captureGuessSubmission(playerIds.get(1));
        assertThat(snapshot).isEqualTo(new GuessSubmissionSnapshot(
                "ABCD", 1, 3, GuessSubmissionStatus.SUBMITTED, "강아지가 기타를 치는 장면", null, null));
        captureRoundSnapshotBroadcast();
        InOrder messageOrder = inOrder(messagingTemplate);
        messageOrder.verify(messagingTemplate).convertAndSend(eq("/topic/rooms/ABCD"), any(RoomMessage.class));
        messageOrder.verify(messagingTemplate).convertAndSendToUser(
                eq(playerIds.get(1)), eq("/queue/guess-submission"), any(GuessSubmissionSnapshot.class));
    }


    @Test
    @DisplayName("다른 플레이어의 추측과 중복되면 메시지를 제출자 개인큐로 전송하고 방에 브로드캐스트하지 않는다.")
    void submitGuess_다른_플레이어의_추측과_중복되면_개인큐로_거절_메시지를_전송한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "강아지가 기타를 치는 장면");
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitGuess("ABCD", playerIds.get(2), "강아지가 기타를 치는 장면");

        // then
        GuessSubmissionSnapshot snapshot = captureGuessSubmission(playerIds.get(2));
        assertThat(snapshot).isEqualTo(new GuessSubmissionSnapshot(
                "ABCD", 1, 3, GuessSubmissionStatus.REJECTED, "강아지가 기타를 치는 장면", null,
                "다른 플레이어의 추측과 동일한 추측은 제출할 수 없습니다."));
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(RoomMessage.class));
    }


    @Test
    @DisplayName("같은 플레이어가 추측을 다시 제출하면 메시지를 개인큐로 전송하고 방에 브로드캐스트하지 않는다.")
    void submitGuess_같은_플레이어가_재제출하면_개인큐로_거절_메시지를_전송한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "강아지가 기타를 치는 장면");
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "고양이가 드럼을 치는 장면");

        // then
        GuessSubmissionSnapshot snapshot = captureGuessSubmission(playerIds.get(1));
        assertThat(snapshot).isEqualTo(new GuessSubmissionSnapshot(
                "ABCD", 1, 3, GuessSubmissionStatus.REJECTED, "고양이가 드럼을 치는 장면", null,
                "이미 추측을 제출했습니다."));
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(RoomMessage.class));
    }


    @Test
    @DisplayName("정답 프롬프트를 제출하면 PERFECT 개인 응답만 전송하고 방에는 브로드캐스트하지 않는다.")
    void submitGuess_정답이면_PERFECT_개인_응답만_전송한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "호스트프롬프트");

        // then
        GuessSubmissionSnapshot snapshot = captureGuessSubmission(playerIds.get(1));
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot).isEqualTo(new GuessSubmissionSnapshot(
                    "ABCD",
                    1,
                    3,
                    GuessSubmissionStatus.PERFECT_RETRY_REQUIRED,
                    "호스트프롬프트",
                    3,
                    null
            ));
            softly.assertThat(gameRegistry.find("ABCD").orElseThrow().getCurrentRound().getGuesses()).isEmpty();
        });
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(RoomMessage.class));
    }


    @Test
    @DisplayName("PERFECT를 다시 제출하면 이미 확정된 정답 사유만 개인큐로 전송한다.")
    void submitGuess_PERFECT를_재제출하면_개인큐로_거절_사유를_전송한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "호스트프롬프트");
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "호스트프롬프트");

        // then
        GuessSubmissionSnapshot snapshot = captureGuessSubmission(playerIds.get(1));
        assertThat(snapshot).isEqualTo(new GuessSubmissionSnapshot(
                "ABCD",
                1,
                3,
                GuessSubmissionStatus.REJECTED,
                "호스트프롬프트",
                null,
                "이미 완벽 정답을 맞혔습니다. 투표용 가짜 프롬프트를 입력하세요."
        ));
        assertThat(gameRegistry.find("ABCD").orElseThrow().getCurrentRound().getGuesses()).isEmpty();
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(RoomMessage.class));
    }


    @Test
    @DisplayName("PERFECT를 맞힌 뒤 가짜 프롬프트를 제출하면 일반 제출 결과와 진행 스냅샷을 전송한다.")
    void submitGuess_PERFECT_후_가짜_프롬프트를_제출한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "호스트프롬프트");
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "강아지가 기타를 치는 장면");

        // then
        GuessSubmissionSnapshot snapshot = captureGuessSubmission(playerIds.get(1));
        assertThat(snapshot.status()).isEqualTo(GuessSubmissionStatus.SUBMITTED);
        captureRoundSnapshotBroadcast();
    }


    @Test
    @DisplayName("PERFECT 플레이어가 포함된 투표는 집계 진행도를 공개하고 해당 플레이어에게 투표 불가를 알린다.")
    void submitGuess_PERFECT_플레이어가_있으면_집계_진행도와_투표_불가를_전송한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "호스트프롬프트");
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "강아지가 기타를 치는 장면");
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitGuess("ABCD", playerIds.get(2), "고양이가 드럼을 치는 장면");

        // then
        VoteSnapshot voteSnapshot = captureVoteSnapshotBroadcast();
        OwnVoteOptionNotice perfectPlayerOption = captureOwnVoteOption(playerIds.get(1));
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(voteSnapshot.completedVoteCount()).isEqualTo(1);
            softly.assertThat(voteSnapshot.totalVoteCount()).isEqualTo(2);
            softly.assertThat(voteSnapshot.perfectGuessExists()).isTrue();
            softly.assertThat(perfectPlayerOption.ownImage()).isFalse();
            softly.assertThat(perfectPlayerOption.voteAllowed()).isFalse();
            softly.assertThat(perfectPlayerOption.voteDisabledReason()).isEqualTo(VoteDisabledReason.PERFECT_GUESS);
        });
    }


    @Test
    @DisplayName("출제자를 제외한 전원이 PERFECT면 투표 생략 스냅샷을 보내고 3초 후 결과를 공개한다.")
    void submitGuess_전원이_PERFECT면_투표생략_스냅샷후_결과를_공개한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "호스트프롬프트");
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "강아지가 기타를 치는 장면");
        gamePhaseService.submitGuess("ABCD", playerIds.get(2), "호스트프롬프트");
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitGuess("ABCD", playerIds.get(2), "고양이가 드럼을 치는 장면");

        // then
        VoteSkippedSnapshot skippedSnapshot = captureVoteSkippedSnapshotBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(skippedSnapshot.roomCode()).isEqualTo("ABCD");
            softly.assertThat(skippedSnapshot.roundNumber()).isEqualTo(1);
            softly.assertThat(skippedSnapshot.phase()).isEqualTo(GamePhase.VOTE_SKIPPED);
            softly.assertThat(skippedSnapshot.reason()).isEqualTo(VoteSkippedReason.ALL_PERFECT);
            softly.assertThat(Duration.between(skippedSnapshot.startedAt(), skippedSnapshot.deadline()))
                    .isEqualTo(Duration.ofSeconds(3));
            softly.assertThat(gameRegistry.find("ABCD").orElseThrow().getPlayers())
                    .filteredOn(player -> player.getId().equals(playerIds.get(1))
                            || player.getId().equals(playerIds.get(2)))
                    .extracting(player -> player.getScore())
                    .containsOnly(0);
        });

        Runnable voteSkippedExpiration = captureLastScheduledDeadline(3);
        clearInvocations(messagingTemplate);
        voteSkippedExpiration.run();

        RoundResultSnapshot resultSnapshot = captureRoundResultSnapshotBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(resultSnapshot.phase()).isEqualTo(GamePhase.RESULTS);
            softly.assertThat(resultSnapshot.voteSkippedReason()).isEqualTo(VoteSkippedReason.ALL_PERFECT);
            softly.assertThat(resultSnapshot.players())
                    .filteredOn(player -> player.id().equals(playerIds.get(1)) || player.id().equals(playerIds.get(2)))
                    .extracting(player -> player.score())
                    .containsOnly(3);
        });
    }


    @Test
    @DisplayName("출제자를 제외한 전원이 추측을 제출하면 VOTING 스냅샷을 브로드캐스트하고 마감 작업을 취소한다.")
    void submitGuess_전원이_제출하면_VOTING_스냅샷을_브로드캐스트하고_마감_작업을_취소한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "강아지가 기타를 치는 장면");
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitGuess("ABCD", playerIds.get(2), "고양이가 드럼을 치는 장면");

        // then
        VoteSnapshot voteSnapshot = captureVoteSnapshotBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(voteSnapshot.phase()).isEqualTo(GamePhase.VOTING);
            softly.assertThat(voteSnapshot.voteOptions()).hasSize(3);
            softly.assertThat(voteSnapshot.voteDeadline()).isNotNull();
        });
        verify(gamePhaseDeadlineScheduler, times(3)).schedule(any(Runnable.class), any(Instant.class));
        verify(scheduledPromptExpiration, times(2)).cancel(false);
    }


    @Test
    @DisplayName("전원이 추측을 제출해 투표가 열리면 추측자에게 본인 보기를 개인큐로 전송한다.")
    void submitGuess_전원이_제출하면_추측자에게_본인_보기를_개인큐로_전송한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "강아지가 기타를 치는 장면");
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitGuess("ABCD", playerIds.get(2), "고양이가 드럼을 치는 장면");

        // then
        OwnVoteOptionNotice guest1Option = captureOwnVoteOption(playerIds.get(1));
        OwnVoteOptionNotice guest2Option = captureOwnVoteOption(playerIds.get(2));
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(guest1Option)
                    .isEqualTo(new OwnVoteOptionNotice(
                            "ABCD", 1, false, true, null, findOwnOptionId("ABCD", playerIds.get(1))));
            softly.assertThat(guest2Option)
                    .isEqualTo(new OwnVoteOptionNotice(
                            "ABCD", 1, false, true, null, findOwnOptionId("ABCD", playerIds.get(2))));
        });
    }


    @Test
    @DisplayName("전원이 추측을 제출해 투표가 열리면 출제자에게 본인 이미지임을 개인큐로 전송한다.")
    void submitGuess_전원이_제출하면_출제자에게_본인_이미지임을_전송한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "강아지가 기타를 치는 장면");
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitGuess("ABCD", playerIds.get(2), "고양이가 드럼을 치는 장면");

        // then
        OwnVoteOptionNotice hostOption = captureOwnVoteOption(playerIds.get(0));
        assertThat(hostOption).isEqualTo(new OwnVoteOptionNotice(
                "ABCD", 1, true, false, VoteDisabledReason.QUESTIONER, null));
    }


    @Test
    @DisplayName("방에 없는 플레이어가 추측을 제출하면 PlayerNotFoundException을 던진다.")
    void submitGuess_방에_없는_플레이어면_예외를_던진다() {
        // given
        setUpRoomInPlaying();

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitGuess("ABCD", "unknown-player", "추측"))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessage("방에 없는 플레이어입니다.");
    }


    @Test
    @DisplayName("추측 마감 작업이 실행되면 미제출자에게 자동 추측을 채워 VOTING 스냅샷을 브로드캐스트한다.")
    void guessDeadline_마감_작업이_실행되면_자동_추측을_채워_VOTING_스냅샷을_브로드캐스트한다() {
        // given
        setUpRoomWithImagesReady();
        captureScheduledPlayingTransition().run();
        captureLastScheduledDeadline(2);
        clearInvocations(messagingTemplate);
        expireGuessSubmissionDeadline();

        // when
        runGuessExpirationCallback();

        // then
        VoteSnapshot voteSnapshot = captureVoteSnapshotBroadcast();
        List<String> optionTexts = voteSnapshot.voteOptions().stream()
                .map(VoteOptionView::text)
                .toList();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(voteSnapshot.phase()).isEqualTo(GamePhase.VOTING);
            softly.assertThat(voteSnapshot.voteOptions()).hasSize(3);
            softly.assertThat(optionTexts).anyMatch(text -> autoPromptCandidates("참가자1").contains(text));
            softly.assertThat(optionTexts).anyMatch(text -> autoPromptCandidates("참가자2").contains(text));
            softly.assertThat(voteSnapshot.completedVoteCount()).isZero();
            softly.assertThat(voteSnapshot.totalVoteCount()).isEqualTo(2);
            softly.assertThat(voteSnapshot.perfectGuessExists()).isFalse();
        });
    }


    @Test
    @DisplayName("추측 마감으로 투표가 열리면 자동 추측이 채워진 추측자에게도 본인 보기를 개인큐로 전송한다.")
    void guessDeadline_마감_작업이_실행되면_추측자에게_본인_보기를_개인큐로_전송한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        captureLastScheduledDeadline(2);
        clearInvocations(messagingTemplate);
        expireGuessSubmissionDeadline();

        // when
        runGuessExpirationCallback();

        // then
        OwnVoteOptionNotice guest1Option = captureOwnVoteOption(playerIds.get(1));
        OwnVoteOptionNotice guest2Option = captureOwnVoteOption(playerIds.get(2));
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(guest1Option)
                    .isEqualTo(new OwnVoteOptionNotice(
                            "ABCD", 1, false, true, null, findOwnOptionId("ABCD", playerIds.get(1))));
            softly.assertThat(guest2Option)
                    .isEqualTo(new OwnVoteOptionNotice(
                            "ABCD", 1, false, true, null, findOwnOptionId("ABCD", playerIds.get(2))));
        });
    }


    @Test
    @DisplayName("최종 추측 제출 마감 전 callback은 자동 추측과 VOTING 전환을 실행하지 않는다.")
    void guessDeadline_최종_제출_마감_전_callback은_무시한다() {
        // given
        setUpRoomWithImagesReady();
        captureScheduledPlayingTransition().run();
        Runnable guessExpiration = captureLastScheduledDeadline(2);
        clearInvocations(messagingTemplate);

        // when
        guessExpiration.run();

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(gameRegistry.find("ABCD").orElseThrow().getPhase()).isEqualTo(GamePhase.PLAYING);
            softly.assertThat(gameRegistry.find("ABCD").orElseThrow().getCurrentRound().getGuesses())
                    .isEmpty();
        });
        verify(gamePhaseDeadlineScheduler, times(3)).schedule(any(Runnable.class), any(Instant.class));
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(Object.class));
    }


    @Test
    @DisplayName("Grace Period 내 DEADLINE 추측은 실제 입력을 저장하고 자동 추측을 만들지 않는다.")
    void submitGuess_Grace_Period_내_DEADLINE이면_실제_추측을_저장한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        GameRoom room = gameRegistry.find("ABCD").orElseThrow();
        ReflectionTestUtils.setField(room, "guessDeadline", Instant.now().minusMillis(500));
        ReflectionTestUtils.setField(room, "finalGuessSubmissionDeadline", Instant.now().plusSeconds(1));

        // when
        gamePhaseService.submitGuess(
                "ABCD", playerIds.get(1), "작성 중이던 추측", GuessSubmissionType.DEADLINE);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getCurrentRound().getGuesses())
                    .extracting(entry -> entry.getGuess())
                    .containsExactly("작성 중이던 추측");
            softly.assertThat(room.getPhase()).isEqualTo(GamePhase.PLAYING);
        });
    }


    @Test
    @DisplayName("취소된 추측 마감 작업이 실행되면 아무것도 브로드캐스트하지 않는다.")
    void guessDeadline_취소된_마감_작업이_실행되면_무시한다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        Runnable guessExpiration = captureLastScheduledDeadline(2);
        gamePhaseService.submitGuess("ABCD", playerIds.get(1), "강아지가 기타를 치는 장면");
        gamePhaseService.submitGuess("ABCD", playerIds.get(2), "고양이가 드럼을 치는 장면");
        clearInvocations(messagingTemplate);

        // when
        guessExpiration.run();

        // then
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(Object.class));
    }

}
