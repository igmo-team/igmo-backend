#!/usr/bin/env bash
# 저장소의 nginx 설정을 EC2에 반영한다 (SSM 경유, 무중단 reload).
#
# 사용법:
#   ./deploy/nginx/apply.sh [conf-file]
#     conf-file 생략 시 deploy/nginx/igmo.conf
#
# 환경변수:
#   EC2_INSTANCE_ID  대상 인스턴스 (기본 i-0d44e815cf354804c)
#   AWS_REGION       리전 (기본 ap-northeast-2)
#
# 필요 도구: aws CLI(로그인 상태), base64
set -Eeuo pipefail

AWS_REGION="${AWS_REGION:-ap-northeast-2}"
EC2_INSTANCE_ID="${EC2_INSTANCE_ID:-i-0d44e815cf354804c}"
REMOTE_TARGET="/etc/nginx/sites-available/igmo"
ENABLED_LINK="/etc/nginx/sites-enabled/igmo"

CONF_FILE="${1:-deploy/nginx/igmo.conf}"
if [[ ! -f "$CONF_FILE" ]]; then
  echo "설정 파일을 찾을 수 없습니다: $CONF_FILE" >&2
  exit 1
fi

CONF_B64=$(base64 -w0 "$CONF_FILE")

# EC2에서 실행할 스크립트: 백업 -> 교체 -> 심볼릭 링크 보장 -> nginx -t -> reload(실패 시 복원)
REMOTE_SCRIPT="set -eu
TS=\$(date +%s)
BACKUP='$REMOTE_TARGET.bak.'\$TS
cp -a '$REMOTE_TARGET' \"\$BACKUP\"
echo '$CONF_B64' | base64 -d > '$REMOTE_TARGET'
ln -sfn '$REMOTE_TARGET' '$ENABLED_LINK'
if nginx -t; then
  systemctl reload nginx
  echo \"RESULT=RELOADED_OK (backup: \$BACKUP)\"
else
  cp -a \"\$BACKUP\" '$REMOTE_TARGET'
  echo \"RESULT=RESTORED_BACKUP (nginx -t failed)\"
  exit 1
fi"

SCRIPT_B64=$(printf '%s' "$REMOTE_SCRIPT" | base64 -w0)
PARAMS="{\"commands\":[\"echo $SCRIPT_B64 | base64 -d | bash\"],\"executionTimeout\":[\"120\"]}"

echo "대상: $EC2_INSTANCE_ID ($AWS_REGION)"
echo "설정: $CONF_FILE"

CID=$(aws ssm send-command \
  --region "$AWS_REGION" \
  --instance-ids "$EC2_INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --comment "Apply nginx conf: $(basename "$CONF_FILE")" \
  --parameters "$PARAMS" \
  --query Command.CommandId --output text)
echo "SSM CommandId: $CID"

STATUS=Pending
for _ in $(seq 1 30); do
  STATUS=$(aws ssm get-command-invocation \
    --region "$AWS_REGION" --command-id "$CID" --instance-id "$EC2_INSTANCE_ID" \
    --query Status --output text 2>/dev/null || echo Pending)
  case "$STATUS" in Success|Failed|Cancelled|TimedOut) break ;; esac
  sleep 3
done

echo "Status: $STATUS"
aws ssm get-command-invocation \
  --region "$AWS_REGION" --command-id "$CID" --instance-id "$EC2_INSTANCE_ID" \
  --query '{Output:StandardOutputContent,Error:StandardErrorContent}' --output text

[[ "$STATUS" == Success ]]
