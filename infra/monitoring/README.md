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

## Grafana Cloud 대시보드 동기화

대시보드 원본은 `grafana/provisioning/dashboards/`의 JSON이다. 이 파일은 로컬 Grafana에서 `prometheus`, `loki` datasource UID를 사용한다. Cloud 동기화는 원본을 수정하지 않고 임시 `build/grafana-cloud-dashboards/`에 Cloud datasource UID가 적용된 JSON을 만든다.

GitHub 저장소 수준 `Variables`에 다음 비밀이 아닌 값을 설정한다. PR dry-run도 이 값을 사용하므로 `production` 환경 변수로만 두면 PR 검증이 실패한다. API 토큰만 `production` 환경의 `Secret`으로 둔다.

| 구분 | 이름 | 용도 |
|---|---|---|
| Variable | `GRAFANA_CLOUD_URL` | Grafana Cloud 인스턴스 URL |
| Variable | `GRAFANA_CLOUD_FOLDER_UID` | 동기화 대상 폴더 UID |
| Variable | `GRAFANA_CLOUD_PROMETHEUS_DATASOURCE_UID` | Cloud Prometheus datasource UID |
| Variable | `GRAFANA_CLOUD_LOKI_DATASOURCE_UID` | Cloud Loki datasource UID |
| Secret | `GRAFANA_CLOUD_API_TOKEN` | 대시보드 조회·수정용 Grafana 서비스 계정 토큰 |

`GRAFANA_CLOUD_API_TOKEN`은 `GRAFANA_CLOUD_INGEST_TOKEN`과 다르다. 전자는 Dashboard API용 서비스 계정 토큰이고, 후자는 Alloy의 메트릭·로그 전송용 Access Policy 토큰이다.

대시보드 JSON 또는 동기화 스크립트가 포함된 `prod` 대상 PR에서는 `Validate Grafana Dashboards`가 실행된다. JSON, UID, datasource 변환, ShellCheck, mock API 테스트와 dry-run만 수행하며 Cloud 쓰기 토큰을 사용하지 않는다. `prod` 병합만으로 Cloud 대시보드는 변경되지 않는다.

Cloud 반영은 GitHub Actions의 `Deploy Monitoring`을 수동 실행할 때만 수행한다.

- `deploy_alloy`: Alloy와 node_exporter 배포
- `sync_dashboards`: Grafana Cloud 대시보드 동기화

둘 다 `false`이면 실패한다. 대시보드만 수정한 경우 `deploy_alloy=false`, `sync_dashboards=true`로 실행한다.

로컬 dry-run은 다음처럼 실행한다. Cloud API 조회 없이 JSON 렌더링과 UID 검증만 수행한다.

```bash
DRY_RUN=true \
GRAFANA_CLOUD_FOLDER_UID=<folder-uid> \
GRAFANA_CLOUD_PROMETHEUS_DATASOURCE_UID=<prometheus-uid> \
GRAFANA_CLOUD_LOKI_DATASOURCE_UID=<loki-uid> \
bash deploy/monitoring/grafana/sync-dashboards.sh
```

동기화는 Cloud에 같은 UID의 대시보드가 존재하고 제목·폴더가 일치하며, 같은 제목을 다른 UID가 사용하지 않을 때만 update한다. 새 UID는 자동 생성하지 않는다. API 조회·render·update 후 재조회 중 하나라도 실패하면 즉시 중단한다. 이미 성공한 대시보드는 자동 롤백하지 않으므로, 실패 원인을 수정한 뒤 같은 수동 동기화를 다시 실행한다.

Cloud UI 직접 수정은 다음 동기화에서 Git JSON으로 덮어써질 수 있다. 새 대시보드는 Cloud에 대상 폴더와 UID를 먼저 수동으로 준비하고, JSON과 Cloud datasource 매핑을 추가한 뒤 PR 검증을 통과시킨다.

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
