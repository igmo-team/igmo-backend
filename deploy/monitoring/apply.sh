#!/usr/bin/env bash
# 저장소의 관측 스택 설정을 EC2에 반영한다 (SSM 경유).
#
# 필요 환경변수:
#   GRAFANA_ADMIN_PASSWORD  Grafana igmo-admin 계정 비밀번호
# 선택 환경변수:
#   EC2_INSTANCE_ID         대상 인스턴스 (기본 i-0d44e815cf354804c)
#   AWS_REGION              리전 (기본 ap-northeast-2)
set -Eeuo pipefail

AWS_REGION="${AWS_REGION:-ap-northeast-2}"
EC2_INSTANCE_ID="${EC2_INSTANCE_ID:-i-0d44e815cf354804c}"
GRAFANA_ADMIN_PASSWORD="${GRAFANA_ADMIN_PASSWORD:?GRAFANA_ADMIN_PASSWORD is required}"

if [[ ! "$AWS_REGION" =~ ^[a-z0-9-]+$ ]]; then
  echo "유효하지 않은 AWS_REGION입니다: $AWS_REGION" >&2
  exit 1
fi

if [[ ! "$EC2_INSTANCE_ID" =~ ^i-[a-f0-9]+$ ]]; then
  echo "유효하지 않은 EC2_INSTANCE_ID입니다: $EC2_INSTANCE_ID" >&2
  exit 1
fi

STACK_ARCHIVE_B64=$(tar -C . -czf - infra/monitoring | base64 | tr -d '\n')
GRAFANA_PASSWORD_B64=$(printf '%s' "$GRAFANA_ADMIN_PASSWORD" | base64 | tr -d '\n')

REMOTE_SCRIPT="set -eu
STACK_ROOT=/opt/igmo/monitoring
SECRETS_ROOT=/opt/igmo/monitoring-secrets
TEMP_ROOT=\$(mktemp -d)

if ! docker compose version >/dev/null 2>&1; then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y docker-compose-v2
fi

mkdir -p \"\$SECRETS_ROOT\"
echo '$GRAFANA_PASSWORD_B64' | base64 -d > \"\$SECRETS_ROOT/grafana-admin-password\"
chown 472:472 \"\$SECRETS_ROOT/grafana-admin-password\"
chmod 600 \"\$SECRETS_ROOT/grafana-admin-password\"

echo '$STACK_ARCHIVE_B64' | base64 -d | tar -xzf - -C \"\$TEMP_ROOT\"
BACKUP_ROOT=''
if [ -d \"\$STACK_ROOT\" ]; then
  BACKUP_ROOT=\"\$TEMP_ROOT/previous-monitoring\"
  mv \"\$STACK_ROOT\" \"\$BACKUP_ROOT\"
fi
mkdir -p \"\$(dirname \"\$STACK_ROOT\")\"
mv \"\$TEMP_ROOT/infra/monitoring\" \"\$STACK_ROOT\"

if ! docker compose -f \"\$STACK_ROOT/docker-compose.yml\" config -q; then
  if [ -n \"\$BACKUP_ROOT\" ]; then
    rm -rf \"\$STACK_ROOT\"
    mv \"\$BACKUP_ROOT\" \"\$STACK_ROOT\"
  fi
  exit 1
fi

ROLLBACK_NEEDED=true
rollback() {
  EXIT_STATUS=\"\$1\"
  if [ \"\$ROLLBACK_NEEDED\" = true ]; then
    docker compose -f \"\$STACK_ROOT/docker-compose.yml\" down --remove-orphans || true
    if [ -n \"\$BACKUP_ROOT\" ]; then
      rm -rf \"\$STACK_ROOT\"
      mv \"\$BACKUP_ROOT\" \"\$STACK_ROOT\"
      docker compose -f \"\$STACK_ROOT/docker-compose.yml\" up -d --remove-orphans || true
    fi
  fi
  exit \"\$EXIT_STATUS\"
}
trap 'rollback \"\$?\"' EXIT

docker compose -f \"\$STACK_ROOT/docker-compose.yml\" up -d --remove-orphans
curl --fail --retry 10 --retry-connrefused --retry-delay 2 http://127.0.0.1:9090/-/ready
curl --fail --retry 10 --retry-connrefused --retry-delay 2 http://127.0.0.1:3100/ready
curl --fail --retry 10 --retry-connrefused --retry-delay 2 http://127.0.0.1:3000/api/health
docker compose -f \"\$STACK_ROOT/docker-compose.yml\" ps
ROLLBACK_NEEDED=false
trap - EXIT"

SCRIPT_B64=$(printf '%s' "$REMOTE_SCRIPT" | base64 | tr -d '\n')
PARAMS=$(jq -n --arg command "echo $SCRIPT_B64 | base64 -d | bash" '{commands: [$command], executionTimeout: ["600"]}')

COMMAND_ID=$(aws ssm send-command \
  --region "$AWS_REGION" \
  --instance-ids "$EC2_INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --comment "Deploy monitoring stack" \
  --parameters "$PARAMS" \
  --query 'Command.CommandId' --output text)

echo "SSM CommandId: $COMMAND_ID"

STATUS=Pending
for _ in $(seq 1 120); do
  STATUS=$(aws ssm get-command-invocation \
    --region "$AWS_REGION" --command-id "$COMMAND_ID" --instance-id "$EC2_INSTANCE_ID" \
    --query Status --output text 2>/dev/null || echo Pending)
  case "$STATUS" in Success|Failed|Cancelled|TimedOut) break ;; esac
  sleep 5
done

aws ssm get-command-invocation \
  --region "$AWS_REGION" --command-id "$COMMAND_ID" --instance-id "$EC2_INSTANCE_ID" \
  --query '{Status:Status,Output:StandardOutputContent,Error:StandardErrorContent}' --output json

[[ "$STATUS" == Success ]]
