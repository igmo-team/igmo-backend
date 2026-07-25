#!/usr/bin/env bash
set -Eeuo pipefail

CONTAINER_NAME="igmo-backend"
CLOUDWATCH_LOG_GROUP="/igmo/prod/app"

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
require_value IGMO_IMAGE_STORAGE_S3_BUCKET
require_value IGMO_IMAGE_STORAGE_S3_REGION
require_value IGMO_IMAGE_STORAGE_S3_KEY_PREFIX
require_value IGMO_ADMIN_IMAGE_STORAGE_S3_BUCKET
require_value IGMO_ADMIN_IMAGE_STORAGE_S3_KEY_PREFIX

SERVER_PORT="${SERVER_PORT:-8080}"
IGMO_IMAGE_STORAGE_S3_PUBLIC_BASE_URL="${IGMO_IMAGE_STORAGE_S3_PUBLIC_BASE_URL:-}"
IGMO_ADMIN_IMAGE_GENERATION_USERNAME="${IGMO_ADMIN_IMAGE_GENERATION_USERNAME:-}"
IGMO_ADMIN_IMAGE_GENERATION_PASSWORD="${IGMO_ADMIN_IMAGE_GENERATION_PASSWORD:-}"
IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_MODELS="${IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_MODELS:-}"
IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_IMAGE_SIZES="${IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_IMAGE_SIZES:-}"
IGMO_ADMIN_IMAGE_GENERATION_USERNAME_B64=$(printf '%s' "$IGMO_ADMIN_IMAGE_GENERATION_USERNAME" | base64 | tr -d '\n')
IGMO_ADMIN_IMAGE_GENERATION_PASSWORD_B64=$(printf '%s' "$IGMO_ADMIN_IMAGE_GENERATION_PASSWORD" | base64 | tr -d '\n')

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

