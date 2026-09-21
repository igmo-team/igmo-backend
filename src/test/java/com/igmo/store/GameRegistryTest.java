package com.igmo.store;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GameRegistryTest {

    @Test
    @DisplayName("새로운 코드면 방을 저장하고 true를 반환한다.")
    void saveIfAbsent_새_코드면_저장하고_참을_반환한다() {
        // given
        GameRegistry registry = new GameRegistry();
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));

        // when
        boolean saved = registry.saveIfAbsent(room);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(saved).isTrue();
            softly.assertThat(registry.find("ABCD")).contains(room);
        });
    }

    @Test
    @DisplayName("이미 존재하는 코드면 저장하지 않고 false를 반환하며 기존 방을 덮어쓰지 않는다.")
    void saveIfAbsent_이미_있는_코드면_저장하지_않고_거짓을_반환한다() {
        // given
        GameRegistry registry = new GameRegistry();
        GameRoom existing = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        registry.saveIfAbsent(existing);
        GameRoom duplicate = GameRoom.create("ABCD", new Player("다른호스트"), Duration.ofMinutes(10));

        // when
        boolean saved = registry.saveIfAbsent(duplicate);

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(saved).isFalse();
            softly.assertThat(registry.find("ABCD")).contains(existing);
        });
    }

    @Test
    @DisplayName("존재하지 않는 코드를 조회하면 빈 Optional을 반환한다.")
    void find_없는_코드면_빈_Optional을_반환한다() {
        // given
        GameRegistry registry = new GameRegistry();

        // when & then
        assertThat(registry.find("ZZZZ")).isEmpty();
    }

    @Test
    @DisplayName("같은 방 객체를 삭제하면 더 이상 조회되지 않는다.")
    void removeIfSame_같은방객체면_삭제한다() {
        // given
        GameRegistry registry = new GameRegistry();
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        registry.saveIfAbsent(room);

        // when
        boolean removed = registry.removeIfSame(room);

        // then
        assertThat(removed).isTrue();
        assertThat(registry.find("ABCD")).isEmpty();
    }

    @Test
    @DisplayName("같은 코드의 다른 방 객체로는 기존 방을 삭제하지 않는다.")
    void removeIfSame_다른방객체면_삭제하지_않는다() {
        // given
        GameRegistry registry = new GameRegistry();
        GameRoom existing = GameRoom.create("ABCD", new Player("호스트"), Duration.ofMinutes(10));
        GameRoom stale = GameRoom.create("ABCD", new Player("오래된 호스트"), Duration.ofMinutes(10));
        registry.saveIfAbsent(existing);

        // when
        boolean removed = registry.removeIfSame(stale);

        // then
        assertThat(removed).isFalse();
        assertThat(registry.find("ABCD")).contains(existing);
    }
}
