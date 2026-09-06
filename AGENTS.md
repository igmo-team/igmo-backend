# Repository Guidelines

## 프로젝트 개요

- Spring Boot 기반 IGMO 실시간 게임 백엔드다.
- REST API, WebSocket/STOMP 게임 흐름, API·WebSocket 문서를 제공한다.
- Java/Gradle 애플리케이션과 Node.js·Python 검증 도구를 사용한다.

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

## 빌드·테스트 명령

- 단위·통합 테스트: `./gradlew test --no-daemon`
- WebSocket 문서 검증: `./gradlew validateWebSocketDocs --no-daemon`
- WebSocket 문서 검증 전 Node.js 의존성 설치: `npm ci`
- 모니터링 배포 테스트: `python3 -B -m unittest discover -s deploy/monitoring/tests -p 'test_*.py' -v`
- 변경 범위에 해당하는 검증 명령을 실행하고 결과를 최종 보고에 기록한다.

## 코드 작성 규칙

- 현재 요구사항에 필요한 코드만 추가한다.
- 새 의존성은 기존 코드나 표준 라이브러리로 해결하기 어려울 때만 추가한다.
- `src/main/java` 하위 코드를 수정할 때만 `src/main/java/AGENTS.md`를 참고한다.

## 테스트

- 테스트는 `src/test/java`에 둔다.
- 애플리케이션 동작·도메인 규칙·API 계약을 변경하면 관련 테스트를 추가하거나 수정한다.
- 버그 수정은 재현 테스트 또는 회귀 테스트를 포함한다.
- 순수 로직은 단위테스트를 우선하고, HTTP·WebSocket·Spring 경계 변경은 적절한 통합 테스트 또는 E2E 테스트를 작성한다.
- 문서·주석·단순 이름 변경처럼 동작이 바뀌지 않는 변경은 테스트 대상에서 제외할 수 있다.
- 테스트 없이 동작 변경을 완료 상태로 보고하지 않는다.
- 테스트 작성 전 `src/test/java/AGENTS.md`를 확인한다.

## 지침 읽기 범위

- 단순 질문, 문서 수정, PR 본문 작성만 하는 경우 하위 `AGENTS.md` 파일은 읽지 않는다.
- 작업 대상이 애플리케이션 코드나 테스트 코드로 확정된 뒤 필요한 하위 지침만 읽는다.

## PR

- PR 본문에는 `closed #이슈번호`, As-Is, To-Be, 체크리스트를 작성한다.

## 커밋

- 커밋 제목은 `<type>: <간결한 요약>` 형식을 따른다.
- 허용 type은 `feat`, `fix`, `refactor`, `test`, `docs`, `chore`다.
- 제목은 한 줄로 작성하고 변경 대상과 목적을 포함한다.
- 본문은 bullet point로 작성한다.
- 하나의 커밋에는 하나의 목적만 포함한다.
- 관련 없는 변경을 같은 커밋에 포함하지 않는다.
- 커밋 전 `git diff --check`와 관련 테스트를 실행한다.
- `.env`, 비밀값, 생성 산출물을 커밋하지 않는다.

## 완료 기준

- 요청한 동작이 구현되었다.
- 변경된 동작에 관련 테스트가 추가·수정되었다.
- 관련 검증 명령이 성공했다.
- 테스트를 생략하면 최종 보고에 사유를 기록한다.
- 실패한 검증이 있으면 원인과 미해결 상태를 보고한다.

## Review guidelines

- PR 리뷰 작업일 때만 `.github/codex/review-guidelines.md`를 참고한다.
