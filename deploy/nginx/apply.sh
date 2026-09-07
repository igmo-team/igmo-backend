#!/usr/bin/env bash
# 저장소의 nginx 설정을 EC2에 반영한다 (SSM 경유, 무중단 reload).
#
# 사용법:
#   ./deploy/nginx/apply.sh [conf-file] [site-name]
#     conf-file 생략 시 deploy/nginx/igmo.conf
#     site-name 생략 시 igmo
#
# 환경변수:
#   EC2_INSTANCE_ID  대상 인스턴스 (기본 i-0d44e815cf354804c)
#   AWS_REGION       리전 (기본 ap-northeast-2)
#
# 필요 도구: aws CLI(로그인 상태), base64
set -Eeuo pipefail

AWS_REGION="${AWS_REGION:-ap-northeast-2}"
EC2_INSTANCE_ID="${EC2_INSTANCE_ID:-i-0d44e815cf354804c}"
CONF_FILE="${1:-deploy/nginx/igmo.conf}"
SITE_NAME="${2:-igmo}"

if [[ ! "$SITE_NAME" =~ ^[a-z0-9-]+$ ]]; then
  echo "유효하지 않은 site-name입니다: $SITE_NAME" >&2
  exit 1
fi

REMOTE_TARGET="/etc/nginx/sites-available/$SITE_NAME"
ENABLED_LINK="/etc/nginx/sites-enabled/$SITE_NAME"

if [[ ! -f "$CONF_FILE" ]]; then
  echo "설정 파일을 찾을 수 없습니다: $CONF_FILE" >&2
  exit 1
fi

CONF_B64=$(base64 < "$CONF_FILE" | tr -d '\n')

REMOTE_SCRIPT="set -eu
TS=\$(date +%s)
BACKUP=''
TMP_TARGET='$REMOTE_TARGET.tmp.'\$TS

detect_active_port() {
  CONFIG_DUMP=\$(nginx -T 2>/dev/null)
  UPSTREAM_PORT=\$(printf '%s\\n' \"\$CONFIG_DUMP\" | awk '
    /upstream[[:space:]]+igmo_backend[[:space:]]*\\{/ { inside=1; next }
    inside && /server[[:space:]]+127\\.0\\.0\\.1:(8080|8081);/ {
      print \$0
      exit
    }
    inside && /^[[:space:]]*\\}/ { inside=0 }
  ' | sed -nE 's/.*127\\.0\\.0\\.1:(8080|8081);.*/\\1/p')
  if [ \"\$UPSTREAM_PORT\" = '8080' ] || [ \"\$UPSTREAM_PORT\" = '8081' ]; then
    printf '%s' \"\$UPSTREAM_PORT\"
    return 0
  fi

  LEGACY_PORTS=\$(printf '%s\\n' \"\$CONFIG_DUMP\" \\
    | grep -oE 'proxy_pass[[:space:]]+http://127\\.0\\.0\\.1:(8080|8081);' \\
    | sed -nE 's/.*:([0-9]+);/\\1/p' \\
    | sort -u || true)
  if [ \"\$(printf '%s\\n' \"\$LEGACY_PORTS\" | sed '/^\$/d' | wc -l | tr -d ' ')\" = '1' ]; then
    printf '%s' \"\$LEGACY_PORTS\"
    return 0
  fi
  return 1
}

restore_config() {
  if [ -n \"\$BACKUP\" ]; then
    cp -a \"\$BACKUP\" '$REMOTE_TARGET'
  else
    rm -f '$ENABLED_LINK' '$REMOTE_TARGET'
  fi
}

ACTIVE_PORT=''
if [ '$SITE_NAME' = 'igmo' ]; then
  ACTIVE_PORT=\$(detect_active_port) || {
    echo 'Unable to determine the active IGMO backend port.' >&2
    exit 1
  }
fi

if [ -e '$REMOTE_TARGET' ]; then
  BACKUP='$REMOTE_TARGET.bak.'\$TS
  cp -a '$REMOTE_TARGET' \"\$BACKUP\"
fi
echo '$CONF_B64' | base64 -d > \"\$TMP_TARGET\"
if [ '$SITE_NAME' = 'igmo' ]; then
  sed -E -i \"/^[[:space:]]*upstream[[:space:]]+igmo_backend[[:space:]]*[{]/,/^[[:space:]]*[}]/ s@127\\.0\\.0\\.1:(8080|8081);@127.0.0.1:\$ACTIVE_PORT;@\" \"\$TMP_TARGET\"
fi
mv \"\$TMP_TARGET\" '$REMOTE_TARGET'
ln -sfn '$REMOTE_TARGET' '$ENABLED_LINK'
if ! nginx -t; then
  restore_config
  rm -f \"\$TMP_TARGET\"
  echo \"RESULT=RESTORED_AFTER_NGINX_TEST_FAILURE\"
  exit 1
fi
if ! systemctl reload nginx; then
  restore_config
  if nginx -t && systemctl reload nginx; then
    echo \"RESULT=RESTORED_AFTER_NGINX_RELOAD_FAILURE\"
  else
    echo \"RESULT=RESTORE_FAILED_AFTER_NGINX_RELOAD_FAILURE\" >&2
  fi
  rm -f \"\$TMP_TARGET\"
  exit 1
fi
rm -f \"\$TMP_TARGET\"
echo \"RESULT=RELOADED_OK\""

SCRIPT_B64=$(printf '%s' "$REMOTE_SCRIPT" | base64 | tr -d '\n')
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
