# CI/CD 배포 흐름

이 문서는 IGMO의 Pull Request 검증과 운영 배포 과정을 설명합니다. 애플리케이션 JAR과 API·WebSocket 문서는 GitHub Actions 러너에서 먼저 생성하고, Docker는 생성된 JAR을 실행 이미지로 패키징합니다.

## CI: Pull Request 검증

`prod` 브랜치를 대상으로 Pull Request를 만들면 `.github/workflows/ci.yml`이 실행됩니다.

1. JDK 25와 Node.js 24를 설정합니다.
2. `PUPPETEER_SKIP_DOWNLOAD=true npm ci`로 문서 생성 도구를 설치합니다.
3. `./gradlew validateWebSocketDocs --no-daemon`을 실행합니다.
   - Gradle 테스트를 실행합니다.
   - 테스트 결과로 만든 AsyncAPI 문서를 검증합니다.
4. 모니터링 배포 테스트를 실행합니다.

테스트나 문서 검증이 실패하면 CI workflow가 성공하지 않습니다. `prod` 브랜치 보호 규칙에서 이 workflow를 필수 검사로 지정해야 검증 실패 커밋의 병합을 막을 수 있습니다.

## CD: 운영 배포

다음 조건에서 `.github/workflows/cd.yml`이 실행됩니다.

- `prod` 브랜치에 Push
- GitHub Actions의 `workflow_dispatch` 수동 실행

### 1. 배포 설정 검증

GitHub Actions의 `production` 환경에 저장된 AWS, 게임, 이미지 저장소, 문서 서버 URL 설정을 먼저 확인합니다. 검증에 실패하면 AWS나 EC2에 접근하지 않습니다.

### 2. JAR과 문서 생성

일반 배포에서는 GitHub Actions 러너에서 다음 순서로 실행합니다.

```bash
PUPPETEER_SKIP_DOWNLOAD=true npm ci
./gradlew clean bootJar --no-daemon \
  -Popenapi.server-url="$OPENAPI_SERVER_URL" \
  -Pwebsocket-docs.server-url="$WEBSOCKET_DOCS_SERVER_URL"
```

`bootJar`는 테스트, OpenAPI 생성, AsyncAPI 검증, WebSocket HTML 문서 생성을 완료한 뒤 `build/libs/app.jar`를 만듭니다.

테스트에서 Docker가 필요한 경우 이 단계에서는 GitHub Actions 러너의 Docker 데몬을 사용합니다. Docker 이미지 빌드 내부에서 테스트를 실행하지 않으므로 Docker-in-Docker에 의존하지 않습니다.

### 3. Docker 이미지 생성

Dockerfile은 Gradle이나 Node.js를 설치하지 않습니다. `build/libs/app.jar`를 JRE 이미지에 복사해 실행 이미지를 만들고, ARM64 이미지로 Amazon ECR에 Push합니다.

```text
GitHub Actions 러너
  ├─ 테스트·API 문서·WebSocket 문서 생성
  ├─ build/libs/app.jar 생성
  └─ Docker Buildx로 app.jar 패키징
       └─ Amazon ECR: <ECR_REPOSITORY>:<DEPLOY_SHA>
```

이미지 빌드가 사용할 파일은 [Dockerfile](../Dockerfile)과 `build/libs/app.jar`입니다. Gradle 소스나 `node_modules`는 Docker 이미지에 포함하지 않습니다.

### 4. EC2 Blue/Green 배포

GitHub Actions는 AWS OIDC로 인증한 뒤 AWS Systems Manager를 통해 EC2 배포 스크립트를 실행합니다.

배포 스크립트는 다음 순서로 동작합니다.

1. EC2에서 `igmo-runtime` 네트워크와 실행 중인 `igmo-redis` 컨테이너를 확인합니다.
2. ECR에서 새 이미지를 Pull합니다.
3. Nginx의 현재 Active 포트를 확인합니다.
4. 반대 슬롯을 시작합니다.
5. 대상 포트의 `/actuator/health`가 200을 반환할 때까지 2초 간격으로 최대 30회 확인합니다.
6. Nginx upstream을 대상 슬롯으로 변경하고 `nginx -t`와 reload를 실행합니다.
7. 전환이 확인되면 기존 Active 컨테이너를 Drain 시간만큼 정상 종료하고 제거합니다.

대상 컨테이너가 정상화되지 않거나 Nginx 변경·reload가 실패하면 대상 컨테이너를 정리하고 백업한 Nginx 설정을 복원합니다.

앱 컨테이너는 Redis와 같은 `igmo-runtime` 네트워크에 연결되며, Redis는 애플리케이션 배포 과정에서 시작·중지하지 않습니다. Redis 최초 구성과 운영 명령은 [Redis 운영 문서](../deploy/redis/redis.md)를 참고합니다.

## `deploy_only` 수동 배포

`workflow_dispatch`에서 `deploy_only=true`를 선택하면 JAR 생성과 Docker 이미지 빌드를 건너뜁니다.

코드나 이미지가 아닌 운영 환경변수만 바꾸고, 이미 ECR에 있는 동일한 이미지를 다시 배포할 때 사용합니다.

1. `DEPLOY_SHA` 태그의 ECR 이미지가 존재하는지 확인합니다.
2. 현재 운영 환경변수로 해당 이미지를 EC2에 배포합니다.

코드, Dockerfile, API·WebSocket 문서가 바뀐 경우에는 `deploy_only`를 사용하지 않습니다. 새 커밋으로 일반 배포를 실행해야 합니다.

## 로컬 실행과의 관계

로컬에서는 GitHub Actions 러너 대신 개발자의 환경에서 같은 산출물 생성 단계를 실행합니다.

```bash
PUPPETEER_SKIP_DOWNLOAD=true npm ci
./gradlew clean bootJar --no-daemon
docker compose -f docker-compose.local.yml build
docker compose -f docker-compose.local.yml up -d
```

자세한 로컬 실행 방법은 [README의 로컬 Docker 실행](../README.md#로컬-docker-실행)을 참고합니다.

## 관련 파일

| 파일 | 역할 |
|---|---|
| `.github/workflows/ci.yml` | Pull Request 테스트와 문서 검증 |
| `.github/workflows/cd.yml` | JAR 생성, 이미지 Push, 운영 배포 |
| `.github/scripts/deploy-via-ssm.sh` | EC2 Blue/Green 전환과 Health Check |
| `Dockerfile` | 생성된 JAR을 실행 이미지로 패키징 |
| `docker-compose.prod.yml` | EC2의 Blue/Green 앱 서비스 정의 |
| `deploy/redis/redis.md` | Redis 네트워크와 별도 Compose 운영 방법 |
