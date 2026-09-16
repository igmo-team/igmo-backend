#!/usr/bin/env bash
set -Eeuo pipefail

CONTAINER_NAME="igmo-backend"
PRODUCTION_COMPOSE_FILE="docker-compose.prod.yml"

require_value() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Required environment variable is missing: ${name}" >&2
    exit 1
  fi
}

require_value AWS_REGION
require_value EC2_INSTANCE_ID
require_value IMAGE_URI
require_value GEMINI_API_KEY
require_value IGMO_AI_GEMINI_MODEL
require_value IGMO_AI_GEMINI_IMAGE_SIZE
require_value IGMO_GAME_DISCONNECT_GRACE
require_value IGMO_GAME_PROMPT_DURATION
require_value IGMO_GAME_GUESS_DURATION
require_value IGMO_GAME_VOTE_DURATION
require_value IGMO_GAME_RESULT_DURATION
require_value IGMO_GAME_IMAGE_GENERATION_COMPLETION_DELAY
require_value IGMO_GAME_DRAIN_TIMEOUT
require_value IGMO_IMAGE_STORAGE_S3_BUCKET
require_value IGMO_IMAGE_STORAGE_S3_REGION
require_value IGMO_IMAGE_STORAGE_S3_KEY_PREFIX
require_value IGMO_ADMIN_IMAGE_STORAGE_S3_BUCKET
require_value IGMO_ADMIN_IMAGE_STORAGE_S3_KEY_PREFIX

if [[ ! -f "$PRODUCTION_COMPOSE_FILE" ]]; then
  echo "Production Compose file is missing: ${PRODUCTION_COMPOSE_FILE}" >&2
  exit 1
fi

SERVER_PORT="${SERVER_PORT:-8080}"
IGMO_IMAGE_STORAGE_S3_PUBLIC_BASE_URL="${IGMO_IMAGE_STORAGE_S3_PUBLIC_BASE_URL:-}"
IGMO_ADMIN_IMAGE_GENERATION_USERNAME="${IGMO_ADMIN_IMAGE_GENERATION_USERNAME:-}"
IGMO_ADMIN_IMAGE_GENERATION_PASSWORD="${IGMO_ADMIN_IMAGE_GENERATION_PASSWORD:-}"
IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_MODELS="${IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_MODELS:-}"
IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_IMAGE_SIZES="${IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_IMAGE_SIZES:-}"
PRODUCTION_COMPOSE_FILE_B64=$(base64 < "$PRODUCTION_COMPOSE_FILE" | tr -d '\n')

