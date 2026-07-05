# nginx 설정

`api.igmo.co.kr` 리버스 프록시 설정. nginx가 80/443만 외부에 공개하고, 요청을
백엔드 컨테이너(`127.0.0.1:8080`)로 프록시한다. nginx와 인증서는 EC2에서 SSM으로
관리하며, 앱 배포(GitHub Actions CD)와는 독립적으로 동작한다.

## 폴더 구성

| 파일 | 설명 |
|---|---|
| `igmo.conf` | 운영 HTTPS 설정. HTTP(80)→HTTPS 301, `api.igmo.co.kr` TLS 종단, `127.0.0.1:8080` 프록시, `/actuator/*` 외부 차단(404), API 문서 Basic Auth 보호 |
| `igmo.bootstrap.conf` | 인증서 발급 전 임시 HTTP 설정. ACME challenge 경로 + 프록시만. EC2 재구축·도메인 변경으로 인증서를 새로 받을 때 사용 |
| `apply.sh` | 위 설정을 EC2에 반영하는 헬퍼 스크립트 |

EC2 적용 위치: `/etc/nginx/sites-available/igmo`
(`sites-enabled/igmo`는 이 파일을 가리키는 심볼릭 링크)

## 설정 변경 후 재배포

1. `igmo.conf`(또는 `igmo.bootstrap.conf`)를 수정하고 커밋한다.
2. 헬퍼 스크립트로 반영한다:

   ```bash
   ./deploy/nginx/apply.sh deploy/nginx/igmo.conf
   ```

   스크립트가 SSM으로 **백업 → 교체 → 심볼릭 링크 보장 → `nginx -t` → reload(무중단)**
   까지 수행하고, `nginx -t` 실패 시 백업으로 자동 복원한다.

대상 인스턴스는 기본값 `i-0d44e815cf354804c`(`ap-northeast-2`)이며, 환경변수로 바꿀 수 있다:

   ```bash
   EC2_INSTANCE_ID=i-xxxx AWS_REGION=ap-northeast-2 ./deploy/nginx/apply.sh deploy/nginx/igmo.conf
   ```

**필요 도구:** `aws` CLI(로그인 상태), `base64`. 자격증명이 만료되면 `aws login`으로 재인증한다.

## API 문서 Basic Auth

운영 설정은 API 문서 경로만 Basic Auth로 보호한다.

- `/docs.html`
- `/api-spec/*`

nginx는 EC2 내부의 `/etc/nginx/.htpasswd` 파일로 인증 정보를 확인한다. 이 파일은 Git에
커밋하지 않고 EC2에서 직접 생성한다.

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

### 주의

- **인증서 경로를 바꾸는 변경**(도메인 변경 등)은 인증서 발급이 선행돼야 `nginx -t`가
  통과한다. 이때는 `igmo.bootstrap.conf` 반영 → 인증서 발급 → `igmo.conf` 반영 순서로.
- `sites-enabled/igmo`는 **심볼릭 링크**를 유지해야 한다(스크립트가 보장). 백업 파일을
  `sites-enabled` 안에 두면 nginx가 중복 로드해 `conflicting server name`이 발생한다.

## 수동 반영 (스크립트 없이)

```bash
# 로컬
base64 -w0 deploy/nginx/igmo.conf

# EC2 접속: aws ssm start-session --target i-0d44e815cf354804c --region ap-northeast-2
cp -a /etc/nginx/sites-available/igmo /etc/nginx/sites-available/igmo.bak.$(date +%s)
echo '<base64>' | base64 -d > /etc/nginx/sites-available/igmo
ln -sfn /etc/nginx/sites-available/igmo /etc/nginx/sites-enabled/igmo
nginx -t && systemctl reload nginx
```
