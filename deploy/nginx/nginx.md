# Nginx 설정과 Blue/Green 배포

이 문서는 Git 저장소의 Nginx 설정이 EC2에 반영되는 과정과 애플리케이션 Blue/Green 배포의 관계를 설명한다.
Nginx 설정 배포와 애플리케이션 배포는 별도 작업이지만, Blue/Green 배포 스크립트가 요구하는 Nginx 구조가
먼저 준비되어야 한다. 게임 연결을 드레이닝하거나 프로세스 외부에 게임 상태를 저장하는 작업은 이 문서의 범위에 포함하지 않는다.

## 전체 구조

Nginx는 Spring Boot Docker 컨테이너 안이 아니라 EC2 호스트에서 실행된다. 컨테이너는 8080 포트를 열고,
배포 스크립트가 이를 EC2의 8080 또는 8081 포트에 바인딩한다. Nginx는 외부 요청을 현재 Active 컨테이너로
전달한다.

```text
GitHub Repository
├─ deploy/nginx/igmo.conf
├─ deploy/nginx/igmo.bootstrap.conf
└─ deploy/nginx/apply.sh
          │
          │ GitHub Actions → AWS Systems Manager
          ▼
EC2
├─ Nginx
│   └─ /etc/nginx/sites-available/igmo
│      └─ /etc/nginx/sites-enabled/igmo
│
└─ Docker
    ├─ igmo-backend-blue  → 127.0.0.1:8080 또는 8081
    └─ igmo-backend-green → 127.0.0.1:8080 또는 8081
```

Git의 설정 파일은 버전 관리되는 원본이고, EC2의 설정 파일은 실제 Nginx가 읽는 배포 결과다.
두 파일은 자동으로 동기화되지 않는다.

```text
Git의 igmo.conf
        ↓ apply.sh
       SSM
        ↓
EC2의 /etc/nginx/sites-available/igmo
        ↓ nginx -t
       reload
```

## Nginx의 역할

Nginx는 다음 역할을 담당한다.

- 외부 HTTP/HTTPS 요청 수신
- HTTPS 인증서 처리(TLS 종단)
- Spring Boot 애플리케이션으로 reverse proxy
- `igmo_backend` upstream이 가리키는 Active 포트로 요청 전달
- `/actuator/*` 외부 접근 차단
- API 문서 Basic Auth 적용
- 로컬 Alloy가 현재 Active 애플리케이션의 메트릭을 수집할 수 있도록 `127.0.0.1:18080/metrics` 제공

reverse proxy는 클라이언트가 직접 애플리케이션 포트에 연결하지 않고, Nginx가 요청을 대신 받아 내부
애플리케이션으로 전달하는 구조다. `igmo_backend`는 DNS 이름이 아니라 Nginx 설정 안에서 정의한 upstream 그룹 이름이다.

## 파일 구성

| 파일 | 역할 |
|---|---|
| `deploy/nginx/igmo.conf` | 운영 설정. HTTP→HTTPS redirect, TLS, API 문서 인증, WebSocket proxy, Active upstream, 로컬 메트릭 proxy 포함 |
| `deploy/nginx/igmo.bootstrap.conf` | 인증서가 없을 때 사용하는 HTTP 설정. ACME challenge와 애플리케이션 proxy를 제공 |
| `deploy/nginx/apply.sh` | 설정을 SSM으로 EC2에 전송하고 검증·reload·복구하는 반영 스크립트 |
| `.github/workflows/deploy-nginx.yml` | `apply.sh`를 수동 실행하는 GitHub Actions workflow |

운영 Nginx 설정은 EC2의 다음 경로에 반영된다.

```text
/etc/nginx/sites-available/igmo
/etc/nginx/sites-enabled/igmo → /etc/nginx/sites-available/igmo
```

Spring Boot Docker 이미지에는 애플리케이션 JAR만 포함된다. Dockerfile에 Nginx 설정이나 인증서를 복사하지
않으므로 이미지 빌드와 Nginx 설정 반영은 서로 다른 작업이다.

## Git 설정과 EC2 설정의 관계

`igmo.conf`를 Git에 커밋해도 EC2 파일은 자동으로 바뀌지 않는다.

```text
Git Repository                         EC2
deploy/nginx/igmo.conf   ──별도 반영──▶  /etc/nginx/sites-available/igmo
```

- Git의 `igmo.conf`: 원하는 운영 상태를 기록한 버전 관리 대상
- EC2의 `/etc/nginx/sites-available/igmo`: 현재 실행 중인 Nginx가 사용하는 실제 상태
- `apply.sh`: 두 상태 사이의 전달·검증·복구를 담당

## Nginx 설정 반영 과정

### `apply.sh`

일반적인 실행 명령은 다음과 같다.

```bash
./deploy/nginx/apply.sh deploy/nginx/igmo.conf igmo
```

대상 인스턴스는 기본값 `i-0d44e815cf354804c`(`ap-northeast-2`)이며, 환경변수로 변경할 수 있다.

```bash
EC2_INSTANCE_ID=i-xxxx AWS_REGION=ap-northeast-2 \
  ./deploy/nginx/apply.sh deploy/nginx/igmo.conf igmo
```

