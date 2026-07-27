package com.igmo.domain;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GameStartPolicyTest {

    @Test
    @DisplayName("local 시작 정책은 1명부터 게임을 시작할 수 있다.")
    void local_시작_정책은_1명부터_게임을_시작할_수_있다() {
        // given
        GameStartPolicy policy = GameStartPolicy.local();

        // when & then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(policy.minimumPlayers()).isOne();
            softly.assertThat(policy.canStart(0)).isFalse();
            softly.assertThat(policy.canStart(1)).isTrue();
        });
    }

    @Test
    @DisplayName("standard 시작 정책은 3명부터 게임을 시작할 수 있다.")
    void standard_시작_정책은_3명부터_게임을_시작할_수_있다() {
        // given
        GameStartPolicy policy = GameStartPolicy.standard();

        // when & then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(policy.minimumPlayers()).isEqualTo(3);
            softly.assertThat(policy.canStart(2)).isFalse();
            softly.assertThat(policy.canStart(3)).isTrue();
        });
    }
}
