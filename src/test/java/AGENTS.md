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

## 테스트 책임

- 하나의 테스트는 하나의 동작 또는 하나의 결과만 검증한다.
- 테스트 이름은 검증 대상 동작을 하나만 드러낸다.
- 조건 확인, 예약 등록, 예약 실행 결과처럼 독립적으로 관찰 가능한 동작은 각각 다른 테스트로 분리한다.
- 하나의 동작을 검증하기 위한 복수 assertion은 허용한다.
- 공통 준비 과정은 private helper로 추출하되, helper가 검증 대상 동작을 대신 실행하지 않도록 한다.

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