스크립트는 로컬 파일을 직접 EC2에 복사하지 않는다. 설정을 Base64로 변환해 AWS Systems Manager(SSM)의
`AWS-RunShellScript` 명령으로 EC2에 전달한다.

```text
igmo.conf 읽기
    ↓
SSM 명령 전송
    ↓
기존 설정 백업
    ↓
임시 파일에 새 설정 작성
    ↓
sites-enabled 심볼릭 링크 보장
    ↓
nginx -t
    ├─ 실패 → 기존 설정 복원
    └─ 성공 → systemctl reload nginx
```

`reload`는 기존 Nginx worker를 즉시 종료하는 대신 새 설정으로 worker를 교체하는 Nginx 동작이다.
이 의미의 Nginx 프로세스 전환과 애플리케이션 게임 상태를 보존하는 Blue/Green 무중단 배포는 서로 다른 문제다.

`igmo`를 적용할 때 `apply.sh`는 먼저 현재 Active 포트를 확인한다. 기존 `igmo_backend` upstream이 있으면
그 안의 8080 또는 8081을 사용한다. 최초 마이그레이션처럼 upstream이 없으면 현재 구현이 지원하는 레거시 형태인
`proxy_pass http://127.0.0.1:8080;` 또는 `8081`을 찾아 Active 포트를 보존한다. 그 포트를 새 `igmo.conf`의
upstream에 넣어 반영하므로, 설정을 적용하는 순간 기존 애플리케이션으로 연결되는 포트가 바뀌지 않는다.

### `Deploy Nginx` workflow

`.github/workflows/deploy-nginx.yml`은 `workflow_dispatch` 기반의 수동 Nginx 배포 인터페이스다.
실제 반영 로직을 별도로 구현하지 않고 선택한 대상에 따라 `apply.sh`를 호출한다.

```text
Deploy Nginx workflow
        ↓
     apply.sh
        ↓ SSM
       EC2 Nginx
```

선택 가능한 대상은 다음과 같다.

| target | 실행 파일 | 용도 |
|---|---|---|
| `igmo` | `deploy/nginx/igmo.conf` | 애플리케이션 Nginx 설정 |
| `monitoring-bootstrap` | `deploy/nginx/monitoring.bootstrap.conf` | 모니터링 인증서 발급 전 설정 |
| `monitoring` | `deploy/nginx/monitoring.conf` | 모니터링 운영 설정 |

`Run workflow` 화면에서 선택한 브랜치를 `actions/checkout`이 checkout하므로, 그 브랜치의 설정 파일이 EC2에
반영된다. 운영 반영 시에는 적용하려는 설정이 포함된 브랜치를 선택해야 한다. 설정 변경이 이미 merge됐다면
`prod`를 선택하고, 아직 merge되지 않은 변경을 먼저 반영해야 한다면 해당 작업 브랜치를 선택한다.

## 애플리케이션 CD와의 관계

현재 애플리케이션 CD는 Nginx 설정을 자동으로 반영하지 않는다.

```text
Deploy Nginx workflow  →  Nginx 설정 반영

Application CD         →  Docker 이미지 빌드·Push·Blue/Green 컨테이너 배포
```

애플리케이션 CD는 `deploy-via-ssm.sh`를 통해 다음 조건을 확인한다.

- EC2 Nginx 설정이 유효해야 한다.
- `igmo_backend` upstream이 존재해야 한다.
- upstream이 8080 또는 8081 중 하나의 Active 포트를 가리켜야 한다.

조건을 통과하면 CD는 반대 포트에 Target 컨테이너를 실행하고 Health Check를 수행한다. 정상 기동 후 Nginx
upstream을 Target 포트로 바꾸고 `nginx -t`와 reload를 수행한다. 이후 기존 Active 컨테이너를 제거한다.

따라서 두 배포 파이프라인은 실행 단위는 분리되어 있지만, 애플리케이션 CD가 요구하는 Nginx 구조라는 의존성이 있다.

## Blue/Green 최초 마이그레이션

Blue/Green 배포 도입 전 EC2에는 다음과 같은 direct proxy 설정이 있을 수 있다.

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
}
```

새 배포 스크립트는 Nginx upstream을 포트 스위칭 대상으로 사용한다.

```nginx
upstream igmo_backend {
    server 127.0.0.1:8080;
}

location / {
    proxy_pass http://igmo_backend;
}
```

EC2가 아직 direct proxy 구조인 상태에서 애플리케이션 CD를 먼저 실행하면, `deploy-via-ssm.sh`가
`igmo_backend` upstream을 찾지 못하고 애플리케이션 컨테이너를 시작하기 전에 종료한다.

```text
레거시 Nginx 설정
    ↓
prod에 Blue/Green CD 반영
    ↓
deploy-via-ssm.sh가 upstream 확인
    ↓
igmo_backend 없음
    ↓
애플리케이션 배포 실패
```

현재 수동 전환 절차는 다음과 같다.

```text
1. Deploy Nginx 실행
   target=igmo
   설정이 포함된 브랜치 선택
        ↓
