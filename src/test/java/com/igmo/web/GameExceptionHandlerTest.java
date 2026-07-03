package com.igmo.web;

import com.igmo.domain.exception.DuplicateNicknameException;
import com.igmo.domain.exception.GameAlreadyStartedException;
import com.igmo.domain.exception.RoomFullException;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomCodeGenerationFailedException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.service.exception.UnauthorizedPlayerException;
import com.igmo.web.dto.ErrorResponse;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GameExceptionHandlerTest {

    private final GameExceptionHandler handler = new GameExceptionHandler();

    @Test
    @DisplayName("RoomNotFoundException은 404와 메시지를 반환한다.")
    void handle_방을_찾을_수_없으면_404를_반환한다() {
        // when
        ResponseEntity<ErrorResponse> response = handler.handleNotFound(new RoomNotFoundException());

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            softly.assertThat(response.getBody().message()).isEqualTo("방을 찾을 수 없습니다.");
        });
    }

    @Test
    @DisplayName("PlayerNotFoundException은 404와 메시지를 반환한다.")
    void handle_플레이어를_찾을_수_없으면_404를_반환한다() {
        // when
        ResponseEntity<ErrorResponse> response = handler.handleNotFound(new PlayerNotFoundException());

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            softly.assertThat(response.getBody().message()).isEqualTo("방에 없는 플레이어입니다.");
        });
    }

    @Test
    @DisplayName("RoomFullException은 403과 메시지를 반환한다.")
    void handle_방이_가득_차면_403을_반환한다() {
        // when
        ResponseEntity<ErrorResponse> response = handler.handleForbidden(new RoomFullException());

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            softly.assertThat(response.getBody().message()).isEqualTo("방 정원이 가득 찼습니다.");
        });
    }

    @Test
    @DisplayName("UnauthorizedPlayerException은 403과 메시지를 반환한다.")
    void handle_본인이_아니면_403을_반환한다() {
        // when
        ResponseEntity<ErrorResponse> response = handler.handleForbidden(new UnauthorizedPlayerException());

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            softly.assertThat(response.getBody().message()).isEqualTo("본인만 퇴장할 수 있습니다.");
        });
    }

    @Test
    @DisplayName("GameAlreadyStartedException은 409와 메시지를 반환한다.")
    void handle_이미_시작된_게임이면_409를_반환한다() {
        // when
        ResponseEntity<ErrorResponse> response = handler.handleConflict(new GameAlreadyStartedException());

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            softly.assertThat(response.getBody().message()).isEqualTo("이미 시작된 게임입니다.");
        });
    }

    @Test
    @DisplayName("DuplicateNicknameException은 409와 메시지를 반환한다.")
    void handle_닉네임이_중복되면_409를_반환한다() {
        // when
        ResponseEntity<ErrorResponse> response = handler.handleConflict(new DuplicateNicknameException());

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            softly.assertThat(response.getBody().message()).isEqualTo("이미 사용 중인 닉네임입니다.");
        });
    }

    @Test
    @DisplayName("RoomCodeGenerationFailedException은 503과 메시지를 반환한다.")
    void handle_방_코드_발급_실패면_503을_반환한다() {
        // when
        ResponseEntity<ErrorResponse> response = handler.handleRoomCodeGenerationFailed(
                new RoomCodeGenerationFailedException());

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            softly.assertThat(response.getBody().message())
                    .isEqualTo("방 코드를 발급하지 못했습니다. 잠시 후 다시 시도해주세요.");
        });
    }
}
