# Grafana Cloud 관측

운영은 Grafana Cloud를 메트릭·로그 저장소와 대시보드로 사용한다. EC2에는 Grafana Alloy와 node_exporter만 실행한다. Alloy가 Spring Boot와 node_exporter 메트릭을 scrape해 Grafana Cloud Metrics로 전송하고, 앱 Docker stdout 로그를 Grafana Cloud Logs로 전송한다.

| 서비스 | 역할 | 메모리 상한 |
|---|---|---:|
| Alloy | 메트릭 scrape 및 로그 전송 | 256MiB |
| node_exporter | EC2 호스트 지표 노출 | 32MiB |

Grafana Cloud 접근은 `monitoring.igmo.co.kr`에서 Grafana Cloud 조직으로 리다이렉트한다.

## 운영 배포

GitHub 저장소 `production` 환경에 `GRAFANA_CLOUD_INGEST_TOKEN` secret을 설정한다. 토큰은 `metrics:write`, `logs:write` 권한이 필요하다.

1. GitHub Actions의 `Deploy Monitoring`을 수동 실행한다.
2. 워크플로에서 Alloy와 node_exporter가 실행 중이고 재시작 횟수가 0인지 확인한다.
3. Alloy readiness와 health endpoint(`http://127.0.0.1:12345/-/ready`, `/-/healthy`)가 성공했는지 확인한다.

배포는 `/opt/igmo/monitoring-secrets/grafana-cloud-ingest-token`에 토큰을 저장하고, Alloy 컨테이너에 read-only로 마운트한다. Grafana Cloud 수집·조회는 배포 중 잠시 중단될 수 있다.

## 로컬 실행

로컬은 Grafana, Prometheus, Loki, Alloy를 Docker Compose로 실행한다. `GRAFANA_ADMIN_PASSWORD`를 로컬 `.env`에 설정한 뒤 실행한다.

### IDE에서 Spring Boot 실행

먼저 관측 컨테이너만 실행한다.

```bash
docker compose -f infra/monitoring/docker-compose.local.yml up --build
```

IDE 앱은 일반 `local` 프로필로 실행한다. Prometheus는 Docker Desktop의 `host.docker.internal:8080`을 통해 IDE 앱의 `/actuator/prometheus`를 수집한다. IDE 로그는 Loki로 수집하지 않는다.

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

### Docker에서 Spring Boot 실행

기존 루트 Compose로 앱을 실행한다. 앱 컨테이너의 `8080` 포트를 통해 Prometheus가 같은 방식으로 메트릭을 수집하고, Alloy는 앱 컨테이너 stdout을 Loki로 전송한다. Compose가 컨테이너 로그를 JSON으로 출력하도록 설정하므로 Grafana에서 로그 레벨·검색어 필터도 동작한다.

```bash
docker compose --env-file .env up --build
```

Grafana는 `http://localhost:3000`, Prometheus는 `http://localhost:9090`, Loki는 `http://localhost:3100`으로 접근한다. Grafana에서 `환경` 변수를 `local`로 바꾸면 로컬 로그를 볼 수 있다. 종료할 때는 `docker compose -f infra/monitoring/docker-compose.local.yml down`을 사용한다.