2. apply.sh가 레거시 direct proxy를 upstream 구조로 변환
        ↓
3. nginx -t 및 reload 성공 확인
        ↓
4. 애플리케이션 CD 실행 또는 prod merge
```

`prod` push가 CD를 즉시 시작하는 환경에서는 Nginx 마이그레이션을 먼저 완료한 뒤 `prod`에 merge해야 한다.
그렇지 않으면 애플리케이션 CD가 레거시 설정을 만나는 순서 경쟁이 발생한다.

## 최초 마이그레이션 이후 포트 전환

최초 마이그레이션은 매번 반복하지 않는다. Nginx 설정을 한 번 upstream 구조로 바꾼 뒤에는 애플리케이션 CD가
다음과 같이 Active와 Target을 교대한다.

```text
현재 Active 8080 → 새 Target 8081 실행 → Health Check → Nginx 8081 전환
현재 Active 8081 → 새 Target 8080 실행 → Health Check → Nginx 8080 전환
```

이후 Nginx 설정 파일 자체를 변경할 때는 `Deploy Nginx`를 별도로 실행해야 한다. 애플리케이션 이미지에
Nginx 설정이 포함되어 있지 않기 때문이다.

## 인증서 발급과 bootstrap 설정

운영 설정 `igmo.conf`는 다음 인증서 파일을 참조한다.

```text
/etc/letsencrypt/live/api.igmo.co.kr/fullchain.pem
/etc/letsencrypt/live/api.igmo.co.kr/privkey.pem
```

인증서가 없는 EC2에서 바로 `igmo.conf`를 적용하면 `nginx -t`가 인증서 파일을 읽지 못해 실패한다.
이때는 인증서 발급에 필요한 HTTP 설정을 먼저 적용한다.

```text
igmo.bootstrap.conf
    ↓
HTTP 80 + ACME challenge
    ↓
인증서 발급
    ↓
igmo.conf
    ↓
HTTPS 운영 설정
```

`igmo.bootstrap.conf`는 EC2 재구축이나 도메인 변경처럼 인증서를 새로 발급해야 할 때 사용한다.

## API 문서 Basic Auth

운영 설정은 다음 API 문서 경로에만 Basic Auth를 적용한다.

- `/docs.html`
- `/api-spec/*`

Nginx는 EC2의 `/etc/nginx/.htpasswd` 파일을 사용한다. 이 파일은 Git이나 Docker 이미지에 포함하지 않고 EC2에서 직접 생성한다.

```bash
sudo apt-get update
sudo apt-get install -y apache2-utils
sudo htpasswd -c /etc/nginx/.htpasswd igmo-docs
sudo nginx -t
sudo systemctl reload nginx
```

사용자를 추가할 때는 기존 파일을 덮어쓰지 않도록 `-c`를 빼고 실행한다.

```bash
sudo htpasswd /etc/nginx/.htpasswd another-user
```

## 주의사항

- 인증서 경로를 변경하면 인증서 발급을 먼저 완료해야 `nginx -t`가 통과한다. `igmo.bootstrap.conf` 적용 → 인증서 발급 → `igmo.conf` 적용 순서를 지킨다.
- `sites-enabled/igmo`는 `/etc/nginx/sites-available/igmo`를 가리키는 심볼릭 링크여야 한다. 백업 파일을 `sites-enabled` 안에 두면 `conflicting server name`이 발생할 수 있다.
- `apply.sh`는 설정 반영 전에 `sites-available/igmo.bak.<timestamp>`로 백업한다. 백업 파일은 `sites-enabled`에 두지 않는다.
- `apply.sh`의 레거시 자동 감지는 현재 운영에서 사용하는 `127.0.0.1:8080` 또는 `8081` direct proxy 형태를 기준으로 한다. 다른 주소 형식이면 수동 확인이 필요하다.
- Nginx reload가 애플리케이션 컨테이너의 연결이나 게임 상태를 보존하는 것은 아니다. 현재 CD의 기존 Active 컨테이너 드레이닝과 게임 상태 이관은 별도 과제다.
- AWS CLI와 `base64`가 필요하다. 자격증명이 만료되면 AWS CLI 자격증명을 다시 설정한다.

## 수동 반영(apply.sh를 사용할 수 없는 경우)

일반적인 반영은 `apply.sh` 또는 `Deploy Nginx`를 사용한다. 아래 절차는 장애 대응 등으로 스크립트를 사용할 수 없을 때만 사용한다.
수동으로 `igmo.conf`를 적용할 때는 파일 안의 upstream 포트가 현재 Active 포트와 같은지 먼저 확인한다.

```bash
# 로컬
base64 -w0 deploy/nginx/igmo.conf

# EC2 접속
aws ssm start-session --target i-0d44e815cf354804c --region ap-northeast-2

cp -a /etc/nginx/sites-available/igmo /etc/nginx/sites-available/igmo.bak.$(date +%s)
echo '<base64>' | base64 -d > /etc/nginx/sites-available/igmo
ln -sfn /etc/nginx/sites-available/igmo /etc/nginx/sites-enabled/igmo
nginx -t && systemctl reload nginx
```
