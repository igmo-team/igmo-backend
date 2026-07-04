package com.igmo.web;

import com.igmo.web.dto.CreateGameRequest;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.ErrorResponse;
import com.igmo.web.dto.JoinGameRequest;
import com.igmo.web.dto.JoinGameResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@Tag(name = "Games", description = "게임 방 생성, 참여, 퇴장 API")
public interface GameApiSpecification {

    String CREATE_GAME_RESPONSE_EXAMPLE = """
            {
              "roomCode": "ABCD",
              "playerId": "host-id",
              "secret": "host-secret",
              "snapshot": {
                "roomCode": "ABCD",
                "phase": "LOBBY",
                "hostId": "host-id",
                "players": [
                  {
                    "id": "host-id",
                    "nickname": "host",
                    "score": 0
                  }
                ]
              }
            }
            """;

    String JOIN_GAME_RESPONSE_EXAMPLE = """
            {
              "playerId": "guest-id",
              "secret": "guest-secret",
              "snapshot": {
                "roomCode": "ABCD",
                "phase": "LOBBY",
                "hostId": "host-id",
                "players": [
                  {
                    "id": "host-id",
                    "nickname": "host",
                    "score": 0
                  },
                  {
                    "id": "guest-id",
                    "nickname": "guest",
                    "score": 0
                  }
                ]
              }
            }
            """;

    String ERROR_RESPONSE_EXAMPLE = """
            {
              "message": "방을 찾을 수 없습니다."
            }
            """;

    @Operation(summary = "게임 방 생성", description = "호스트 닉네임으로 새 게임 방을 생성합니다.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "게임 방 생성 성공",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CreateGameResponse.class),
                            examples = @ExampleObject(value = CREATE_GAME_RESPONSE_EXAMPLE))),
            @ApiResponse(
                    responseCode = "400",
                    description = "닉네임이 비어 있거나 길이 정책에 맞지 않음",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = ERROR_RESPONSE_EXAMPLE))),
            @ApiResponse(
                    responseCode = "503",
                    description = "방 코드 생성 실패",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "message": "방 코드를 생성하지 못했습니다. 잠시 후 다시 시도해주세요."
                                    }
                                    """)))
    })
    ResponseEntity<CreateGameResponse> createGame(@RequestBody CreateGameRequest request);

    @Operation(summary = "게임 방 참여", description = "기존 게임 방에 새 플레이어로 참여합니다.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "게임 방 참여 성공",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = JoinGameResponse.class),
                            examples = @ExampleObject(value = JOIN_GAME_RESPONSE_EXAMPLE))),
            @ApiResponse(
                    responseCode = "400",
                    description = "닉네임이 비어 있거나 길이 정책에 맞지 않음",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = ERROR_RESPONSE_EXAMPLE))),
            @ApiResponse(
                    responseCode = "403",
                    description = "방 정원이 가득 참",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "message": "방 정원이 가득 찼습니다."
                                    }
                                    """))),
            @ApiResponse(
                    responseCode = "404",
                    description = "방을 찾을 수 없음",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = ERROR_RESPONSE_EXAMPLE))),
            @ApiResponse(
                    responseCode = "409",
                    description = "닉네임 중복 또는 이미 시작한 게임",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "message": "이미 사용 중인 닉네임입니다."
                                    }
                                    """)))
    })
    ResponseEntity<JoinGameResponse> joinGame(
            @Parameter(description = "참여할 방 코드", example = "ABCD") @PathVariable String code,
            @RequestBody JoinGameRequest request);

    @Operation(summary = "게임 방 퇴장", description = "플레이어 secret을 검증한 뒤 방에서 퇴장합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "게임 방 퇴장 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "X-Player-Secret 헤더 누락",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "message": "필수 요청 헤더 'X-Player-Secret'이 없습니다."
                                    }
                                    """))),
            @ApiResponse(
                    responseCode = "403",
                    description = "플레이어 secret 불일치",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "message": "플레이어 인증 정보가 일치하지 않습니다."
                                    }
                                    """))),
            @ApiResponse(
                    responseCode = "404",
                    description = "방 또는 플레이어를 찾을 수 없음",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = ERROR_RESPONSE_EXAMPLE)))
    })
    ResponseEntity<Void> leaveGame(
            @Parameter(description = "방 코드", example = "ABCD") @PathVariable String code,
            @Parameter(description = "퇴장할 플레이어 ID", example = "player-id") @PathVariable String playerId,
            @Parameter(description = "플레이어 인증 secret", example = "player-secret")
            @RequestHeader("X-Player-Secret") String secret);
}
