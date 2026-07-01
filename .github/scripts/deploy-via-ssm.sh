#!/usr/bin/env bash
set -Eeuo pipefail

CONTAINER_NAME="igmo-backend"

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
CONTAINER_NAME='${CONTAINER_NAME}'
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
  docker run --detach \
    --name "\$CONTAINER_NAME" \
    --restart always \
    --stop-timeout 20 \
    --memory 1280m \
    --env SPRING_PROFILES_ACTIVE=prod \
    --env 'JAVA_TOOL_OPTIONS=-Xms128m -Xmx768m' \
    --publish 80:8080 \
    --log-opt max-size=10m \
    --log-opt max-file=3 \
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
  HTTP_STATUS=\$(curl --silent --output /dev/null --write-out '%{http_code}' http://127.0.0.1/ || true)
  case "\$HTTP_STATUS" in
    2??|3??|4??)
      HEALTHY='true'
      break
      ;;
  esac
  ATTEMPT=\$((ATTEMPT + 1))
  sleep 2
done

if [ "\$HEALTHY" != 'true' ]; then
  echo 'The new container did not become healthy.' >&2
  docker logs --tail 200 "\$CONTAINER_NAME" || true
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
