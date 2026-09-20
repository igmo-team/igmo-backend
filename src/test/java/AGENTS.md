# Test Guidelines

## 테스트 작성 규칙

- 대상 코드와 같은 패키지에 `*Test` 클래스를 작성하고, 테스트명·`@DisplayName`에 검증 동작을 명시한다.
- given-when-then 구조를 따르고, 테스트별 데이터를 직접 준비해 실행 순서에 의존하지 않게 한다.
- @DisplayName은 한글 자연어로 작성하며 구현 방법보다 관찰 가능한 동작과 기대 결과를 표현하고, 실행 결과를 작업 보고에 기록한다.
- 순수 로직은 Spring Context 없이, Service는 필요할 때만 `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)`를
  사용한다.
- Controller·WebSocket 계약은 해당 경계를, 동시성·재연결·시간 기반 로직은 경계 조건과 경쟁 상황을 검증한다.
- 하나의 테스트는 하나의 동작·결과만 검증한다. 독립적인 조건·결과는 분리하고, 관련 복수 assertion은 허용한다.
- 공통 준비는 private helper로 추출하되 검증 대상 동작은 대신 실행하지 않게 한다.
- 구현 또는 변경한 기능에 대해서 성공·주요 실패·경계 케이스를 검증하고, 버그 수정은 재현·회귀 시나리오를 포함한다.

## 외부 의존성 통합 테스트

- Redis 동작은 Mockito 대신 Testcontainers의 실제 Redis로 검증하고, 단위 테스트와 통합 테스트를 구분한다.
- `@Container static` 컨테이너를 공유하며, 각 테스트 전에 `FLUSHDB`로 상태를 격리한다.
- 컨테이너 포트는 동적으로 매핑하고 로컬 Compose·운영 Redis에 연결하지 않으며, Docker 실행을 전제한다.

## 검증 방식

- 예외 테스트는 `assertThatThrownBy`로 예외 타입과 메시지를 함께 검증하며, when과 then을 한 번에 검증하면 `// when // then` 주석 뒤에 assertion chain을
  작성한다.

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
- 실제 연결·요청·수신 frame을 검증하고, 요청의 operation ID·destination을 기록한다. payload가 있는 요청만 payload 예시를 추가하고, 본문이 없으면 요청 body가 없음을
  명시한다. 수신 메시지는 message ID·type/status·scope·relationship·payload를 실제 계약에 맞춰 기록한다.
- 성공·실패·경계 상태와 하나의 요청에서 발생하는 모든 메시지를 문서화한다. `/topic/rooms/{roomCode}`는 broadcast, `/user/queue/*`는 개인 메시지다.
- 클라이언트는 destination으로 채널을 구분하고, 실제 `type` 또는 `status`가 제공되는 메시지만 해당 필드로 세부 분기·검증한다. 계약에 없는 필드는 추가하지 않으며, 보장되지 않은 메시지
  순서에 의존하지 않는다.
- 연결·구독 안내는 `src/test/resources/websocket-docs/overview.md`, operation·message 계약은 E2E snippet에서 관리한다.
- `build/generated-snippets/websocket`과 `build/generated/websocket-docs`는 직접 수정하지 않는다. 검증은 `npm ci` 후
  `./gradlew validateWebSocketDocs --no-daemon`, HTML 확인은 `./gradlew generateWebSocketDocs --no-daemon`을 사용한다.
