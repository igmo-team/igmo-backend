package com.igmo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerTest {

    @Test
    @DisplayName("플레이어를 생성하면 준비 상태는 기본적으로 false다.")
    void 생성_시_준비_상태는_기본값_false다() {
        // given & when
        Player player = new Player("참가자");

        // then
        assertThat(player.isReady()).isFalse();
    }

    @Test
    @DisplayName("준비 상태를 true로 변경하면 isReady가 true를 반환한다.")
    void changeReady_준비_상태를_변경하면_반영된다() {
        // given
        Player player = new Player("참가자");

        // when
        player.changeReady(true);

        // then
        assertThat(player.isReady()).isTrue();
    }

    @Test
    @DisplayName("준비 상태를 다시 false로 변경하면 isReady가 false를 반환한다.")
    void changeReady_준비를_해제하면_반영된다() {
        // given
        Player player = new Player("참가자");
        player.changeReady(true);

        // when
        player.changeReady(false);

        // then
        assertThat(player.isReady()).isFalse();
    }
}
