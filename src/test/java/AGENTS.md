# Test Guidelines

## 테스트 작성 규칙

- 대상 코드와 같은 패키지에 `*Test` 클래스를 작성하고, 테스트명·`@DisplayName`에 검증 동작을 명시한다.
- given-when-then 구조를 따르고, 테스트별 데이터를 직접 준비해 실행 순서에 의존하지 않게 한다.
- 순수 로직은 Spring Context 없이, Service는 필요할 때만 `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)`를
  사용한다.
- Controller·WebSocket 계약은 해당 경계를, 동시성·재연결·시간 기반 로직은 경계 조건과 경쟁 상황을 검증한다.
- 하나의 테스트는 하나의 동작·결과만 검증한다. 독립적인 조건·결과는 분리하고, 관련 복수 assertion은 허용한다.
- 공통 준비는 private helper로 추출하되 검증 대상 동작은 대신 실행하지 않게 한다.

## 테스트 완료 기준

- 성공·주요 실패·경계 케이스를 검증하고, 버그 수정은 재현·회귀 시나리오를 포함한다.
- 테스트 이름만 읽어도 검증 동작을 알 수 있게 하고, 실행 결과를 작업 보고에 기록한다.

## 검증 방식

- 예외 테스트는 `assertThatThrownBy`로 예외 타입과 메시지를 함께 검증한다.

``` java
assertThatThrownBy(() -> service.findById(unknownId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("사용자를 찾을 수 없습니다.");
```

- 복수 검증은 `SoftAssertions.assertSoftly`를 우선 고려한다.

``` java
SoftAssertions.assertSoftly(softly -> {
    softly.assertThat(response.name()).isEqualTo("홍길동");
    softly.assertThat(response.age()).isEqualTo(20);
});
```

## API 문서화

- API 테스트에는 성공·예외 케이스의 REST Docs snippet을 작성하고, path parameter·header·request/response body는 실제 검증 계약을 기준으로 기록한다.

## WebSocket API 문서화

- 실제 STOMP E2E 테스트의 frame과 snippet으로 AsyncAPI를 생성하며, endpoint·destination·메시지 계약 변경 시 E2E 테스트·snippet·명시적 assertion을 함께
  수정한다.
- 실제 연결·요청·수신 frame을 검증하고, 요청의 operation ID·destination·payload와 수신의 message ID·type/status·scope·relationship·payload를
  기록한다.
- 성공·실패·경계 상태와 하나의 요청에서 발생하는 모든 메시지를 문서화한다. `/topic/rooms/{roomCode}`는 broadcast, `/user/queue/*`는 개인 메시지다.
- 클라이언트는 destination과 `type` 또는 `status`로 분기하며, 보장되지 않은 메시지 순서에 의존하지 않는다.
- 연결·구독 안내는 `src/test/resources/websocket-docs/overview.md`, operation·message 계약은 E2E snippet에서 관리한다.
- `build/generated-snippets/websocket`과 `build/generated/websocket-docs`는 직접 수정하지 않는다. 검증은 `npm ci` 후
  `./gradlew validateWebSocketDocs --no-daemon`, HTML 확인은 `./gradlew generateWebSocketDocs --no-daemon`을 사용한다.
