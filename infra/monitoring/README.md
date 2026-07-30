# 단일 EC2 관측 스택

`t4g.small`의 Spring Boot 앱과 같은 EC2에서 실행하는 관측 스택이다. 모든 서비스는 host network를 사용하지만 `127.0.0.1`에만 바인딩한다. 외부 공개 경로는 Nginx의
`monitoring.igmo.co.kr`뿐이다.

| 서비스           | 역할                     | 메모리 상한 |
|---------------|------------------------|-------:|
| Prometheus    | Spring Boot·EC2 메트릭 수집 | 192MiB |
| Grafana       | 시각화·로그 조회              | 128MiB |
| Loki          | 로그 저장                  | 128MiB |
| Alloy         | Docker 로그를 Loki로 전송    |  64MiB |
| node_exporter | EC2 호스트 지표 노출          |  32MiB |

관측 컨테이너 상한 합계는 544MiB다. Prometheus는 3일 또는 512MB까지 보관한다. Loki는 3일 보관한다. 앱 Docker 로그는 `local` 드라이버의 10MB 파일 3개로 제한한다.

## 배포 전 준비

GitHub 저장소의 `production` 환경에 `GRAFANA_ADMIN_PASSWORD` secret을 추가한다. Grafana 로그인 계정은 `igmo-admin`이며, 비밀번호는 EC2의
`/opt/igmo/monitoring-secrets/`에만 저장된다.

## 배포 순서

1. GitHub Actions의 `Deploy Monitoring`을 수동 실행한다.
2. Grafana·Prometheus·Loki 준비 상태가 성공했는지 워크플로 로그에서 확인한다.
3. GitHub Actions의 `Deploy Nginx`에서 `monitoring`을 선택해 HTTPS reverse proxy를 적용한다.
4. 기존 CD의 `deploy_only`를 한 번 실행해 앱 컨테이너를 재생성한다. 이 시점부터 앱 로그 드라이버가 `awslogs`에서 `local`로 바뀌며 Alloy가 로그를 수집한다.

`Deploy Monitoring`은 설정 파일 변경을 확실히 반영하기 위해 관측 컨테이너를 재생성한다. 배포 중에는 Grafana 조회와 메트릭·로그 수집이 잠시 중단될 수 있지만, Docker named volume의 데이터는 유지된다.

## Grafana 대시보드 관리

Grafana UI에서 직접 만들지 않고 `grafana/provisioning/dashboards/` 파일로 관리한다. 따라서 패널·PromQL·Loki 쿼리를 수정한 뒤 `Deploy Monitoring`을 한 번 실행하면 컨테이너 재생성과 함께 대시보드가 갱신된다.

| 대시보드 | 파일 | 용도 |
|---|---|---|
| 인스턴스 상태 | `instance.json` | EC2 기동·CPU·메모리·EBS·네트워크·디스크 상태 확인 |
| 애플리케이션 상태 | `spring-application.json` | 앱 기동·HTTP·JVM·이미지 생성·앱 로그 확인 |
| WebSocket·게임 상태 | `websocket-game.json` | 연결·게임 방·방 전체 메시지 전송·관련 로그 확인 |

`인스턴스 상태`는 운영 EC2의 `node_exporter` 지표를 사용하므로 로컬에서는 빈값이 정상이다. 앱·WebSocket 대시보드는 환경·로그 레벨·로그 검색어 변수로 조회 범위를 좁힐 수 있다.

## 로컬 실행

macOS·Docker Desktop에서 운영 Compose를 실행하지 않는다. `docker-compose.local.yml`은 관측 컨테이너를 bridge network로 묶고, EC2 전용 node_exporter는 제외한다. `GRAFANA_ADMIN_PASSWORD`를 로컬 `.env`에 설정한 뒤 실행한다.

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

## EC2 내부 확인

```bash
curl --fail http://127.0.0.1:9090/-/ready
curl --fail http://127.0.0.1:3100/ready
curl --fail http://127.0.0.1:3000/api/health
```

Prometheus 수집 대상은 `http://127.0.0.1:9090/targets`에서 확인한다. `igmo-app`과 `ec2-host`가 `UP`이어야 한다.
