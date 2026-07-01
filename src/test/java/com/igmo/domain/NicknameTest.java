package com.igmo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.igmo.domain.exception.InvalidNicknameException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NicknameTest {

    @Test
    @DisplayName("닉네임 앞뒤 공백을 제거한다.")
    void 앞뒤_공백을_제거한다() {
        // given & when
        Nickname nickname = new Nickname("  호스트  ");

        // then
        assertThat(nickname.value()).isEqualTo("호스트");
    }

    @Test
    @DisplayName("앞뒤 공백만 다른 닉네임은 같은 값으로 취급한다.")
    void 앞뒤_공백만_다른_닉네임은_같다() {
        // given & when
        Nickname nickname = new Nickname("호스트");
        Nickname padded = new Nickname("  호스트  ");

        // then
        assertThat(padded).isEqualTo(nickname);
    }

    @Test
    @DisplayName("공백 제거 후 길이가 최소 길이 미만이면 InvalidNicknameException을 던진다.")
    void 최소_길이_미만이면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> new Nickname(" a "))
                .isInstanceOf(InvalidNicknameException.class)
                .hasMessage("닉네임은 2자 이상 10자 이하여야 합니다.");
    }

    @Test
    @DisplayName("길이가 최대 길이를 초과하면 InvalidNicknameException을 던진다.")
    void 최대_길이_초과면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> new Nickname("가나다라마바사아자차카"))
                .isInstanceOf(InvalidNicknameException.class)
                .hasMessage("닉네임은 2자 이상 10자 이하여야 합니다.");
    }
}
