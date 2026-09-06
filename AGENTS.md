# Repository Guidelines

## 프로젝트 개요

- Spring Boot 기반 IGMO 실시간 게임 백엔드다. REST API와 WebSocket/STOMP 게임 흐름, API·WebSocket 문서를 제공한다.
- 주요 경로: `src/main/java/com/igmo/`, `src/test/java/`, `src/main/resources/application.yaml`, `.github/workflows/ci.yml`, `.github/pull_request_template.md`.

## 핵심 규칙

- Gradle은 항상 `./gradlew`로 실행한다.
- 새 환경 변수를 추가하면 `.env.example`도 함께 갱신한다.
- `.env` 파일은 읽거나 수정하지 않는다.

## 검증 명령

- 단위·통합 테스트: `./gradlew test --no-daemon`
- WebSocket 문서: `npm ci` 후 `./gradlew validateWebSocketDocs --no-daemon`
- 모니터링 테스트: `python3 -B -m unittest discover -s deploy/monitoring/tests -p 'test_*.py' -v`
- 변경 범위에 맞는 명령을 실행하고 결과를 보고한다.

## 코드·테스트 규칙

- 요구사항에 필요한 코드만 추가하고, 새 의존성은 기존 코드·표준 라이브러리로 해결할 수 없을 때만 추가한다.
- `src/main/java` 하위 코드를 수정하면 해당 경로의 `AGENTS.md`를 참고한다.
- 테스트는 `src/test/java`에 작성한다.
- 동작·도메인 규칙·API 계약 변경에는 관련 테스트를 추가·수정하고, 버그 수정에는 재현·회귀 테스트를 포함한다.
- 순수 로직은 단위테스트, HTTP·WebSocket·Spring 경계는 통합 또는 E2E 테스트를 우선한다.
- 문서·주석·단순 이름 변경은 제외할 수 있으나 사유를 보고한다. 테스트 작성 전 `src/test/java/AGENTS.md`를 확인한다.

## PR

- 본문은 존댓말로 작성하고, `As-Is`에는 문제·변경 필요성, `To-Be`에는 변경 내용·방법·이유, 체크리스트를 작성한다.
- 작업 브랜치 `<이슈번호>-<라벨>-<설명>`의 이슈 번호 뒤 라벨을 사용하고, 저장소에 존재하는 라벨만 지정한다. 예: `155-fix-hotfix` → `fix`.
- 제목은 `<라벨>: <변경 목적을 설명하는 한글 한 줄 문장>` 형식으로 작성하며, 제목 prefix와 실제 라벨을 일치시킨다.
- 닫을 이슈가 있을 때만 `closed #이슈번호`를 작성하고, Assignee는 작성자 본인, Reviewer는 별도 요청 시에만 지정한다.

## 커밋

- 제목은 `<type>: <간결한 요약>` 형식의 한 줄로 작성하고, type은 `feat`, `fix`, `refactor`, `test`, `docs`, `chore` 중 선택한다.
- 본문은 bullet point로 작성하며 하나의 목적만 포함한다. 관련 없는 변경·`.env`·비밀값·생성 산출물은 포함하지 않는다.
- 커밋 전 `git diff --check`와 관련 테스트를 실행한다.

## 완료 기준

- 요청한 동작과 관련 테스트가 반영되고 검증 명령이 성공해야 한다.
- 테스트 생략 또는 검증 실패 시 사유·원인·미해결 상태를 보고한다.

## Review guidelines

- PR 리뷰 작업일 때만 `.github/codex/review-guidelines.md`를 참고한다.
