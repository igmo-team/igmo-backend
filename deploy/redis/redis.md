# Redis 운영 환경

Redis는 애플리케이션 Blue/Green Compose와 별도 Compose 프로젝트로 실행한다.

```text
igmo-redis Compose                         igmo-production Compose
redis container                            app-blue
igmo-redis-data volume                     app-green
        │                                  │
        └────────── igmo-runtime network ──┘
```

## 구성

- Compose 파일: `docker-compose.redis.yml`
- Compose 프로젝트: `igmo-redis`
- 컨테이너: `igmo-redis`
- 메트릭 Exporter: `igmo-redis-exporter`
- 데이터 volume: `igmo-redis-data:/data`
- 공용 network: `igmo-runtime`
- 앱에서 접근할 호스트명: `redis`
- Redis 포트: `6379`
- Exporter 메트릭 포트: `127.0.0.1:9121`

Redis는 호스트 포트를 공개하지 않는다. 앱 Blue/Green과 같은 Docker network 안에서만 `redis:6379`로 접근한다.
Redis Exporter는 같은 `igmo-runtime` network에서 Redis에 연결하고, 호스트의 `127.0.0.1:9121/metrics`로만 메트릭을 공개한다. Alloy는 이 로컬 엔드포인트를 `job=redis`로 수집해 Grafana Cloud Prometheus로 전송한다.

현재 Redis는 별도 `command` 설정 없이 `redis:7.4-alpine` 이미지의 기본 실행 명령으로 시작한다. 따라서 AOF는 활성화하지 않으며, Redis 기본 RDB snapshot 설정을 사용한다. Redis 컨테이너가 재생성되거나 애플리케이션 컨테이너가 교체되어도 `igmo-redis-data` volume은 유지된다.

컨테이너 메모리 제한은 `512m`다. Redis 자체 `maxmemory`와 별도 eviction 정책은 지정하지 않으므로 Redis 이미지 기본값을 사용한다. volume 용량은 Compose에서 고정하지 않으며 EC2 호스트의 Docker 데이터 영역과 EBS 용량이 결정한다.

현재 Redis는 Docker network 내부에서만 접근하도록 구성되어 있으며 비밀번호 인증은 연결 클라이언트 설정과 함께 별도 적용해야 한다.

## EC2 최초 설정

Git 저장소의 `docker-compose.redis.yml`을 Redis 구성의 원본으로 사용한다. EC2의
`/opt/igmo/docker-compose.redis.yml`은 해당 파일을 배포한 복사본이며, EC2에서 직접
수정하지 않는다.

Redis Compose는 앱 CD와 분리된 수동 운영 대상이다. Redis 설정 변경이 드문 현재
운영 정책에서는 변경 시에만 운영자가 파일을 EC2에 다시 배치하고 적용한다. 앱 CD는
Redis Compose 파일을 전송하거나 Redis를 시작·중지하지 않는다.

EC2 최초 설정 순서는 다음과 같다.

1. 외부 Docker network를 생성한다. 이미 존재하면 그대로 사용한다.

   ```bash
   docker network inspect igmo-runtime >/dev/null 2>&1 \
     || docker network create --driver bridge igmo-runtime
   ```

2. Git의 `docker-compose.redis.yml`을 EC2의 `/opt/igmo/docker-compose.redis.yml`에
   SSH 또는 SSM으로 배치한다. 이 파일은 EC2에서 직접 작성하거나 수정하지 않는다.
3. Compose 설정을 검증하고 Redis를 시작한다.

```bash
cd /opt/igmo
docker compose -f docker-compose.redis.yml config -q
docker compose -f docker-compose.redis.yml up -d
docker compose -f docker-compose.redis.yml ps
docker inspect --format '{{.State.Health.Status}}' igmo-redis
docker exec igmo-redis redis-cli ping
curl --fail --silent http://127.0.0.1:9121/metrics | grep '^redis_up 1$'
docker network inspect igmo-runtime
```

`docker exec` 결과가 `PONG`, Redis health 상태가 `healthy`, Exporter의 `redis_up`이
`1`인지 확인한다. 그 다음 애플리케이션 CD를 실행한다. 애플리케이션 배포 스크립트는
Redis와 Exporter를 시작·중지하지 않고, `igmo-runtime` network와 실행 중인
`igmo-redis`만 확인한다.

## 운영 명령

```bash
docker compose -f /opt/igmo/docker-compose.redis.yml ps
docker compose -f /opt/igmo/docker-compose.redis.yml logs --tail 100 redis
docker compose -f /opt/igmo/docker-compose.redis.yml logs --tail 100 redis-exporter
docker compose -f /opt/igmo/docker-compose.redis.yml restart redis
docker compose -f /opt/igmo/docker-compose.redis.yml stop redis
docker compose -f /opt/igmo/docker-compose.redis.yml start redis
```

Redis Compose를 변경한 경우 최신 Git 파일을 EC2에 다시 배치한 뒤 다음 명령으로
적용한다.

```bash
docker compose -f /opt/igmo/docker-compose.redis.yml config -q
docker compose -f /opt/igmo/docker-compose.redis.yml up -d
```

`docker compose down`은 Redis 컨테이너를 제거하지만 외부 `igmo-runtime` network는
제거하지 않으며, named volume도 `-v` 없이는 유지한다. 테스트 목적의 일시 중지는
`stop`을 사용한다. 데이터를 삭제하는 `down -v`는 백업 확인 없이 실행하지 않는다.

문제 발생 시 알려진 Git revision의 Compose 파일을 EC2에 다시 배치하고
`config -q` 통과 후 `up -d`를 실행해 롤백한다.

## 로컬 호스트 실행

IDE 또는 `./gradlew bootRun`으로 Spring Boot를 실행할 때는 Redis의 로컬 override를
사용한다. Redis 컨테이너는 `127.0.0.1:6379`만 호스트에 공개하며, 운영용 기본
Compose 파일에는 포트 매핑이 없다.

```bash
docker network inspect igmo-runtime >/dev/null 2>&1 \
  || docker network create --driver bridge igmo-runtime
docker compose \
  -f docker-compose.redis.yml \
  -f docker-compose.redis.local.yml \
  up -d
IGMO_REDIS_HOST=localhost SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

Docker Compose로 앱을 실행할 때는 앱이 `igmo-runtime` network의 `redis:6379`를
사용하므로 `docker-compose.local.yml`의 기본값을 사용한다. 로컬 Redis override를
사용하면 Exporter도 함께 실행되며, 메트릭은 동일하게 `127.0.0.1:9121/metrics`에서
확인할 수 있다.

앱 Blue/Green 배포는 `igmo-production` Compose 프로젝트의 서비스만 교체한다. 따라서 한 슬롯이 종료되거나 두 슬롯이 동시에 실행되어도 두 앱 컨테이너는 동일한 `igmo-runtime` network에서 하나의 `redis` 컨테이너를 바라본다.
