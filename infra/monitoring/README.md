# 단일 EC2 관측 스택

`t4g.small`의 Spring Boot 앱과 같은 EC2에서 실행하는 관측 스택이다. 모든 서비스는 host network를 사용하지만 `127.0.0.1`에만 바인딩한다. 외부 공개 경로는 Nginx의 `monitoring.igmo.co.kr`뿐이다.

| 서비스 | 역할 | 메모리 상한 |
| --- | --- | ---: |
| Prometheus | Spring Boot·EC2 메트릭 수집 | 192MiB |
| Grafana | 시각화·로그 조회 | 128MiB |
| Loki | 로그 저장 | 128MiB |
| Alloy | Docker 로그를 Loki로 전송 | 64MiB |
| node_exporter | EC2 호스트 지표 노출 | 32MiB |

관측 컨테이너 상한 합계는 544MiB다. Prometheus는 3일 또는 512MB까지 보관한다. Loki는 3일 보관한다. 앱 Docker 로그는 `local` 드라이버의 10MB 파일 3개로 제한한다.

## 배포 전 준비

GitHub 저장소의 `production` 환경에 `GRAFANA_ADMIN_PASSWORD` secret을 추가한다. Grafana 로그인 계정은 `igmo-admin`이며, 비밀번호는 EC2의 `/opt/igmo/monitoring-secrets/`에만 저장된다.

## 배포 순서

1. GitHub Actions의 `Deploy Monitoring`을 수동 실행한다.
2. Grafana·Prometheus·Loki 준비 상태가 성공했는지 워크플로 로그에서 확인한다.
3. GitHub Actions의 `Deploy Nginx`에서 `monitoring`을 선택해 HTTPS reverse proxy를 적용한다.
4. 기존 CD의 `deploy_only`를 한 번 실행해 앱 컨테이너를 재생성한다. 이 시점부터 앱 로그 드라이버가 `awslogs`에서 `local`로 바뀌며 Alloy가 로그를 수집한다.

## EC2 내부 확인

```bash
curl --fail http://127.0.0.1:9090/-/ready
curl --fail http://127.0.0.1:3100/ready
curl --fail http://127.0.0.1:3000/api/health
```

Prometheus 수집 대상은 `http://127.0.0.1:9090/targets`에서 확인한다. `igmo-app`과 `ec2-host`가 `UP`이어야 한다.
