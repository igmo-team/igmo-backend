package com.igmo.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoomCodeGeneratorTest {

    @Test
    @DisplayName("생성된 코드는 4자리 대문자 영문이다.")
    void generate_네_자리_대문자_영문_코드를_생성한다() {
        // given
        RoomCodeGenerator generator = new RoomCodeGenerator();

        // when & then
        for (int i = 0; i < 100; i++) {
            assertThat(generator.generate()).matches("[A-Z]{4}");
        }
    }
}
