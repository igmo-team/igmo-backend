package com.igmo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.igmo.domain.exception.DuplicateNicknameException;
import com.igmo.domain.exception.DuplicatePromptSubmissionException;
import com.igmo.domain.exception.GameAlreadyStartedException;
import com.igmo.domain.exception.GuessSubmissionExpiredException;
import com.igmo.domain.exception.GuessSubmissionNotAllowedException;
import com.igmo.domain.exception.ImagesNotReadyException;
import com.igmo.domain.exception.InsufficientPlayersException;
import com.igmo.domain.exception.NotHostException;
import com.igmo.domain.exception.PlayersNotReadyException;
import com.igmo.domain.exception.PromptSubmissionExpiredException;
import com.igmo.domain.exception.PromptSubmissionNotAllowedException;
import com.igmo.domain.exception.RoomFullException;
import com.igmo.domain.exception.RoundAdvanceNotAllowedException;
import com.igmo.domain.exception.RoundStartNotAllowedException;
import com.igmo.domain.exception.VoteSubmissionExpiredException;
import com.igmo.domain.exception.VoteSubmissionNotAllowedException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GameRoomTest {

    private static final Instant PROMPT_STARTED_AT = Instant.parse("2026-07-06T10:00:00Z");
    private static final Duration PROMPT_DURATION = Duration.ofSeconds(30);
    private static final Instant GUESS_STARTED_AT = Instant.parse("2026-07-06T10:05:00Z");
    private static final Duration GUESS_DURATION = Duration.ofSeconds(60);
    private static final Instant VOTING_OPENED_AT = Instant.parse("2026-07-06T10:05:10Z");
    private static final Duration VOTE_DURATION = Duration.ofSeconds(30);
    private static final Instant RESULTS_OPENED_AT = Instant.parse("2026-07-06T10:05:40Z");
    private static final Duration RESULT_DURATION = Duration.ofSeconds(15);
    private static final Instant PROMPT_DEADLINE = PROMPT_STARTED_AT.plus(PROMPT_DURATION);
    private static final List<SamplePrompt> SAMPLE_POOL = List.of(
            new SamplePrompt("샘플 프롬프트 1", "https://cdn.example.com/samples/1.png"),
            new SamplePrompt("샘플 프롬프트 2", "https://cdn.example.com/samples/2.png"),
            new SamplePrompt("샘플 프롬프트 3", "https://cdn.example.com/samples/3.png"),
            new SamplePrompt("샘플 프롬프트 4", "https://cdn.example.com/samples/4.png"));

    @Test
    @DisplayName("방을 생성하면 호스트가 첫 참가자로 등록되고 LOBBY 상태가 된다.")
    void create_방을_생성하면_호스트가_첫_참가자이고_로비_상태다() {
        // given
        Player host = new Player("호스트");

        // when
        GameRoom room = GameRoom.create("ABCD", host);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getCode()).isEqualTo("ABCD");
            softly.assertThat(room.getHostId()).isEqualTo(host.getId());
            softly.assertThat(room.getPhase()).isEqualTo(GamePhase.LOBBY);
            softly.assertThat(room.getPlayers()).containsExactly(host);
        });
    }

    @Test
    @DisplayName("참가자를 추가하면 목록에 포함되고 참가자 id를 반환한다.")
    void addPlayer_참가자를_추가하면_목록에_포함되고_id를_반환한다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        Player guest = new Player("참가자");

        // when
        String playerId = room.addPlayer(guest);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(playerId).isEqualTo(guest.getId());
            softly.assertThat(room.getPlayers()).contains(guest);
            softly.assertThat(room.getPlayers()).hasSize(2);
        });
    }

    @Test
    @DisplayName("정원(8명)이 가득 찬 방에 참가자를 추가하면 RoomFullException을 던진다.")
    void addPlayer_정원이_가득_차면_예외를_던진다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        for (int i = 1; i <= 7; i++) {
            room.addPlayer(new Player("참가자" + i));
        }

        // when & then
        assertThatThrownBy(() -> room.addPlayer(new Player("초과")))
                .isInstanceOf(RoomFullException.class)
                .hasMessage("방 정원이 가득 찼습니다.");
    }

    @Test
    @DisplayName("이미 사용 중인 닉네임으로 참가자를 추가하면 DuplicateNicknameException을 던진다.")
    void addPlayer_닉네임이_중복되면_예외를_던진다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        room.addPlayer(new Player("참가자"));

        // when & then
        assertThatThrownBy(() -> room.addPlayer(new Player("참가자")))
                .isInstanceOf(DuplicateNicknameException.class)
                .hasMessage("이미 사용 중인 닉네임입니다.");
    }

    @Test
    @DisplayName("앞뒤 공백만 다른 닉네임으로 참가하면 DuplicateNicknameException을 던진다.")
    void addPlayer_공백만_다른_닉네임은_중복으로_처리한다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        room.addPlayer(new Player("참가자"));

        // when & then
        assertThatThrownBy(() -> room.addPlayer(new Player("  참가자  ")))
                .isInstanceOf(DuplicateNicknameException.class)
                .hasMessage("이미 사용 중인 닉네임입니다.");
    }

    @Test
    @DisplayName("로비 단계가 아닌 방에 참가자를 추가하면 GameAlreadyStartedException을 던진다.")
    void addPlayer_이미_시작된_게임이면_예외를_던진다() throws Exception {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        setPhase(room, GamePhase.GENERATING);

        // when & then
        assertThatThrownBy(() -> room.addPlayer(new Player("참가자")))
                .isInstanceOf(GameAlreadyStartedException.class)
                .hasMessage("이미 시작된 게임입니다.");
    }

    @Test
    @DisplayName("참가자가 방을 나가면 목록에서 제거되고 방장은 유지된다.")
    void removePlayer_참가자가_나가면_목록에서_제거되고_방장은_유지된다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest = new Player("참가자");
        room.addPlayer(guest);

        // when
        boolean removed = room.removePlayer(guest.getId());

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(removed).isTrue();
            softly.assertThat(room.getPlayers()).containsExactly(host);
            softly.assertThat(room.getHostId()).isEqualTo(host.getId());
        });
    }

    @Test
    @DisplayName("방장이 방을 나가면 남은 참가자 중에서 새 방장이 선정된다.")
    void removePlayer_방장이_나가면_남은_참가자_중에서_새_방장이_선정된다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);

        // when
        boolean removed = room.removePlayer(host.getId());

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(removed).isTrue();
            softly.assertThat(room.getPlayers()).containsExactly(guest1, guest2);
            softly.assertThat(room.getHostId()).isIn(guest1.getId(), guest2.getId());
        });
    }

    @Test
    @DisplayName("마지막 참가자가 나가면 방이 빈 상태가 된다.")
    void removePlayer_마지막_참가자가_나가면_방이_빈_상태가_된다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);

        // when
        boolean removed = room.removePlayer(host.getId());

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(removed).isTrue();
            softly.assertThat(room.isEmpty()).isTrue();
        });
    }

    @Test
    @DisplayName("방에 없는 플레이어를 제거하면 false를 반환하고 목록은 변하지 않는다.")
    void removePlayer_방에_없는_플레이어면_false를_반환하고_목록은_그대로다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);

        // when
        boolean removed = room.removePlayer("unknown-player-id");

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(removed).isFalse();
            softly.assertThat(room.getPlayers()).containsExactly(host);
            softly.assertThat(room.getHostId()).isEqualTo(host.getId());
        });
    }

    @Test
    @DisplayName("참가자의 준비 상태를 변경하면 해당 참가자에게 반영된다.")
    void changePlayerReady_준비_상태를_변경하면_반영된다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        Player guest = new Player("참가자");
        room.addPlayer(guest);

        // when
        room.changePlayerReady(guest.getId(), true);

        // then
        Player found = room.getPlayers().stream()
                .filter(player -> player.getId().equals(guest.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(found.isReady()).isTrue();
    }

    @Test
    @DisplayName("로비 단계가 아니면 준비 상태를 변경할 때 GameAlreadyStartedException을 던진다.")
    void changePlayerReady_이미_시작된_게임이면_예외를_던진다() throws Exception {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        setPhase(room, GamePhase.GENERATING);

        // when & then
        assertThatThrownBy(() -> room.changePlayerReady(host.getId(), true))
                .isInstanceOf(GameAlreadyStartedException.class)
                .hasMessage("이미 시작된 게임입니다.");
    }

    @Test
    @DisplayName("방에 없는 플레이어의 준비 상태 변경은 예외 없이 무시한다.")
    void changePlayerReady_방에_없는_플레이어면_무시한다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);

        // when & then
        assertThatCode(() -> room.changePlayerReady("unknown-player-id", true))
                .doesNotThrowAnyException();
        assertThat(host.isReady()).isFalse();
    }

    @Test
    @DisplayName("방장이 시작하면 방장 외 모든 참가자가 준비되고 3명 이상일 때 GENERATING 단계로 진행한다.")
    void start_방장이_조건을_충족하면_다음_단계로_진행한다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);

        // when
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);

        // then
        assertThat(room.getPhase()).isEqualTo(GamePhase.GENERATING);
    }

    @Test
    @DisplayName("방장이 시작하면 프롬프트 시작 시각과 마감 시각을 저장한다.")
    void start_조건을_충족하면_프롬프트_마감_시각을_저장한다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);

        // when
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getPromptStartedAt()).isEqualTo(PROMPT_STARTED_AT);
            softly.assertThat(room.getPromptDeadline()).isEqualTo(PROMPT_STARTED_AT.plus(PROMPT_DURATION));
        });
    }

    @Test
    @DisplayName("방장이 시작하면 플레이어별 프롬프트 입력 상태를 대기 상태로 만든다.")
    void start_조건을_충족하면_플레이어별_프롬프트_입력_상태를_초기화한다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);

        // when
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getPromptEntries())
                    .extracting(PromptEntry::getPlayerId)
                    .containsExactly(host.getId(), guest1.getId(), guest2.getId());
            softly.assertThat(room.getPromptEntries())
                    .extracting(PromptEntry::getStatus)
                    .containsOnly(PromptEntryStatus.WAITING);
        });
    }

    @Test
    @DisplayName("GENERATING 단계에서 프롬프트를 제출하면 입력 상태를 저장한다.")
    void submitPrompt_GENERATING_단계이면_프롬프트를_저장한다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);
        Instant submittedAt = Instant.parse("2026-07-06T10:00:29Z");

        // when
        room.submitPrompt(guest1.getId(), "고양이가 피아노를 치는 장면", submittedAt);

        // then
        PromptEntry entry = findPromptEntry(room, guest1.getId());
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getPrompt()).isEqualTo("고양이가 피아노를 치는 장면");
            softly.assertThat(entry.getSubmittedAt()).isEqualTo(submittedAt);
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.GENERATING);
        });
    }

    @Test
    @DisplayName("추측 마감 시 미제출 참가자에게 닉네임 기반 추측을 자동 제출한다.")
    void autoSubmitGuesses_미제출_참가자에게_자동_추측을_제출한다() throws Exception {
        // given
        GameRoom room = createRoomInGuessing();
        String guest1Id = room.getPlayers().get(1).getId();
        String guest2Id = room.getPlayers().get(2).getId();
        room.submitGuess(guest1Id, "강아지가 기타를 치는 장면", GUESS_STARTED_AT);
        Instant deadline = room.getGuessDeadline();

        // when
        room.autoSubmitGuesses(deadline);

        // then
        List<GuessEntry> guesses = room.getCurrentRound().getGuesses();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.hasAllCurrentRoundGuesses()).isTrue();
            softly.assertThat(guesses).extracting(GuessEntry::getPlayerId)
                    .containsExactlyInAnyOrder(guest1Id, guest2Id);
            softly.assertThat(guessOf(guesses, guest1Id).getGuess()).isEqualTo("강아지가 기타를 치는 장면");
            softly.assertThat(guessOf(guesses, guest2Id).getGuess()).isIn(autoPromptCandidates("참가자2"));
            softly.assertThat(guessOf(guesses, guest2Id).getSubmittedAt()).isEqualTo(deadline);
        });
    }

    @Test
    @DisplayName("자동 추측은 출제자에게는 제출하지 않는다.")
    void autoSubmitGuesses_출제자에게는_제출하지_않는다() throws Exception {
        // given
        GameRoom room = createRoomInGuessing();
        String questionerId = room.getCurrentRound().getQuestionerId();

        // when
        room.autoSubmitGuesses(room.getGuessDeadline());

        // then
        assertThat(room.getCurrentRound().getGuesses())
                .extracting(GuessEntry::getPlayerId)
                .doesNotContain(questionerId);
    }

    @Test
    @DisplayName("PERFECT 플레이어가 가짜 프롬프트를 내지 않으면 마감 시 자동 추측을 채운다.")
    void autoSubmitGuesses_PERFECT_플레이어에게_가짜_프롬프트를_채운다() throws Exception {
        // given
        GameRoom room = createRoomInGuessing();
        String guest1Id = room.getPlayers().get(1).getId();
        String guest2Id = room.getPlayers().get(2).getId();
        String answerPrompt = room.getCurrentRound().getAnswerEntry().getPrompt();
        room.submitGuess(guest1Id, answerPrompt.replaceAll("\\s+", ""), GUESS_STARTED_AT);
        room.submitGuess(guest2Id, "강아지가 기타를 치는 장면", GUESS_STARTED_AT);

        // when
        room.autoSubmitGuesses(room.getGuessDeadline());

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.hasAllCurrentRoundGuesses()).isTrue();
            softly.assertThat(room.getCurrentRound().getGuesses())
                    .extracting(GuessEntry::getPlayerId)
                    .containsExactlyInAnyOrder(guest1Id, guest2Id);
        });
    }

    @Test
    @DisplayName("모든 제출 프롬프트의 이미지가 READY 상태가 되면 생성 완료로 판단한다.")
    void hasAllImagesGenerated_모든_이미지가_READY이면_true를_반환한다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);
        room.submitPrompt(host.getId(), "호스트 프롬프트", PROMPT_STARTED_AT);
        room.submitPrompt(guest1.getId(), "참가자1 프롬프트", PROMPT_STARTED_AT);
        room.submitPrompt(guest2.getId(), "참가자2 프롬프트", PROMPT_STARTED_AT);
        room.completeImageGeneration(host.getId(), "https://cdn.example.com/host.png");
        room.completeImageGeneration(guest1.getId(), "https://cdn.example.com/guest-1.png");

        // when & then
        assertThat(room.hasAllImagesGenerated()).isFalse();

        room.completeImageGeneration(guest2.getId(), "https://cdn.example.com/guest-2.png");

        assertThat(room.hasAllImagesGenerated()).isTrue();
    }

    @Test
    @DisplayName("이미지 생성이 하나라도 실패하면 생성 완료로 판단하지 않는다.")
    void hasAllImagesGenerated_이미지_생성이_실패하면_false를_반환한다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);
        room.submitPrompt(host.getId(), "호스트 프롬프트", PROMPT_STARTED_AT);
        room.submitPrompt(guest1.getId(), "참가자1 프롬프트", PROMPT_STARTED_AT);
        room.submitPrompt(guest2.getId(), "참가자2 프롬프트", PROMPT_STARTED_AT);
        room.completeImageGeneration(host.getId(), "https://cdn.example.com/host.png");
        room.completeImageGeneration(guest1.getId(), "https://cdn.example.com/guest-1.png");
        room.failImageGeneration(guest2.getId());

        // when & then
        assertThat(room.hasAllImagesGenerated()).isFalse();
    }

    @Test
    @DisplayName("이미지 마감 시 READY가 아닌 엔트리를 샘플로 채워 전원 READY로 만든다.")
    void fillMissingImagesWithSamples_READY가_아닌_엔트리를_채워_전원_READY로_만든다() {
        // given
        GameRoom room = createGeneratingRoomWithMissingImages();

        // when
        room.fillMissingImagesWithSamples(SAMPLE_POOL, PROMPT_DEADLINE);

        // then
        assertThat(room.hasAllImagesGenerated()).isTrue();
    }

    @Test
    @DisplayName("이미 READY인 엔트리는 샘플로 덮어쓰지 않는다.")
    void fillMissingImagesWithSamples_이미_READY인_엔트리는_유지한다() {
        // given
        GameRoom room = createGeneratingRoomWithMissingImages();
        String hostId = room.getHostId();

        // when
        room.fillMissingImagesWithSamples(SAMPLE_POOL, PROMPT_DEADLINE);

        // then
        PromptEntry hostEntry = findPromptEntry(room, hostId);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(hostEntry.getPrompt()).isEqualTo("호스트 프롬프트");
            softly.assertThat(hostEntry.getImageUrl()).isEqualTo("https://cdn.example.com/host.png");
        });
    }

    @Test
    @DisplayName("샘플로 채운 플레이어와 배정된 샘플을 배정 내역으로 반환한다.")
    void fillMissingImagesWithSamples_배정_내역을_반환한다() {
        // given
        GameRoom room = createGeneratingRoomWithMissingImages();
        String hostId = room.getHostId();
        List<String> missingPlayerIds = room.getPlayers().stream()
                .map(Player::getId)
                .filter(id -> !id.equals(hostId))
                .toList();

        // when
        Map<String, SamplePrompt> assignments = room.fillMissingImagesWithSamples(SAMPLE_POOL, PROMPT_DEADLINE);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(assignments.keySet()).containsExactlyInAnyOrderElementsOf(missingPlayerIds);
            softly.assertThat(assignments).doesNotContainKey(hostId);
            softly.assertThat(assignments.values()).allMatch(SAMPLE_POOL::contains);
        });
    }

    @Test
    @DisplayName("샘플 풀이 채울 인원보다 많으면 서로 다른 샘플을 배정한다.")
    void fillMissingImagesWithSamples_풀이_충분하면_서로_다른_샘플을_배정한다() {
        // given
        GameRoom room = createGeneratingRoomWithMissingImages();

        // when
        Map<String, SamplePrompt> assignments = room.fillMissingImagesWithSamples(SAMPLE_POOL, PROMPT_DEADLINE);

        // then
        assertThat(assignments.values()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("이미지 생성 중(GENERATING)인 참가자는 이미지 생성 진행 중으로 판단한다.")
    void isImageGenerationInProgress_생성_중이면_true를_반환한다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);
        room.submitPrompt(host.getId(), "호스트 프롬프트", PROMPT_STARTED_AT);

        // when & then
        assertThat(room.isImageGenerationInProgress(host.getId())).isTrue();
    }

    @Test
    @DisplayName("이미 READY인 참가자는 이미지 생성 진행 중이 아니라고 판단한다.")
    void isImageGenerationInProgress_READY면_false를_반환한다() {
        // given
        GameRoom room = createGeneratingRoomWithMissingImages();

        // when & then
        assertThat(room.isImageGenerationInProgress(room.getHostId())).isFalse();
    }

    @Test
    @DisplayName("이미지 생성 단계가 아니면 샘플을 채우지 않고 빈 배정 내역을 반환한다.")
    void fillMissingImagesWithSamples_생성_단계가_아니면_아무것도_하지_않는다() throws Exception {
        // given
        GameRoom room = createGeneratingRoomWithMissingImages();
        setPhase(room, GamePhase.PLAYING);

        // when
        Map<String, SamplePrompt> assignments = room.fillMissingImagesWithSamples(SAMPLE_POOL, PROMPT_DEADLINE);

        // then
        assertThat(assignments).isEmpty();
    }

    @Test
    @DisplayName("예약된 마감 시각이 현재 방의 마감 시각과 다르면 오래된 만료 작업으로 판단한다.")
    void isPromptExpirationStale_마감_시각이_다르면_true를_반환한다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);

        // when & then
        assertThat(room.isPromptExpirationStale(PROMPT_STARTED_AT.plusSeconds(29))).isTrue();
    }

    @Test
    @DisplayName("예약된 마감 시각이 현재 방의 마감 시각과 같으면 유효한 만료 작업으로 판단한다.")
    void isPromptExpirationStale_마감_시각이_같으면_false를_반환한다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);

        // when & then
        assertThat(room.isPromptExpirationStale(PROMPT_STARTED_AT.plus(PROMPT_DURATION))).isFalse();
    }

    @Test
    @DisplayName("모든 이미지가 생성됐으면 PLAYING 단계로 전환한다.")
    void advanceToPlaying_모든_이미지가_생성됐으면_PLAYING으로_전환한다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);
        room.submitPrompt(host.getId(), "호스트 프롬프트", PROMPT_STARTED_AT);
        room.submitPrompt(guest1.getId(), "참가자1 프롬프트", PROMPT_STARTED_AT);
        room.submitPrompt(guest2.getId(), "참가자2 프롬프트", PROMPT_STARTED_AT);
        room.completeImageGeneration(host.getId(), "https://cdn.example.com/host.png");
        room.completeImageGeneration(guest1.getId(), "https://cdn.example.com/guest-1.png");
        room.completeImageGeneration(guest2.getId(), "https://cdn.example.com/guest-2.png");

        // when
        room.advanceToPlaying();

        // then
        assertThat(room.getPhase()).isEqualTo(GamePhase.PLAYING);
    }

    @Test
    @DisplayName("이미지 생성이 완료되지 않았으면 PLAYING 단계로 전환할 수 없다.")
    void advanceToPlaying_이미지가_생성되지_않았으면_예외를_던진다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);

        // when & then
        assertThatThrownBy(room::advanceToPlaying)
                .isInstanceOf(ImagesNotReadyException.class)
                .hasMessage("모든 플레이어의 이미지가 생성된 후 게임을 진행할 수 있습니다.");
        assertThat(room.getPhase()).isEqualTo(GamePhase.GENERATING);
    }

    @Test
    @DisplayName("마감 시각 이후 프롬프트를 제출하면 PromptSubmissionExpiredException을 던지고 대기 상태를 유지한다.")
    void submitPrompt_마감_시각_이후이면_예외를_던지고_대기_상태를_유지한다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);
        Instant expiredAt = Instant.parse("2026-07-06T10:00:31Z");

        // when & then
        assertThatThrownBy(() -> room.submitPrompt(guest1.getId(), "늦은 프롬프트", expiredAt))
                .isInstanceOf(PromptSubmissionExpiredException.class)
                .hasMessage("프롬프트 제출 시간이 만료되었습니다.");
        assertThat(findPromptEntry(room, guest1.getId()).getStatus()).isEqualTo(PromptEntryStatus.WAITING);
    }

    @Test
    @DisplayName("GENERATING 단계가 아니면 프롬프트 제출 시 PromptSubmissionNotAllowedException을 던진다.")
    void submitPrompt_GENERATING_단계가_아니면_예외를_던진다() {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));

        // when & then
        assertThatThrownBy(() -> room.submitPrompt("player-id", "프롬프트", Instant.now()))
                .isInstanceOf(PromptSubmissionNotAllowedException.class)
                .hasMessage("프롬프트를 제출할 수 있는 단계가 아닙니다.");
    }

    @Test
    @DisplayName("이미 제출한 플레이어가 다시 제출하면 DuplicatePromptSubmissionException을 던진다.")
    void submitPrompt_이미_제출한_플레이어이면_예외를_던진다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);
        room.submitPrompt(guest1.getId(), "첫 번째 프롬프트", PROMPT_STARTED_AT);

        // when & then
        assertThatThrownBy(() -> room.submitPrompt(guest1.getId(), "두 번째 프롬프트", PROMPT_STARTED_AT))
                .isInstanceOf(DuplicatePromptSubmissionException.class)
                .hasMessage("이미 프롬프트를 제출했습니다.");
    }

    @Test
    @DisplayName("이미지 생성에 실패한 플레이어는 마감 전 프롬프트를 다시 제출할 수 있다.")
    void submitPrompt_이미지_생성에_실패한_플레이어이면_마감_전_다시_제출할_수_있다() {
        // given
        GameRoom room = createGeneratingRoomWithMissingImages();
        String guest2Id = room.getPlayers().get(2).getId();

        // when
        room.submitPrompt(guest2Id, "다시 입력한 프롬프트", PROMPT_STARTED_AT.plusSeconds(1));

        // then
        PromptEntry entry = findPromptEntry(room, guest2Id);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(entry.getPrompt()).isEqualTo("다시 입력한 프롬프트");
            softly.assertThat(entry.getStatus()).isEqualTo(PromptEntryStatus.GENERATING);
            softly.assertThat(entry.getImageUrl()).isNull();
        });
    }

    @Test
    @DisplayName("방장이 아닌 참가자가 시작하면 NotHostException을 던진다.")
    void start_방장이_아니면_예외를_던진다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);

        // when & then
        assertThatThrownBy(() -> room.start(guest1.getId(), PROMPT_STARTED_AT, PROMPT_DURATION))
                .isInstanceOf(NotHostException.class)
                .hasMessage("방장만 게임을 시작할 수 있습니다.");
    }

    @Test
    @DisplayName("1명 시작 정책이면 호스트만으로 게임을 시작한다.")
    void start_1명_시작_정책이면_호스트만으로_시작한다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host, GameStartPolicy.local());

        // when
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);

        // then
        assertThat(room.getPhase()).isEqualTo(GamePhase.GENERATING);
    }

    @Test
    @DisplayName("참가자가 3명 미만이면 시작할 때 InsufficientPlayersException을 던진다.")
    void start_참가자가_3명_미만이면_예외를_던진다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest = new Player("참가자");
        room.addPlayer(guest);
        room.changePlayerReady(guest.getId(), true);

        // when & then
        assertThatThrownBy(() -> room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION))
                .isInstanceOf(InsufficientPlayersException.class)
                .hasMessage("게임을 시작하려면 최소 3명이 필요합니다.");
    }

    @Test
    @DisplayName("방장 외 준비하지 않은 참가자가 있으면 시작할 때 PlayersNotReadyException을 던진다.")
    void start_준비하지_않은_참가자가_있으면_예외를_던진다() {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);

        // when & then
        assertThatThrownBy(() -> room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION))
                .isInstanceOf(PlayersNotReadyException.class)
                .hasMessage("모든 참가자가 준비되지 않았습니다.");
    }

    @Test
    @DisplayName("이미 시작된 게임을 다시 시작하면 GameAlreadyStartedException을 던진다.")
    void start_이미_시작된_게임이면_예외를_던진다() throws Exception {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        room.addPlayer(new Player("참가자1"));
        room.addPlayer(new Player("참가자2"));
        setPhase(room, GamePhase.GENERATING);

        // when & then
        assertThatThrownBy(() -> room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION))
                .isInstanceOf(GameAlreadyStartedException.class)
                .hasMessage("이미 시작된 게임입니다.");
    }

    @Test
    @DisplayName("PLAYING 단계의 방에서 라운드를 시작하면 참여 순서대로 라운드를 만들고 첫 라운드의 추측 마감을 설정한다.")
    void startRounds_PLAYING_단계이면_첫_라운드를_시작한다() throws Exception {
        // given
        GameRoom room = createRoomWithGeneratedImages();
        setPhase(room, GamePhase.PLAYING);
        String hostId = room.getPlayers().get(0).getId();

        // when
        room.startRounds(GUESS_STARTED_AT, GUESS_DURATION);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getPhase()).isEqualTo(GamePhase.PLAYING);
            softly.assertThat(room.getTotalRoundCount()).isEqualTo(3);
            softly.assertThat(room.getCurrentRound().getRoundNumber()).isEqualTo(1);
            softly.assertThat(room.getCurrentRound().getQuestionerId()).isEqualTo(hostId);
            softly.assertThat(room.getCurrentRound().getAnswerEntry().getPrompt()).isEqualTo("호스트 프롬프트");
            softly.assertThat(room.getGuessStartedAt()).isEqualTo(GUESS_STARTED_AT);
            softly.assertThat(room.getGuessDeadline()).isEqualTo(GUESS_STARTED_AT.plus(GUESS_DURATION));
        });
    }

    @Test
    @DisplayName("PLAYING 단계가 아니면 라운드 시작 시 RoundStartNotAllowedException을 던진다.")
    void startRounds_PLAYING_단계가_아니면_예외를_던진다() {
        // given
        GameRoom room = createRoomWithGeneratedImages();

        // when & then
        assertThatThrownBy(() -> room.startRounds(GUESS_STARTED_AT, GUESS_DURATION))
                .isInstanceOf(RoundStartNotAllowedException.class)
                .hasMessage("라운드를 시작할 수 없는 상태입니다.");
    }

    @Test
    @DisplayName("이미 라운드가 시작된 방에서 다시 시작하면 RoundStartNotAllowedException을 던진다.")
    void startRounds_이미_시작됐으면_예외를_던진다() throws Exception {
        // given
        GameRoom room = createRoomInGuessing();

        // when & then
        assertThatThrownBy(() -> room.startRounds(GUESS_STARTED_AT, GUESS_DURATION))
                .isInstanceOf(RoundStartNotAllowedException.class)
                .hasMessage("라운드를 시작할 수 없는 상태입니다.");
    }

    @Test
    @DisplayName("READY 상태의 이미지가 하나도 없으면 라운드 시작 시 RoundStartNotAllowedException을 던진다.")
    void startRounds_READY_이미지가_없으면_예외를_던진다() throws Exception {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);
        setPhase(room, GamePhase.PLAYING);

        // when & then
        assertThatThrownBy(() -> room.startRounds(GUESS_STARTED_AT, GUESS_DURATION))
                .isInstanceOf(RoundStartNotAllowedException.class)
                .hasMessage("라운드를 시작할 수 없는 상태입니다.");
    }

    @Test
    @DisplayName("현재 참여자 중 READY 이미지가 아닌 사람이 있으면 부분 라운드를 시작하지 않는다.")
    void startRounds_READY_이미지가_일부만_있으면_부분_라운드를_시작하지_않는다() throws Exception {
        // given
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);
        room.submitPrompt(host.getId(), "호스트 프롬프트", PROMPT_STARTED_AT);
        room.submitPrompt(guest1.getId(), "참가자1 프롬프트", PROMPT_STARTED_AT);
        room.submitPrompt(guest2.getId(), "참가자2 프롬프트", PROMPT_STARTED_AT);
        room.completeImageGeneration(host.getId(), "https://cdn.example.com/host.png");
        room.completeImageGeneration(guest1.getId(), "https://cdn.example.com/guest-1.png");
        room.failImageGeneration(guest2.getId());
        setPhase(room, GamePhase.PLAYING);

        // when & then
        assertThatThrownBy(() -> room.startRounds(GUESS_STARTED_AT, GUESS_DURATION))
                .isInstanceOf(RoundStartNotAllowedException.class)
                .hasMessage("라운드를 시작할 수 없는 상태입니다.");

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getCurrentRound()).isNull();
            softly.assertThat(room.getTotalRoundCount()).isZero();
            softly.assertThat(room.getGuessDeadline()).isNull();
        });
    }

    @Test
    @DisplayName("PLAYING 단계에서 추측을 제출하면 현재 라운드에 저장한다.")
    void submitGuess_PLAYING_단계이면_현재_라운드에_저장한다() throws Exception {
        // given
        GameRoom room = createRoomInGuessing();
        String guest1Id = room.getPlayers().get(1).getId();

        // when
        room.submitGuess(guest1Id, "강아지가 기타를 치는 장면", GUESS_STARTED_AT);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getCurrentRound().getGuesses()).hasSize(1);
            softly.assertThat(room.getCurrentRound().getGuesses().get(0).getPlayerId()).isEqualTo(guest1Id);
            softly.assertThat(room.getCurrentRound().getGuesses().get(0).getGuess())
                    .isEqualTo("강아지가 기타를 치는 장면");
        });
    }

    @Test
    @DisplayName("PLAYING 단계가 아니면 추측 제출 시 GuessSubmissionNotAllowedException을 던진다.")
    void submitGuess_PLAYING_단계가_아니면_예외를_던진다() {
        // given
        GameRoom room = createRoomWithGeneratedImages();

        // when & then
        assertThatThrownBy(() -> room.submitGuess("player-id", "추측", GUESS_STARTED_AT))
                .isInstanceOf(GuessSubmissionNotAllowedException.class)
                .hasMessage("추측을 제출할 수 있는 단계가 아닙니다.");
    }

    @Test
    @DisplayName("마감 시각 이후 추측을 제출하면 GuessSubmissionExpiredException을 던진다.")
    void submitGuess_마감_이후이면_예외를_던진다() throws Exception {
        // given
        GameRoom room = createRoomInGuessing();
        String guest1Id = room.getPlayers().get(1).getId();
        Instant expiredAt = GUESS_STARTED_AT.plus(GUESS_DURATION).plusSeconds(1);

        // when & then
        assertThatThrownBy(() -> room.submitGuess(guest1Id, "늦은 추측", expiredAt))
                .isInstanceOf(GuessSubmissionExpiredException.class)
                .hasMessage("추측 제출 시간이 만료되었습니다.");
        assertThat(room.getCurrentRound().getGuesses()).isEmpty();
    }

    @Test
    @DisplayName("출제자를 제외한 전원이 추측을 제출하면 VOTING 단계로 전환한다.")
    void completeGuessSubmission_전원이_제출하면_VOTING으로_전환한다() throws Exception {
        // given
        GameRoom room = createRoomInGuessing();
        String guest1Id = room.getPlayers().get(1).getId();
        String guest2Id = room.getPlayers().get(2).getId();
        room.submitGuess(guest1Id, "강아지가 기타를 치는 장면", GUESS_STARTED_AT);
        room.submitGuess(guest2Id, "고양이가 드럼을 치는 장면", GUESS_STARTED_AT);

        // when
        room.completeGuessSubmission(VOTING_OPENED_AT, VOTE_DURATION);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.hasAllCurrentRoundGuesses()).isTrue();
            softly.assertThat(room.getPhase()).isEqualTo(GamePhase.VOTING);
        });
    }

    @Test
    @DisplayName("마감 시각이 지나면 미제출자가 있어도 VOTING 단계로 전환한다.")
    void completeGuessSubmission_마감_이후이면_VOTING으로_전환한다() throws Exception {
        // given
        GameRoom room = createRoomInGuessing();
        String guest1Id = room.getPlayers().get(1).getId();
        room.submitGuess(guest1Id, "강아지가 기타를 치는 장면", GUESS_STARTED_AT);

        // when
        room.completeGuessSubmission(GUESS_STARTED_AT.plus(GUESS_DURATION).plusSeconds(1), VOTE_DURATION);

        // then
        assertThat(room.getPhase()).isEqualTo(GamePhase.VOTING);
    }

    @Test
    @DisplayName("마감 전이고 미제출자가 있으면 추측 제출을 종료하지 않는다.")
    void completeGuessSubmission_마감_전이고_미제출자가_있으면_전환하지_않는다() throws Exception {
        // given
        GameRoom room = createRoomInGuessing();
        String guest1Id = room.getPlayers().get(1).getId();
        room.submitGuess(guest1Id, "강아지가 기타를 치는 장면", GUESS_STARTED_AT);

        // when
        room.completeGuessSubmission(GUESS_STARTED_AT.plusSeconds(10), VOTE_DURATION);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.hasAllCurrentRoundGuesses()).isFalse();
            softly.assertThat(room.getPhase()).isEqualTo(GamePhase.PLAYING);
        });
    }

    @Test
    @DisplayName("추측 마감 시각이 예약 시점과 다르면 만료 작업을 무시하도록 stale로 판단한다.")
    void isGuessExpirationStale_마감_시각이_다르면_true를_반환한다() throws Exception {
        // given
        GameRoom room = createRoomInGuessing();
        Instant deadline = GUESS_STARTED_AT.plus(GUESS_DURATION);

        // when & then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.isGuessExpirationStale(deadline)).isFalse();
            softly.assertThat(room.isGuessExpirationStale(deadline.plusSeconds(1))).isTrue();
        });
    }

    @Test
    @DisplayName("추측이 끝나 VOTING으로 전환하면 보기 목록을 열고 투표 마감 시각을 설정한다.")
    void completeGuessSubmission_VOTING_전환_시_보기를_열고_마감을_설정한다() throws Exception {
        // given
        GameRoom room = createRoomInGuessing();
        String guest1Id = room.getPlayers().get(1).getId();
        String guest2Id = room.getPlayers().get(2).getId();
        room.submitGuess(guest1Id, "강아지가 기타를 치는 장면", GUESS_STARTED_AT);
        room.submitGuess(guest2Id, "고양이가 드럼을 치는 장면", GUESS_STARTED_AT);

        // when
        room.completeGuessSubmission(VOTING_OPENED_AT, VOTE_DURATION);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getPhase()).isEqualTo(GamePhase.VOTING);
            softly.assertThat(room.getCurrentRound().getVoteOptions()).hasSize(3);
            softly.assertThat(room.getVoteStartedAt()).isEqualTo(VOTING_OPENED_AT);
            softly.assertThat(room.getVoteDeadline()).isEqualTo(VOTING_OPENED_AT.plus(VOTE_DURATION));
        });
    }

    @Test
    @DisplayName("투표 단계에서 출제자는 본인 이미지(ownImage=true), 추측자는 본인 보기 id를 담아 반환한다.")
    void getCurrentRoundOwnVoteOptions_투표_단계에서_출제자와_추측자별_본인_보기_정보를_반환한다() throws Exception {
        // given
        GameRoom room = createRoomInVoting();
        String hostId = room.getPlayers().get(0).getId();
        String guest1Id = room.getPlayers().get(1).getId();
        String guest2Id = room.getPlayers().get(2).getId();
        List<GuessEntry> guesses = room.getCurrentRound().getGuesses();
        String guess1OptionId = guessOf(guesses, guest1Id).getGuessId();
        String guess2OptionId = guessOf(guesses, guest2Id).getGuessId();

        // when
        Map<String, OwnVoteOption> ownVoteOptions = room.getCurrentRoundOwnVoteOptions();

        // then
        assertThat(ownVoteOptions).containsOnly(
                entry(hostId, OwnVoteOption.forQuestioner()),
                entry(guest1Id, OwnVoteOption.forGuesser(guess1OptionId)),
                entry(guest2Id, OwnVoteOption.forGuesser(guess2OptionId)));
    }

    @Test
    @DisplayName("라운드가 시작되지 않았으면 본인 보기 매핑은 비어 있다.")
    void getCurrentRoundOwnVoteOptions_라운드_시작_전이면_빈_매핑을_반환한다() {
        // given
        GameRoom room = createRoomWithGeneratedImages();

        // when
        Map<String, OwnVoteOption> ownVoteOptions = room.getCurrentRoundOwnVoteOptions();

        // then
        assertThat(ownVoteOptions).isEmpty();
    }

    @Test
    @DisplayName("VOTING 단계에서 투표하면 현재 라운드에 표를 저장한다.")
    void submitVote_VOTING_단계이면_현재_라운드에_저장한다() throws Exception {
        // given
        GameRoom room = createRoomInVoting();
        String guest1Id = room.getPlayers().get(1).getId();
        String answerOptionId = room.getCurrentRound().getAnswerEntry().getPromptId();

        // when
        room.submitVote(guest1Id, answerOptionId, VOTING_OPENED_AT.plusSeconds(1));

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getCurrentRound().getVotes()).hasSize(1);
            softly.assertThat(room.getCurrentRound().getVotes().get(0).getVoterId()).isEqualTo(guest1Id);
            softly.assertThat(room.getCurrentRound().getVotes().get(0).getOptionId()).isEqualTo(answerOptionId);
        });
    }

    @Test
    @DisplayName("VOTING 단계가 아니면 투표 시 VoteSubmissionNotAllowedException을 던진다.")
    void submitVote_VOTING_단계가_아니면_예외를_던진다() throws Exception {
        // given
        GameRoom room = createRoomInGuessing();

        // when & then
        assertThatThrownBy(() -> room.submitVote("player-id", "option-id", VOTING_OPENED_AT))
                .isInstanceOf(VoteSubmissionNotAllowedException.class)
                .hasMessage("투표할 수 있는 단계가 아닙니다.");
    }

    @Test
    @DisplayName("마감 시각 이후 투표하면 VoteSubmissionExpiredException을 던진다.")
    void submitVote_마감_이후이면_예외를_던진다() throws Exception {
        // given
        GameRoom room = createRoomInVoting();
        String guest1Id = room.getPlayers().get(1).getId();
        String answerOptionId = room.getCurrentRound().getAnswerEntry().getPromptId();
        Instant expiredAt = VOTING_OPENED_AT.plus(VOTE_DURATION).plusSeconds(1);

        // when & then
        assertThatThrownBy(() -> room.submitVote(guest1Id, answerOptionId, expiredAt))
                .isInstanceOf(VoteSubmissionExpiredException.class)
                .hasMessage("투표 시간이 만료되었습니다.");
        assertThat(room.getCurrentRound().getVotes()).isEmpty();
    }

    @Test
    @DisplayName("출제자를 제외한 전원이 투표하면 RESULTS 단계로 전환한다.")
    void completeVoting_전원이_투표하면_RESULTS로_전환한다() throws Exception {
        // given
        GameRoom room = createRoomInVoting();
        String guest1Id = room.getPlayers().get(1).getId();
        String guest2Id = room.getPlayers().get(2).getId();
        String answerOptionId = room.getCurrentRound().getAnswerEntry().getPromptId();
        room.submitVote(guest1Id, answerOptionId, VOTING_OPENED_AT.plusSeconds(1));
        room.submitVote(guest2Id, answerOptionId, VOTING_OPENED_AT.plusSeconds(2));

        // when
        room.completeVoting(VOTING_OPENED_AT.plusSeconds(3), RESULT_DURATION);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.hasAllCurrentRoundVotes()).isTrue();
            softly.assertThat(room.getPhase()).isEqualTo(GamePhase.RESULTS);
            softly.assertThat(room.getResultStartedAt()).isEqualTo(VOTING_OPENED_AT.plusSeconds(3));
        });
    }

    @Test
    @DisplayName("투표 마감 시각이 지나면 미투표자가 있어도 RESULTS 단계로 전환한다.")
    void completeVoting_마감_이후이면_RESULTS로_전환한다() throws Exception {
        // given
        GameRoom room = createRoomInVoting();
        String guest1Id = room.getPlayers().get(1).getId();
        String answerOptionId = room.getCurrentRound().getAnswerEntry().getPromptId();
        room.submitVote(guest1Id, answerOptionId, VOTING_OPENED_AT.plusSeconds(1));

        // when
        room.completeVoting(VOTING_OPENED_AT.plus(VOTE_DURATION).plusSeconds(1), RESULT_DURATION);

        // then
        assertThat(room.getPhase()).isEqualTo(GamePhase.RESULTS);
    }

    @Test
    @DisplayName("투표 마감 전이고 미투표자가 있으면 투표를 종료하지 않는다.")
    void completeVoting_마감_전이고_미투표자가_있으면_전환하지_않는다() throws Exception {
        // given
        GameRoom room = createRoomInVoting();
        String guest1Id = room.getPlayers().get(1).getId();
        String answerOptionId = room.getCurrentRound().getAnswerEntry().getPromptId();
        room.submitVote(guest1Id, answerOptionId, VOTING_OPENED_AT.plusSeconds(1));

        // when
        room.completeVoting(VOTING_OPENED_AT.plusSeconds(2), RESULT_DURATION);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.hasAllCurrentRoundVotes()).isFalse();
            softly.assertThat(room.getPhase()).isEqualTo(GamePhase.VOTING);
        });
    }

    @Test
    @DisplayName("투표 마감 시각이 예약 시점과 다르면 만료 작업을 무시하도록 stale로 판단한다.")
    void isVoteExpirationStale_마감_시각이_다르면_true를_반환한다() throws Exception {
        // given
        GameRoom room = createRoomInVoting();
        Instant deadline = VOTING_OPENED_AT.plus(VOTE_DURATION);

        // when & then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.isVoteExpirationStale(deadline)).isFalse();
            softly.assertThat(room.isVoteExpirationStale(deadline.plusSeconds(1))).isTrue();
        });
    }

    @Test
    @DisplayName("RESULTS로 전환하면 라운드 점수를 플레이어에게 한 번만 반영한다.")
    void completeVoting_RESULTS로_전환하면_점수를_한_번만_반영한다() throws Exception {
        // given
        GameRoom room = createRoomInVoting();
        String hostId = room.getPlayers().get(0).getId();
        String guest1Id = room.getPlayers().get(1).getId();
        String guest2Id = room.getPlayers().get(2).getId();
        String answerOptionId = room.getCurrentRound().getAnswerEntry().getPromptId();
        room.submitVote(guest1Id, answerOptionId, VOTING_OPENED_AT.plusSeconds(1));
        room.submitVote(guest2Id, answerOptionId, VOTING_OPENED_AT.plusSeconds(2));

        // when
        room.completeVoting(VOTING_OPENED_AT.plusSeconds(3), RESULT_DURATION);
        room.completeVoting(VOTING_OPENED_AT.plusSeconds(4), RESULT_DURATION);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(scoreOf(room, hostId)).isZero();
            softly.assertThat(scoreOf(room, guest1Id)).isEqualTo(2);
            softly.assertThat(scoreOf(room, guest2Id)).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("마지막 라운드가 아니면 결과 확인 후 다음 라운드로 넘어가고 추측 마감을 설정한다.")
    void advanceRound_마지막_라운드가_아니면_다음_라운드로_넘어간다() throws Exception {
        // given
        GameRoom room = createRoomInResults();
        String guest1Id = room.getPlayers().get(1).getId();
        Instant nextGuessStartedAt = RESULTS_OPENED_AT.plus(RESULT_DURATION);

        // when
        room.advanceRound(nextGuessStartedAt, GUESS_DURATION);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getPhase()).isEqualTo(GamePhase.PLAYING);
            softly.assertThat(room.getCurrentRound().getRoundNumber()).isEqualTo(2);
            softly.assertThat(room.getCurrentRound().getQuestionerId()).isEqualTo(guest1Id);
            softly.assertThat(room.getCurrentRound().getAnswerEntry().getPrompt()).isEqualTo("참가자1 프롬프트");
            softly.assertThat(room.getGuessStartedAt()).isEqualTo(nextGuessStartedAt);
            softly.assertThat(room.getGuessDeadline()).isEqualTo(nextGuessStartedAt.plus(GUESS_DURATION));
        });
    }

    @Test
    @DisplayName("마지막 라운드의 결과 확인이 끝나면 ENDED 단계로 전환한다.")
    void advanceRound_마지막_라운드면_ENDED로_전환한다() throws Exception {
        // given
        GameRoom room = createRoomInResults();
        setField(room, "currentRoundIndex", room.getTotalRoundCount() - 1);

        // when
        room.advanceRound(RESULTS_OPENED_AT.plus(RESULT_DURATION), GUESS_DURATION);

        // then
        assertThat(room.getPhase()).isEqualTo(GamePhase.ENDED);
    }

    @Test
    @DisplayName("RESULTS 단계가 아니면 라운드 진행 시 RoundAdvanceNotAllowedException을 던진다.")
    void advanceRound_RESULTS_단계가_아니면_예외를_던진다() throws Exception {
        // given
        GameRoom room = createRoomInVoting();

        // when & then
        assertThatThrownBy(() -> room.advanceRound(VOTING_OPENED_AT, GUESS_DURATION))
                .isInstanceOf(RoundAdvanceNotAllowedException.class)
                .hasMessage("라운드를 진행할 수 없는 상태입니다.");
    }

    @Test
    @DisplayName("결과 마감 시각이 예약 시점과 다르면 만료 작업을 무시하도록 stale로 판단한다.")
    void isResultExpirationStale_마감_시각이_다르면_true를_반환한다() throws Exception {
        // given
        GameRoom room = createRoomInResults();
        Instant deadline = RESULTS_OPENED_AT.plus(RESULT_DURATION);

        // when & then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.isResultExpirationStale(deadline)).isFalse();
            softly.assertThat(room.isResultExpirationStale(deadline.plusSeconds(1))).isTrue();
        });
    }

    @Test
    @DisplayName("최종 순위는 누적 점수 내림차순으로 정렬한다.")
    void getFinalRanking_점수_내림차순으로_정렬한다() throws Exception {
        // given
        GameRoom room = createRoomInVoting();
        String hostId = room.getPlayers().get(0).getId();
        String guest1Id = room.getPlayers().get(1).getId();
        String guest2Id = room.getPlayers().get(2).getId();
        String answerOptionId = room.getCurrentRound().getAnswerEntry().getPromptId();
        String guess1OptionId = room.getCurrentRound().getGuesses().get(0).getGuessId();
        // guest1: 정답(+2) + guest2가 낚임(+1) = 3, host(출제자): 정답자 1명(+2) = 2, guest2: 오답 = 0
        room.submitVote(guest1Id, answerOptionId, VOTING_OPENED_AT.plusSeconds(1));
        room.submitVote(guest2Id, guess1OptionId, VOTING_OPENED_AT.plusSeconds(2));
        room.completeVoting(VOTING_OPENED_AT.plusSeconds(3), RESULT_DURATION);

        // when
        List<Player> ranking = room.getFinalRanking();

        // then
        assertThat(ranking)
                .extracting(Player::getId)
                .containsExactly(guest1Id, hostId, guest2Id);
    }

    private GameRoom createRoomInVoting() throws Exception {
        GameRoom room = createRoomInGuessing();
        String guest1Id = room.getPlayers().get(1).getId();
        String guest2Id = room.getPlayers().get(2).getId();
        room.submitGuess(guest1Id, "강아지가 기타를 치는 장면", GUESS_STARTED_AT);
        room.submitGuess(guest2Id, "고양이가 드럼을 치는 장면", GUESS_STARTED_AT);
        room.completeGuessSubmission(VOTING_OPENED_AT, VOTE_DURATION);
        return room;
    }

    private GameRoom createRoomInResults() throws Exception {
        GameRoom room = createRoomInVoting();
        String guest1Id = room.getPlayers().get(1).getId();
        String guest2Id = room.getPlayers().get(2).getId();
        String answerOptionId = room.getCurrentRound().getAnswerEntry().getPromptId();
        room.submitVote(guest1Id, answerOptionId, VOTING_OPENED_AT.plusSeconds(1));
        room.submitVote(guest2Id, answerOptionId, VOTING_OPENED_AT.plusSeconds(2));
        room.completeVoting(RESULTS_OPENED_AT, RESULT_DURATION);
        return room;
    }

    private int scoreOf(GameRoom room, String playerId) {
        return room.getPlayers().stream()
                .filter(player -> player.getId().equals(playerId))
                .findFirst()
                .orElseThrow()
                .getScore();
    }

    private GameRoom createRoomWithGeneratedImages() {
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);
        room.submitPrompt(host.getId(), "호스트 프롬프트", PROMPT_STARTED_AT);
        room.submitPrompt(guest1.getId(), "참가자1 프롬프트", PROMPT_STARTED_AT);
        room.submitPrompt(guest2.getId(), "참가자2 프롬프트", PROMPT_STARTED_AT);
        room.completeImageGeneration(host.getId(), "https://cdn.example.com/host.png");
        room.completeImageGeneration(guest1.getId(), "https://cdn.example.com/guest-1.png");
        room.completeImageGeneration(guest2.getId(), "https://cdn.example.com/guest-2.png");
        return room;
    }

    // host: READY, 참가자1: WAITING(무제출), 참가자2: FAILED 로 GENERATING 단계를 만든다.
    private GameRoom createGeneratingRoomWithMissingImages() {
        Player host = new Player("호스트");
        GameRoom room = GameRoom.create("ABCD", host);
        Player guest1 = new Player("참가자1");
        Player guest2 = new Player("참가자2");
        room.addPlayer(guest1);
        room.addPlayer(guest2);
        room.changePlayerReady(guest1.getId(), true);
        room.changePlayerReady(guest2.getId(), true);
        room.start(host.getId(), PROMPT_STARTED_AT, PROMPT_DURATION);
        room.submitPrompt(host.getId(), "호스트 프롬프트", PROMPT_STARTED_AT);
        room.completeImageGeneration(host.getId(), "https://cdn.example.com/host.png");
        room.submitPrompt(guest2.getId(), "참가자2 프롬프트", PROMPT_STARTED_AT);
        room.failImageGeneration(guest2.getId());
        return room;
    }

    private GameRoom createRoomInGuessing() throws Exception {
        GameRoom room = createRoomWithGeneratedImages();
        setPhase(room, GamePhase.PLAYING);
        room.startRounds(GUESS_STARTED_AT, GUESS_DURATION);
        return room;
    }

    private void setPhase(GameRoom room, GamePhase phase) throws Exception {
        setField(room, "phase", phase);
    }

    private void setField(GameRoom room, String name, Object value) throws Exception {
        Field field = GameRoom.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(room, value);
    }

    private PromptEntry findPromptEntry(GameRoom room, String playerId) {
        return room.getPromptEntries().stream()
                .filter(entry -> entry.getPlayerId().equals(playerId))
                .findFirst()
                .orElseThrow();
    }

    private GuessEntry guessOf(List<GuessEntry> guesses, String playerId) {
        return guesses.stream()
                .filter(entry -> entry.getPlayerId().equals(playerId))
                .findFirst()
                .orElseThrow();
    }

    private List<String> autoPromptCandidates(String nickname) {
        return Arrays.stream(AutoPromptPrefix.values())
                .map(prefix -> prefix.value() + " " + nickname)
                .toList();
    }
}
