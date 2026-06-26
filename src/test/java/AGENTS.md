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