runtime_environment() {
  printf 'SPRING_PROFILES_ACTIVE=prod\n'
  printf 'SERVER_PORT=%s\n' "$SERVER_PORT"
  printf 'JAVA_TOOL_OPTIONS=%s\n' '-Xms128m -Xmx768m -Duser.timezone=Asia/Seoul'
  printf 'GEMINI_API_KEY=%s\n' "$GEMINI_API_KEY"
  printf 'IGMO_AI_GEMINI_MODEL=%s\n' "$IGMO_AI_GEMINI_MODEL"
  printf 'IGMO_AI_GEMINI_IMAGE_SIZE=%s\n' "$IGMO_AI_GEMINI_IMAGE_SIZE"
  printf 'IGMO_GAME_DISCONNECT_GRACE=%s\n' "$IGMO_GAME_DISCONNECT_GRACE"
  printf 'IGMO_GAME_PROMPT_DURATION=%s\n' "$IGMO_GAME_PROMPT_DURATION"
  printf 'IGMO_GAME_GUESS_DURATION=%s\n' "$IGMO_GAME_GUESS_DURATION"
  printf 'IGMO_GAME_VOTE_DURATION=%s\n' "$IGMO_GAME_VOTE_DURATION"
  printf 'IGMO_GAME_RESULT_DURATION=%s\n' "$IGMO_GAME_RESULT_DURATION"
  printf 'IGMO_GAME_IMAGE_GENERATION_COMPLETION_DELAY=%s\n' "$IGMO_GAME_IMAGE_GENERATION_COMPLETION_DELAY"
  printf 'IGMO_GAME_DRAIN_TIMEOUT=%s\n' "$IGMO_GAME_DRAIN_TIMEOUT"
  printf 'IGMO_IMAGE_STORAGE_S3_BUCKET=%s\n' "$IGMO_IMAGE_STORAGE_S3_BUCKET"
  printf 'IGMO_IMAGE_STORAGE_S3_REGION=%s\n' "$IGMO_IMAGE_STORAGE_S3_REGION"
  printf 'IGMO_IMAGE_STORAGE_S3_KEY_PREFIX=%s\n' "$IGMO_IMAGE_STORAGE_S3_KEY_PREFIX"
  printf 'IGMO_IMAGE_STORAGE_S3_PUBLIC_BASE_URL=%s\n' "$IGMO_IMAGE_STORAGE_S3_PUBLIC_BASE_URL"
  printf 'IGMO_ADMIN_IMAGE_GENERATION_USERNAME=%s\n' "$IGMO_ADMIN_IMAGE_GENERATION_USERNAME"
  printf 'IGMO_ADMIN_IMAGE_GENERATION_PASSWORD=%s\n' "$IGMO_ADMIN_IMAGE_GENERATION_PASSWORD"
  printf 'IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_MODELS=%s\n' "$IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_MODELS"
  printf 'IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_IMAGE_SIZES=%s\n' "$IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_IMAGE_SIZES"
  printf 'IGMO_ADMIN_IMAGE_STORAGE_S3_BUCKET=%s\n' "$IGMO_ADMIN_IMAGE_STORAGE_S3_BUCKET"
  printf 'IGMO_ADMIN_IMAGE_STORAGE_S3_KEY_PREFIX=%s\n' "$IGMO_ADMIN_IMAGE_STORAGE_S3_KEY_PREFIX"
}

RUNTIME_ENV_FILE_B64=$(runtime_environment | base64 | tr -d '\n')

if [[ ! "$AWS_REGION" =~ ^[a-z0-9-]+$ ]]; then
  echo "Invalid AWS_REGION: ${AWS_REGION}" >&2
  exit 1
fi

if [[ ! "$EC2_INSTANCE_ID" =~ ^i-[a-f0-9]+$ ]]; then
  echo "Invalid EC2_INSTANCE_ID: ${EC2_INSTANCE_ID}" >&2
  exit 1
fi

if [[ ! "$IMAGE_URI" =~ ^[A-Za-z0-9._:/@-]+$ ]]; then
  echo "Invalid IMAGE_URI: ${IMAGE_URI}" >&2
  exit 1
fi

if [[ ! "$IGMO_GAME_DRAIN_TIMEOUT" =~ ^[1-9][0-9]*s$ ]]; then
  echo 'IGMO_GAME_DRAIN_TIMEOUT must be a positive number of seconds, for example 300s.' >&2
  exit 1
fi
DRAIN_TIMEOUT_SECONDS="${IGMO_GAME_DRAIN_TIMEOUT%s}"
STOP_TIMEOUT_SECONDS=$((DRAIN_TIMEOUT_SECONDS + 30))
STOP_GRACE_PERIOD="${STOP_TIMEOUT_SECONDS}s"
SSM_EXECUTION_TIMEOUT_SECONDS=$((DRAIN_TIMEOUT_SECONDS + 60))

