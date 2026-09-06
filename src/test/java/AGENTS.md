# Test Guidelines

## 테스트 작성 규칙

- 테스트 패키지는 대상 코드의 패키지와 맞춘다.
- 테스트 클래스 이름은 `*Test`로 끝나게 작성한다.
- 테스트 메서드 이름은 검증하려는 동작이 드러나게 작성한다.
- 테스트는 given-when-then 구조를 따른다.
- `@DisplayName`은 한글로 작성하여 테스트 목적을 명시한다.
    - 예: `@DisplayName("사용자 조회 시 존재하지 않는 ID면 예외를 던진다.")`
- 각 테스트는 필요한 데이터를 직접 준비하고, 다른 테스트 실행 순서에 의존하지 않는다.
- `@SpringBootTest`는 전체 Spring Context가 필요한 경우에만 사용한다.
- service layer 테스트는 `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)`로 최소한의 Context만 띄운다.
- 단위 테스트로 충분한 경우 Spring Context를 띄우지 않는다.

## 테스트 유형 선택

- 순수 도메인·계산 로직은 Spring Context 없이 테스트한다.
- Service 테스트는 필요한 의존성 범위에 따라 단위테스트 또는 최소 Spring Context를 선택한다.
- Controller·WebSocket 계약은 해당 경계를 검증하는 테스트를 작성한다.
- 동시성·재연결·시간 기반 로직은 경계 조건과 경쟁 상황을 포함한다.

## 테스트 책임

- 하나의 테스트는 하나의 동작 또는 하나의 결과만 검증한다.
- 테스트 이름은 검증 대상 동작을 하나만 드러낸다.
- 조건 확인, 예약 등록, 예약 실행 결과처럼 독립적으로 관찰 가능한 동작은 각각 다른 테스트로 분리한다.
- 하나의 동작을 검증하기 위한 복수 assertion은 허용한다.
- 공통 준비 과정은 private helper로 추출하되, helper가 검증 대상 동작을 대신 실행하지 않도록 한다.

## 테스트 완료 기준

- 성공 케이스와 주요 실패 케이스를 검증한다.
- 버그 수정 테스트는 수정 전 실패하고 수정 후 성공하는 시나리오를 표현한다.
- 테스트 이름만 읽어도 검증 동작을 알 수 있어야 한다.
- 테스트 실행 결과를 작업 보고에 기록한다.

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

- API 테스트를 작성할 때는 REST Docs 문서화를 위한 스니핏도 함께 작성한다.
- 문서화 대상은 API 테스트이며, 성공 케이스와 예외 케이스를 모두 포함한다.
- API 요청/응답의 path parameter, header, request body, response body는 테스트에서 검증한 실제 계약을 기준으로 문서화한다.

## WebSocket API 문서화

- WebSocket API 문서는 REST Docs가 아니라 실제 STOMP E2E 테스트 결과와 snippet으로 AsyncAPI 명세를 생성한다.
- WebSocket endpoint, SEND destination, 구독 destination, 메시지 타입·상태·payload를 변경하면 관련 E2E 테스트와 문서 snippet을 함께 수정한다.
- 문서화 테스트는 실제 WebSocket 연결을 열고 요청을 전송한 뒤, 실제 수신 frame을 기다려 핵심 필드를 검증하고 snippet을 작성한다.
- 요청마다 operation ID, 제목, tag, 전송 조건, destination, message ID, 설명, payload 예시를 기록한다.
- 수신 메시지는 실제 frame을 기준으로 message ID, 메시지 타입, destination, scope, relationship, client action, tag, payload 예시를 기록한다.
- 성공·실패·경계 상태를 문서화한다. 하나의 요청으로 여러 메시지가 발생하면 각 메시지의 destination과 발생 관계를 모두 기록한다.
- `/topic/rooms/{roomCode}`는 방 전체 broadcast, `/user/queue/*`는 특정 사용자에게 전달되는 개인 메시지로 구분한다.
- 클라이언트는 destination과 메시지의 `type` 또는 `status`로 분기하므로 해당 필드를 실제 계약과 함께 검증한다.
- 명시적으로 보장되지 않은 메시지 도착 순서에 테스트가 의존하지 않도록 한다.
- 일반 연결·구독 안내는 `src/test/resources/websocket-docs/overview.md`에 작성하고, 개별 operation·message 계약은 E2E 테스트 snippet에서 관리한다.
- `build/generated-snippets/websocket`과 `build/generated/websocket-docs`의 생성 파일은 직접 수정하지 않는다.
- 문서 계약 변경 후 다음 명령으로 snippet 생성과 AsyncAPI 검증을 수행한다.
  - `npm ci`
  - `./gradlew validateWebSocketDocs --no-daemon`
- HTML 문서까지 확인해야 하면 `./gradlew generateWebSocketDocs --no-daemon`을 추가로 실행한다.
- operation·channel·message·schema·enum 계약이 추가되거나 변경되면 문서 생성 테스트의 명시적 assertion도 함께 갱신한다.