REMOTE_COMMAND=$(cat <<EOF
set -eu

AWS_REGION='${AWS_REGION}'
IMAGE_URI='${IMAGE_URI}'
SERVER_PORT='${SERVER_PORT}'
GEMINI_API_KEY='${GEMINI_API_KEY}'
IGMO_AI_GEMINI_MODEL='${IGMO_AI_GEMINI_MODEL}'
IGMO_AI_GEMINI_IMAGE_SIZE='${IGMO_AI_GEMINI_IMAGE_SIZE}'
IGMO_GAME_DISCONNECT_GRACE='${IGMO_GAME_DISCONNECT_GRACE}'
IGMO_GAME_PROMPT_DURATION='${IGMO_GAME_PROMPT_DURATION}'
IGMO_GAME_GUESS_DURATION='${IGMO_GAME_GUESS_DURATION}'
IGMO_GAME_VOTE_DURATION='${IGMO_GAME_VOTE_DURATION}'
IGMO_GAME_RESULT_DURATION='${IGMO_GAME_RESULT_DURATION}'
IGMO_GAME_IMAGE_GENERATION_COMPLETION_DELAY='${IGMO_GAME_IMAGE_GENERATION_COMPLETION_DELAY}'
IGMO_IMAGE_STORAGE_S3_BUCKET='${IGMO_IMAGE_STORAGE_S3_BUCKET}'
IGMO_IMAGE_STORAGE_S3_REGION='${IGMO_IMAGE_STORAGE_S3_REGION}'
IGMO_IMAGE_STORAGE_S3_KEY_PREFIX='${IGMO_IMAGE_STORAGE_S3_KEY_PREFIX}'
IGMO_IMAGE_STORAGE_S3_PUBLIC_BASE_URL='${IGMO_IMAGE_STORAGE_S3_PUBLIC_BASE_URL}'
IGMO_ADMIN_IMAGE_GENERATION_USERNAME=\$(printf '%s' '${IGMO_ADMIN_IMAGE_GENERATION_USERNAME_B64}' | base64 -d)
IGMO_ADMIN_IMAGE_GENERATION_PASSWORD=\$(printf '%s' '${IGMO_ADMIN_IMAGE_GENERATION_PASSWORD_B64}' | base64 -d)
IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_MODELS='${IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_MODELS}'
IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_IMAGE_SIZES='${IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_IMAGE_SIZES}'
IGMO_ADMIN_IMAGE_STORAGE_S3_BUCKET='${IGMO_ADMIN_IMAGE_STORAGE_S3_BUCKET}'
IGMO_ADMIN_IMAGE_STORAGE_S3_KEY_PREFIX='${IGMO_ADMIN_IMAGE_STORAGE_S3_KEY_PREFIX}'
CONTAINER_NAME='${CONTAINER_NAME}'
CLOUDWATCH_LOG_GROUP='${CLOUDWATCH_LOG_GROUP}'
REGISTRY="\${IMAGE_URI%%/*}"

if ! command -v aws >/dev/null 2>&1; then
  echo 'AWS CLI is not installed on the instance.' >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo 'Docker is not installed on the instance.' >&2
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo 'curl is not installed on the instance.' >&2
  exit 1
fi

aws ecr get-login-password --region "\$AWS_REGION" \
  | docker login --username AWS --password-stdin "\$REGISTRY"

docker pull "\$IMAGE_URI"

PREVIOUS_IMAGE=''
if docker container inspect "\$CONTAINER_NAME" >/dev/null 2>&1; then
  PREVIOUS_IMAGE=\$(docker container inspect --format '{{.Config.Image}}' "\$CONTAINER_NAME")
fi

docker rm --force "\$CONTAINER_NAME" >/dev/null 2>&1 || true

start_container() {
  IMAGE_TAG="\${1##*:}"
  docker run --detach \
    --name "\$CONTAINER_NAME" \
    --restart always \
    --stop-timeout 20 \
    --memory 1280m \
    --env SPRING_PROFILES_ACTIVE=prod \
    --env SERVER_PORT="\$SERVER_PORT" \
    --env 'JAVA_TOOL_OPTIONS=-Xms128m -Xmx768m' \
    --env GEMINI_API_KEY="\$GEMINI_API_KEY" \
    --env IGMO_AI_GEMINI_MODEL="\$IGMO_AI_GEMINI_MODEL" \
    --env IGMO_AI_GEMINI_IMAGE_SIZE="\$IGMO_AI_GEMINI_IMAGE_SIZE" \
    --env IGMO_GAME_DISCONNECT_GRACE="\$IGMO_GAME_DISCONNECT_GRACE" \
    --env IGMO_GAME_PROMPT_DURATION="\$IGMO_GAME_PROMPT_DURATION" \
    --env IGMO_GAME_GUESS_DURATION="\$IGMO_GAME_GUESS_DURATION" \
    --env IGMO_GAME_VOTE_DURATION="\$IGMO_GAME_VOTE_DURATION" \
    --env IGMO_GAME_RESULT_DURATION="\$IGMO_GAME_RESULT_DURATION" \
    --env IGMO_GAME_IMAGE_GENERATION_COMPLETION_DELAY="\$IGMO_GAME_IMAGE_GENERATION_COMPLETION_DELAY" \
    --env IGMO_IMAGE_STORAGE_S3_BUCKET="\$IGMO_IMAGE_STORAGE_S3_BUCKET" \
    --env IGMO_IMAGE_STORAGE_S3_REGION="\$IGMO_IMAGE_STORAGE_S3_REGION" \
    --env IGMO_IMAGE_STORAGE_S3_KEY_PREFIX="\$IGMO_IMAGE_STORAGE_S3_KEY_PREFIX" \
    --env IGMO_IMAGE_STORAGE_S3_PUBLIC_BASE_URL="\$IGMO_IMAGE_STORAGE_S3_PUBLIC_BASE_URL" \
    --env IGMO_ADMIN_IMAGE_GENERATION_USERNAME="\$IGMO_ADMIN_IMAGE_GENERATION_USERNAME" \
    --env IGMO_ADMIN_IMAGE_GENERATION_PASSWORD="\$IGMO_ADMIN_IMAGE_GENERATION_PASSWORD" \
    --env IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_MODELS="\$IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_MODELS" \
    --env IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_IMAGE_SIZES="\$IGMO_ADMIN_IMAGE_GENERATION_ALLOWED_IMAGE_SIZES" \
    --env IGMO_ADMIN_IMAGE_STORAGE_S3_BUCKET="\$IGMO_ADMIN_IMAGE_STORAGE_S3_BUCKET" \
    --env IGMO_ADMIN_IMAGE_STORAGE_S3_KEY_PREFIX="\$IGMO_ADMIN_IMAGE_STORAGE_S3_KEY_PREFIX" \
    --publish 127.0.0.1:8080:8080 \
    --log-driver awslogs \
    --log-opt awslogs-region="\$AWS_REGION" \
    --log-opt awslogs-group="\$CLOUDWATCH_LOG_GROUP" \
    --log-opt awslogs-stream="\$CONTAINER_NAME/\$IMAGE_TAG" \
    "\$1"
}

rollback() {
  docker rm --force "\$CONTAINER_NAME" >/dev/null 2>&1 || true
  if [ -n "\$PREVIOUS_IMAGE" ]; then
    echo "Rolling back to \$PREVIOUS_IMAGE"
    start_container "\$PREVIOUS_IMAGE"
  fi
}

if ! start_container "\$IMAGE_URI"; then
  rollback
  exit 1
fi

HEALTHY='false'
ATTEMPT=0
while [ "\$ATTEMPT" -lt 30 ]; do
  HTTP_STATUS=\$(curl --silent --output /dev/null --write-out '%{http_code}' http://127.0.0.1:8080/actuator/health || true)
  if [ "\$HTTP_STATUS" = '200' ]; then
    HEALTHY='true'
    break
  fi
  ATTEMPT=\$((ATTEMPT + 1))
  sleep 2
done

if [ "\$HEALTHY" != 'true' ]; then
  echo 'The new container did not become healthy.' >&2
  aws logs tail "\$CLOUDWATCH_LOG_GROUP" \
    --region "\$AWS_REGION" \
    --log-stream-names "\$CONTAINER_NAME/\${IMAGE_URI##*:}" \
    --since 10m || true
  rollback
  exit 1
fi

docker image prune --all --force --filter 'until=168h' >/dev/null
echo "Deployed \$IMAGE_URI"
EOF
)

PARAMETERS=$(jq --null-input \
  --arg command "$REMOTE_COMMAND" \
  '{commands: [$command], executionTimeout: ["300"]}')

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
for _ in $(seq 1 60); do
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

aws ssm get-command-invocation \
  --region "$AWS_REGION" \
  --command-id "$COMMAND_ID" \
  --instance-id "$EC2_INSTANCE_ID" \
  --query '{Status:Status,Output:StandardOutputContent,Error:StandardErrorContent}'

if [ "$STATUS" != 'Success' ]; then
  exit 1
fi