REMOTE_COMMAND=$(cat <<EOF
set -eu

AWS_REGION='${AWS_REGION}'
IMAGE_URI='${IMAGE_URI}'
SERVER_PORT='${SERVER_PORT}'
RUNTIME_ENV_FILE_B64='${RUNTIME_ENV_FILE_B64}'
STOP_TIMEOUT_SECONDS='${STOP_TIMEOUT_SECONDS}'
STOP_GRACE_PERIOD='${STOP_GRACE_PERIOD}'
CONTAINER_NAME='${CONTAINER_NAME}'
COMPOSE_PROJECT_NAME='igmo-production'
COMPOSE_DIRECTORY='/opt/igmo'
COMPOSE_FILE="\$COMPOSE_DIRECTORY/docker-compose.prod.yml"
REGISTRY="\${IMAGE_URI%%/*}"

if ! command -v aws >/dev/null 2>&1; then
  echo 'AWS CLI is not installed on the instance.' >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo 'Docker is not installed on the instance.' >&2
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo 'Docker Compose v2 is not installed on the instance.' >&2
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo 'curl is not installed on the instance.' >&2
  exit 1
fi

NGINX_CONFIG='/etc/nginx/sites-available/igmo'
CONTAINER_PORT='8080'
export IMAGE_URI STOP_GRACE_PERIOD

mkdir -p "\$COMPOSE_DIRECTORY"
printf '%s' '${PRODUCTION_COMPOSE_FILE_B64}' | base64 -d > "\$COMPOSE_FILE"
chmod 600 "\$COMPOSE_FILE"
mkdir -p /run/igmo
chmod 700 /run/igmo
RUNTIME_ENV_FILE='/run/igmo/prod.env'
RUNTIME_ENV_TEMP_FILE=\$(mktemp /run/igmo/prod.env.XXXXXX)
trap 'rm -f "\$RUNTIME_ENV_TEMP_FILE"' EXIT
printf '%s' "\$RUNTIME_ENV_FILE_B64" | base64 -d > "\$RUNTIME_ENV_TEMP_FILE"
chmod 600 "\$RUNTIME_ENV_TEMP_FILE"
mv -f "\$RUNTIME_ENV_TEMP_FILE" "\$RUNTIME_ENV_FILE"
chmod 600 /run/igmo/prod.env
if ! docker compose --project-name "\$COMPOSE_PROJECT_NAME" --file "\$COMPOSE_FILE" config -q; then
  echo 'Production Compose configuration is invalid.' >&2
  exit 1
fi

read_active_port() {
  CONFIG_DUMP=\$(nginx -T 2>/dev/null)
  printf '%s\n' "\$CONFIG_DUMP" \
    | awk '
      /upstream[[:space:]]+igmo_backend[[:space:]]*\{/ { inside=1; next }
      inside && /server[[:space:]]+127\.0\.0\.1:(8080|8081);/ {
        print
        exit
      }
      inside && /^[[:space:]]*\}/ { inside=0 }
    ' \
    | sed -nE 's/.*127\.0\.0\.1:(8080|8081);.*/\1/p'
}

configured_host_port() {
  docker inspect --format '{{json .HostConfig.PortBindings}}' "\$1" 2>/dev/null \
    | sed -nE 's/.*"HostPort":"([0-9]+)".*/\1/p' \
    | head -1
}

running_containers_on_port() {
  for CONTAINER_ID in \$(docker ps -q); do
    HOST_PORT=\$(docker port "\$CONTAINER_ID" "\$CONTAINER_PORT/tcp" 2>/dev/null \
      | sed -nE 's/.*:([0-9]+)$/\1/p' \
      | head -1)
    if [ "\$HOST_PORT" = "\$1" ]; then
      docker inspect --format '{{.Name}}' "\$CONTAINER_ID" | sed 's#^/##'
    fi
  done
}

cleanup_target() {
  COMPOSE_TARGET_CONTAINER_IDS=\$(docker compose --project-name "\$COMPOSE_PROJECT_NAME" --file "\$COMPOSE_FILE" ps --all --quiet "\$TARGET_SERVICE" 2>/dev/null || true)
  if [ -n "\$COMPOSE_TARGET_CONTAINER_IDS" ]; then
    if ! docker compose --project-name "\$COMPOSE_PROJECT_NAME" --file "\$COMPOSE_FILE" stop --timeout "\$STOP_TIMEOUT_SECONDS" "\$TARGET_SERVICE" >/dev/null; then
      return 1
    fi
    docker compose --project-name "\$COMPOSE_PROJECT_NAME" --file "\$COMPOSE_FILE" rm --force "\$TARGET_SERVICE" >/dev/null
    return 0
  fi

  if docker container inspect "\$TARGET_CONTAINER" >/dev/null 2>&1; then
    TARGET_RUNNING=\$(docker inspect --format '{{.State.Running}}' "\$TARGET_CONTAINER")
    if [ "\$TARGET_RUNNING" = 'true' ] && ! docker stop --time "\$STOP_TIMEOUT_SECONDS" "\$TARGET_CONTAINER" >/dev/null; then
      return 1
    fi
    docker rm "\$TARGET_CONTAINER" >/dev/null
  fi
}

restore_nginx() {
  cp -a "\$NGINX_BACKUP" "\$NGINX_CONFIG" \
    && nginx -t \
    && systemctl reload nginx
}

if [ "\$SERVER_PORT" != "\$CONTAINER_PORT" ]; then
  echo 'SERVER_PORT must remain 8080 for Blue/Green host port switching.' >&2
  exit 1
fi

if ! nginx -T >/dev/null 2>&1; then
  echo 'The current Nginx configuration could not be parsed.' >&2
  exit 1
fi

if ! nginx -T 2>/dev/null | grep -Eq 'upstream[[:space:]]+igmo_backend[[:space:]]*\{'; then
  echo 'Nginx upstream igmo_backend is not configured.' >&2
  exit 1
fi

ACTIVE_PORT=\$(read_active_port)
if [ "\$ACTIVE_PORT" = '8080' ]; then
    TARGET_PORT='8081'
    TARGET_CONTAINER='igmo-backend-green'
    TARGET_SERVICE='app-green'
elif [ "\$ACTIVE_PORT" = '8081' ]; then
    TARGET_PORT='8080'
    TARGET_CONTAINER='igmo-backend-blue'
    TARGET_SERVICE='app-blue'
else
    echo 'Unable to determine the active IGMO backend port.' >&2
    exit 1
fi

ACTIVE_CONTAINERS=\$(running_containers_on_port "\$ACTIVE_PORT")
if [ "\$(printf '%s\n' "\$ACTIVE_CONTAINERS" | sed '/^$/d' | wc -l | tr -d ' ')" != '1' ]; then
  echo "Expected exactly one running container on active port \$ACTIVE_PORT." >&2
  exit 1
fi
ACTIVE_CONTAINER=\$(printf '%s\n' "\$ACTIVE_CONTAINERS" | sed '/^$/d' | head -1)
if [ "\$ACTIVE_PORT" = '8080' ]; then
  ACTIVE_SERVICE='app-blue'
else
  ACTIVE_SERVICE='app-green'
fi

aws ecr get-login-password --region "\$AWS_REGION" \
  | docker login --username AWS --password-stdin "\$REGISTRY"

docker pull "\$IMAGE_URI"

if docker container inspect "\$TARGET_CONTAINER" >/dev/null 2>&1; then
  TARGET_EXISTING_PORT=\$(configured_host_port "\$TARGET_CONTAINER")
  if [ "\$TARGET_EXISTING_PORT" != "\$TARGET_PORT" ] || [ "\$TARGET_CONTAINER" = "\$ACTIVE_CONTAINER" ]; then
    echo "Target container \$TARGET_CONTAINER has an unexpected port or is active." >&2
    exit 1
  fi
  if ! cleanup_target; then
    echo 'Target stale container could not be removed.' >&2
    exit 1
  fi
fi

TARGET_OWNERS=\$(running_containers_on_port "\$TARGET_PORT")
if [ -n "\$(printf '%s\n' "\$TARGET_OWNERS" | sed '/^$/d')" ]; then
  echo "Target port \$TARGET_PORT is already used by another container." >&2
  exit 1
fi

start_container() {
  docker compose --project-name "\$COMPOSE_PROJECT_NAME" --file "\$COMPOSE_FILE" up -d --no-deps "\$1"
}

if ! start_container "\$TARGET_SERVICE"; then
  if cleanup_target; then
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=success'
  else
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=failed' >&2
  fi
  echo 'Target container did not start.' >&2
  exit 1
fi

HEALTHY='false'
ATTEMPT=0
while [ "\$ATTEMPT" -lt 30 ]; do
  HTTP_STATUS=\$(curl --silent --output /dev/null --write-out '%{http_code}' "http://127.0.0.1:\$TARGET_PORT/actuator/health" || true)
  if [ "\$HTTP_STATUS" = '200' ]; then
    HEALTHY='true'
    break
  fi
  ATTEMPT=\$((ATTEMPT + 1))
  sleep 2
done

if [ "\$HEALTHY" != 'true' ]; then
  echo 'Target application did not become healthy.' >&2
  docker logs --tail 200 "\$TARGET_CONTAINER" || true
  if cleanup_target; then
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=success'
  else
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=failed' >&2
  fi
  exit 1
fi

NGINX_BACKUP="\$NGINX_CONFIG.bak.\$(date +%s)"
if ! cp -a "\$NGINX_CONFIG" "\$NGINX_BACKUP"; then
  echo 'The current Nginx configuration could not be backed up.' >&2
  cleanup_target || true
  exit 1
fi

if ! sed -E -i "/^[[:space:]]*upstream[[:space:]]+igmo_backend[[:space:]]*[{]/,/^[[:space:]]*[}]/ s@127\.0\.0\.1:(8080|8081);@127.0.0.1:\$TARGET_PORT;@" "\$NGINX_CONFIG"; then
  echo 'Nginx backend could not be changed.' >&2
  if restore_nginx; then
    echo 'IGMO_DEPLOY_NGINX_RECOVERY_STATUS=success'
  else
    echo 'IGMO_DEPLOY_NGINX_RECOVERY_STATUS=failed' >&2
  fi
  if cleanup_target; then
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=success'
  else
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=failed' >&2
  fi
  exit 1
fi
CONFIGURED_PORT=\$(awk '
  /upstream[[:space:]]+igmo_backend[[:space:]]*\{/ { inside=1; next }
  inside && /server[[:space:]]+127\.0\.0\.1:(8080|8081);/ {
    print
    exit
  }
  inside && /^[[:space:]]*\}/ { inside=0 }
' "\$NGINX_CONFIG" | sed -nE 's/.*127\.0\.0\.1:(8080|8081);.*/\1/p')
if [ "\$CONFIGURED_PORT" != "\$TARGET_PORT" ]; then
  echo 'Nginx configuration did not switch to the target port.' >&2
  if restore_nginx; then
    echo 'IGMO_DEPLOY_NGINX_RECOVERY_STATUS=success'
  else
    echo 'IGMO_DEPLOY_NGINX_RECOVERY_STATUS=failed' >&2
  fi
  if cleanup_target; then
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=success'
  else
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=failed' >&2
  fi
  exit 1
fi

if ! nginx -t; then
  echo 'The Nginx configuration test failed.' >&2
  if restore_nginx; then
    echo 'IGMO_DEPLOY_NGINX_RECOVERY_STATUS=success'
  else
    echo 'IGMO_DEPLOY_NGINX_RECOVERY_STATUS=failed' >&2
  fi
  if cleanup_target; then
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=success'
  else
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=failed' >&2
  fi
  exit 1
fi

if ! systemctl reload nginx; then
  echo 'The Nginx reload failed.' >&2
  if restore_nginx; then
    echo 'IGMO_DEPLOY_NGINX_RECOVERY_STATUS=success'
  else
    echo 'IGMO_DEPLOY_NGINX_RECOVERY_STATUS=failed' >&2
  fi
  if cleanup_target; then
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=success'
  else
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=failed' >&2
  fi
  exit 1
fi

if [ "\$(read_active_port)" != "\$TARGET_PORT" ]; then
  echo 'Nginx did not activate the target backend.' >&2
  if restore_nginx; then
    echo 'IGMO_DEPLOY_NGINX_RECOVERY_STATUS=success'
  else
    echo 'IGMO_DEPLOY_NGINX_RECOVERY_STATUS=failed' >&2
  fi
  if cleanup_target; then
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=success'
  else
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=failed' >&2
  fi
  exit 1
fi

if [ "\$(docker inspect --format '{{.State.Running}}' "\$TARGET_CONTAINER")" != 'true' ]; then
  echo 'Target container stopped after Health Check.' >&2
  if restore_nginx; then
    echo 'IGMO_DEPLOY_NGINX_RECOVERY_STATUS=success'
  else
    echo 'IGMO_DEPLOY_NGINX_RECOVERY_STATUS=failed' >&2
  fi
  if cleanup_target; then
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=success'
  else
    echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=failed' >&2
  fi
  exit 1
fi

stop_active_container() {
  COMPOSE_ACTIVE_CONTAINER_IDS=\$(docker compose --project-name "\$COMPOSE_PROJECT_NAME" --file "\$COMPOSE_FILE" ps --all --quiet "\$ACTIVE_SERVICE" 2>/dev/null || true)
  if [ -n "\$COMPOSE_ACTIVE_CONTAINER_IDS" ]; then
    docker compose --project-name "\$COMPOSE_PROJECT_NAME" --file "\$COMPOSE_FILE" stop --timeout "\$STOP_TIMEOUT_SECONDS" "\$ACTIVE_SERVICE" >/dev/null
  else
    docker stop --time "\$STOP_TIMEOUT_SECONDS" "\$ACTIVE_CONTAINER" >/dev/null 2>&1
  fi
}

remove_active_container() {
  COMPOSE_ACTIVE_CONTAINER_IDS=\$(docker compose --project-name "\$COMPOSE_PROJECT_NAME" --file "\$COMPOSE_FILE" ps --all --quiet "\$ACTIVE_SERVICE" 2>/dev/null || true)
  if [ -n "\$COMPOSE_ACTIVE_CONTAINER_IDS" ]; then
    docker compose --project-name "\$COMPOSE_PROJECT_NAME" --file "\$COMPOSE_FILE" rm --force "\$ACTIVE_SERVICE" >/dev/null
  else
    docker rm "\$ACTIVE_CONTAINER" >/dev/null 2>&1
  fi
}

if ! stop_active_container; then
  echo 'The active container could not be stopped gracefully.' >&2
  exit 1
fi

if [ "\$(docker inspect --format '{{.State.Running}}' "\$ACTIVE_CONTAINER")" = 'true' ]; then
  echo 'The active container is still running after graceful stop.' >&2
  exit 1
fi

if ! remove_active_container; then
  echo 'The active container could not be removed.' >&2
  echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=not_required'
  exit 1
fi

if docker container inspect "\$ACTIVE_CONTAINER" >/dev/null 2>&1; then
  echo 'The active container is still present after cleanup.' >&2
  exit 1
fi

docker image prune --all --force --filter 'until=168h' >/dev/null 2>&1 || true
echo 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=success'
echo 'IGMO_DEPLOY_NGINX_RECOVERY_STATUS=not_required'
echo "Deployed \$IMAGE_URI to \$TARGET_CONTAINER on \$TARGET_PORT"
EOF
)

PARAMETERS=$(jq --null-input \
  --arg command "$REMOTE_COMMAND" \
  --arg execution_timeout "$SSM_EXECUTION_TIMEOUT_SECONDS" \
  '{commands: [$command], executionTimeout: [$execution_timeout]}')

DEPLOY_COMMENT="Deploy ${CONTAINER_NAME} ${IMAGE_URI##*:}"
DEPLOY_COMMENT="${DEPLOY_COMMENT:0:100}"

COMMAND_ID=$(aws ssm send-command \
  --region "$AWS_REGION" \
  --instance-ids "$EC2_INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --comment "$DEPLOY_COMMENT" \
  --parameters "$PARAMETERS" \
  --query 'Command.CommandId' \
  --output text)

echo "SSM command: ${COMMAND_ID}"

STATUS='Pending'
POLL_ATTEMPTS=$(( (SSM_EXECUTION_TIMEOUT_SECONDS + 30) / 5 ))
for _ in $(seq 1 "$POLL_ATTEMPTS"); do
  STATUS=$(aws ssm get-command-invocation \
    --region "$AWS_REGION" \
    --command-id "$COMMAND_ID" \
    --instance-id "$EC2_INSTANCE_ID" \
    --query 'Status' \
    --output text 2>/dev/null || echo 'Pending')
  case "$STATUS" in
    Success|Failed|Cancelled|TimedOut)
      break
      ;;
  esac
  sleep 5
done

INVOCATION_JSON=$(aws ssm get-command-invocation \
  --region "$AWS_REGION" \
  --command-id "$COMMAND_ID" \
  --instance-id "$EC2_INSTANCE_ID" \
  --query '{Status:Status,Output:StandardOutputContent,Error:StandardErrorContent}' \
  --output json)

printf '%s\n' "$INVOCATION_JSON"

DEPLOY_FAILURE_STAGE='AWS SSM Deployment'
DEPLOY_FAILURE_REASON="SSM 명령이 $STATUS 상태로 종료되었습니다."
DEPLOY_CLEANUP_STATUS='not_attempted'
DEPLOY_NGINX_RECOVERY_STATUS='not_required'

if grep -Fq 'Target application did not become healthy.' <<< "$INVOCATION_JSON"; then
  DEPLOY_FAILURE_STAGE='Application Health Check'
  DEPLOY_FAILURE_REASON='애플리케이션이 60초 내 /actuator/health HTTP 200을 반환하지 않았습니다.'
elif grep -Fq 'Unable to determine the active IGMO backend port.' <<< "$INVOCATION_JSON"; then
  DEPLOY_FAILURE_STAGE='Active Backend Detection'
  DEPLOY_FAILURE_REASON='Nginx upstream에서 Active backend 포트를 확인하지 못했습니다.'
elif grep -Fq 'Nginx upstream igmo_backend is not configured.' <<< "$INVOCATION_JSON"; then
  DEPLOY_FAILURE_STAGE='Nginx Backend Configuration'
  DEPLOY_FAILURE_REASON='Blue/Green 배포에 필요한 igmo_backend upstream이 없습니다.'
elif grep -Fq 'Target container did not start.' <<< "$INVOCATION_JSON"; then
  DEPLOY_FAILURE_STAGE='Target Container Startup'
  DEPLOY_FAILURE_REASON='Target 컨테이너를 실행하지 못했습니다.'
elif grep -Fq 'Nginx backend could not be changed.' <<< "$INVOCATION_JSON"; then
  DEPLOY_FAILURE_STAGE='Nginx Backend Switch'
  DEPLOY_FAILURE_REASON='Nginx backend 포트를 변경하지 못했습니다.'
elif grep -Fq 'The Nginx configuration test failed.' <<< "$INVOCATION_JSON"; then
  DEPLOY_FAILURE_STAGE='Nginx Configuration Test'
  DEPLOY_FAILURE_REASON='변경된 Nginx 설정 검증에 실패했습니다.'
elif grep -Fq 'The Nginx reload failed.' <<< "$INVOCATION_JSON"; then
  DEPLOY_FAILURE_STAGE='Nginx Reload'
  DEPLOY_FAILURE_REASON='Nginx reload에 실패했습니다.'
elif grep -Fq 'The active container could not be removed.' <<< "$INVOCATION_JSON"; then
  DEPLOY_FAILURE_STAGE='Active Container Cleanup'
  DEPLOY_FAILURE_REASON='Nginx 전환 후 기존 Active 컨테이너 제거에 실패했습니다.'
elif grep -Fq 'The active container could not be stopped gracefully.' <<< "$INVOCATION_JSON"; then
  DEPLOY_FAILURE_STAGE='Active Container Drain'
  DEPLOY_FAILURE_REASON='기존 Active 컨테이너가 Game Drain 시간 내 정상 종료되지 않았습니다.'
fi

if grep -Fq 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=success' <<< "$INVOCATION_JSON"; then
  DEPLOY_CLEANUP_STATUS='success'
elif grep -Fq 'IGMO_DEPLOY_TARGET_CLEANUP_STATUS=failed' <<< "$INVOCATION_JSON"; then
  DEPLOY_CLEANUP_STATUS='failed'
fi

if grep -Fq 'IGMO_DEPLOY_NGINX_RECOVERY_STATUS=success' <<< "$INVOCATION_JSON"; then
  DEPLOY_NGINX_RECOVERY_STATUS='success'
elif grep -Fq 'IGMO_DEPLOY_NGINX_RECOVERY_STATUS=failed' <<< "$INVOCATION_JSON"; then
  DEPLOY_NGINX_RECOVERY_STATUS='failed'
fi

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    echo "failure_stage=$DEPLOY_FAILURE_STAGE"
    echo "failure_reason=$DEPLOY_FAILURE_REASON"
    echo "cleanup_status=$DEPLOY_CLEANUP_STATUS"
    echo "nginx_recovery_status=$DEPLOY_NGINX_RECOVERY_STATUS"
  } >> "$GITHUB_OUTPUT"
fi

if [ "$STATUS" != 'Success' ]; then
  exit 1
fi
