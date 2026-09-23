package com.igmo.service.phase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.GuessSubmissionType;
import com.igmo.domain.exception.PerfectGuesserVoteNotAllowedException;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.web.websocket.snapshot.GameResultSnapshot;
import com.igmo.web.websocket.snapshot.RoundResultSnapshot;
import com.igmo.web.websocket.snapshot.RoundSnapshot;
import com.igmo.web.websocket.snapshot.VoteSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class VoteResultPhaseServiceTest extends GamePhaseServiceTestSupport {

    @Test
    @DisplayName("PERFECT 플레이어가 투표를 요청하면 서버가 거절한다.")
    void submitVote_PERFECT_플레이어면_예외를_던진다() {
        // given
        List<String> playerIds = setUpRoomInPlaying();
        gamePhaseService.submitGuess(
                "ABCD", playerIds.get(1), "호스트프롬프트", GuessSubmissionType.NORMAL);
        gamePhaseService.submitGuess(
                "ABCD", playerIds.get(1), "강아지가 기타를 치는 장면", GuessSubmissionType.NORMAL);
        gamePhaseService.submitGuess(
                "ABCD", playerIds.get(2), "고양이가 드럼을 치는 장면", GuessSubmissionType.NORMAL);
        String answerOptionId = findAnswerOptionId("ABCD");

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitVote("ABCD", playerIds.get(1), answerOptionId))
                .isInstanceOf(PerfectGuesserVoteNotAllowedException.class)
                .hasMessage("완벽 정답자는 투표할 수 없습니다.");
    }


    @Test
    @DisplayName("투표를 제출하면 투표 현황 스냅샷을 브로드캐스트한다.")
    void submitVote_투표를_제출하면_현황을_브로드캐스트한다() {
        // given
        List<String> playerIds = setUpRoomInVoting();
        String answerOptionId = findAnswerOptionId("ABCD");
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitVote("ABCD", playerIds.get(1), answerOptionId);

        // then
        VoteSnapshot snapshot = captureVoteSnapshotBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.VOTING);
            softly.assertThat(snapshot.completedVoteCount()).isEqualTo(1);
            softly.assertThat(snapshot.totalVoteCount()).isEqualTo(2);
            softly.assertThat(snapshot.perfectGuessExists()).isFalse();
        });
    }


    @Test
    @DisplayName("출제자를 제외한 전원이 투표하면 RESULTS로 전환하고 마감 작업을 취소한다.")
    void submitVote_전원이_투표하면_RESULTS로_전환하고_마감_작업을_취소한다() {
        // given
        List<String> playerIds = setUpRoomInVoting();
        String answerOptionId = findAnswerOptionId("ABCD");
        gamePhaseService.submitVote("ABCD", playerIds.get(1), answerOptionId);
        clearInvocations(messagingTemplate);

        // when
        gamePhaseService.submitVote("ABCD", playerIds.get(2), answerOptionId);

        // then
        RoundResultSnapshot snapshot = captureRoundResultSnapshotBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.RESULTS);
            softly.assertThat(snapshot.voteSkippedReason()).isNull();
        });
        verify(scheduledPromptExpiration, times(3)).cancel(false);
    }


    @Test
    @DisplayName("방에 없는 플레이어가 투표를 제출하면 PlayerNotFoundException을 던진다.")
    void submitVote_방에_없는_플레이어면_예외를_던진다() {
        // given
        setUpRoomInVoting();
        String answerOptionId = findAnswerOptionId("ABCD");

        // when & then
        assertThatThrownBy(() -> gamePhaseService.submitVote("ABCD", "unknown-player", answerOptionId))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessage("방에 없는 플레이어입니다.");
    }


    @Test
    @DisplayName("투표 마감 작업이 실행되면 RESULTS로 전환한 스냅샷을 브로드캐스트한다.")
    void voteDeadline_마감_작업이_실행되면_RESULTS로_전환한다() {
        // given
        ReflectionTestUtils.setField(voteResultPhaseService, "voteDuration", Duration.ofMillis(-1));
        setUpRoomInVoting();
        Runnable voteExpiration = captureLastScheduledDeadline(3);
        clearInvocations(messagingTemplate);

        // when
        voteExpiration.run();

        // then
        RoundResultSnapshot snapshot = captureRoundResultSnapshotBroadcast();
        assertThat(snapshot.phase()).isEqualTo(GamePhase.RESULTS);
    }


    @Test
    @DisplayName("취소된 투표 마감 작업이 실행되면 아무것도 브로드캐스트하지 않는다.")
    void voteDeadline_취소된_마감_작업이_실행되면_무시한다() {
        // given
        List<String> playerIds = setUpRoomInVoting();
        Runnable voteExpiration = captureLastScheduledDeadline(3);
        String answerOptionId = findAnswerOptionId("ABCD");
        gamePhaseService.submitVote("ABCD", playerIds.get(1), answerOptionId);
        gamePhaseService.submitVote("ABCD", playerIds.get(2), answerOptionId);
        clearInvocations(messagingTemplate);

        // when
        voteExpiration.run();

        // then
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(Object.class));
    }


    @Test
    @DisplayName("결과 확인 마감 작업이 실행되면 다음 라운드 스냅샷을 브로드캐스트하고 추측 마감을 예약한다.")
    void resultDeadline_마감_작업이_실행되면_다음_라운드로_넘어간다() {
        // given
        List<String> playerIds = setUpRoomInResults();
        Runnable resultExpiration = captureLastScheduledDeadline(4);
        clearInvocations(messagingTemplate);

        // when
        resultExpiration.run();

        // then
        RoundSnapshot snapshot = captureRoundSnapshotBroadcast();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(snapshot.phase()).isEqualTo(GamePhase.PLAYING);
            softly.assertThat(snapshot.roundNumber()).isEqualTo(2);
            softly.assertThat(snapshot.questioner().id()).isEqualTo(playerIds.get(1));
            softly.assertThat(gameRegistry.find("ABCD")).isPresent();
        });
        verify(gamePhaseDeadlineScheduler, times(5)).schedule(any(Runnable.class), any(Instant.class));
    }


    @Test
    @DisplayName("마지막 라운드의 결과 확인 마감 작업이 실행되면 게임 종료 스냅샷을 브로드캐스트한다.")
    void resultDeadline_마지막_라운드면_게임_종료_스냅샷을_브로드캐스트한다() {
        // given
        setUpRoomInResults();
        GameRoom room = gameRegistry.find("ABCD").orElseThrow();
        ReflectionTestUtils.setField(room, "currentRoundIndex", room.getTotalRoundCount() - 1);
        Runnable resultExpiration = captureLastScheduledDeadline(4);
        clearInvocations(messagingTemplate);

        // when
        resultExpiration.run();

        // then
        GameResultSnapshot snapshot = captureGameResultSnapshotBroadcast();
        assertThat(snapshot.phase()).isEqualTo(GamePhase.ENDED);
        assertThat(gameRegistry.find("ABCD")).isEmpty();
        verify(gameDrainLifecycle).onGameEnded("ABCD");
    }


    @Test
    @DisplayName("취소된 결과 확인 마감 작업이 실행되면 아무것도 브로드캐스트하지 않는다.")
    void resultDeadline_취소된_마감_작업이_실행되면_무시한다() {
        // given
        setUpRoomInResults();
        Runnable resultExpiration = captureLastScheduledDeadline(4);
        resultExpiration.run();
        clearInvocations(messagingTemplate);

        // when
        resultExpiration.run();

        // then
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rooms/ABCD"), any(Object.class));
    }

}
