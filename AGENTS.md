# Repository Guidelines

## 프로젝트 구조

- `src/main/java/com/igmo/`: 애플리케이션 코드
- `src/main/resources/application.yaml`: Spring 설정
- `src/test/java/`: 테스트 코드
- `.github/workflows/ci.yml`: PR 테스트 워크플로우
- `.github/pull_request_template.md`: PR 템플릿

## 핵심 규칙

- Gradle 명령은 항상 `./gradlew`로 실행한다.
- 새 환경 변수를 추가하면 `.env.example`도 함께 갱신한다.
- `.env` 파일은 읽거나 수정하지 않는다.

## 코드 작성 규칙

- 현재 요구사항에 필요한 코드만 추가한다.
- 새 의존성은 기존 코드나 표준 라이브러리로 해결하기 어려울 때만 추가한다.
- `src/main/java` 하위 코드를 수정할 때만 `src/main/java/AGENTS.md`를 참고한다.

## 테스트

- 테스트는 `src/test/java`에 둔다.
- `src/test/java` 하위 코드를 수정할 때만 `src/test/java/AGENTS.md`를 참고한다.

## 지침 읽기 범위

- 단순 질문, 문서 수정, PR 본문 작성만 하는 경우 하위 `AGENTS.md` 파일은 읽지 않는다.
- 작업 대상이 애플리케이션 코드나 테스트 코드로 확정된 뒤 필요한 하위 지침만 읽는다.

## PR

- PR 본문에는 `closed #이슈번호`, As-Is, To-Be, 체크리스트를 작성한다.
