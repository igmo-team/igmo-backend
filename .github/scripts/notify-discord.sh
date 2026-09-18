#!/usr/bin/env bash
set -Eeuo pipefail

EVENT="${1:?Usage: notify-discord.sh <start|success|failure>}"

if [[ -z "${DISCORD_DEPLOY_WEBHOOK_URL:-}" ]]; then
  echo "::warning::DISCORD_DEPLOY_WEBHOOK_URL is not configured; skipping Discord notification."
  exit 0
fi

SHORT_SHA="${GITHUB_SHA:0:7}"
WORKFLOW_URL="${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"

case "$EVENT" in
  start)
    CONTENT=$(cat <<EOF
🚀 IGMO 배포 시작

Production 배포를 시작합니다.

환경: production
Commit: ${SHORT_SHA}
배포자: ${GITHUB_ACTOR}

GitHub Actions: ${WORKFLOW_URL}
EOF
)
    ;;
  success)
    CONTENT=$(cat <<EOF
✅ IGMO 배포 성공

Production 배포가 정상적으로 완료되었습니다.

환경: production
Commit: ${SHORT_SHA}
배포자: ${GITHUB_ACTOR}
Health Check: 정상
GitHub Actions: ${WORKFLOW_URL}
EOF
)
    ;;
  failure)
    FAILURE_STAGE="Unknown CD failure"
    FAILURE_REASON="실패 단계를 특정하지 못했습니다. GitHub Actions 로그를 확인해주세요."
    RECOVERY_LINE=""

    if [[ "${VALIDATE_OUTCOME:-}" == "failure" ]]; then
      FAILURE_STAGE="Deployment configuration validation"
      FAILURE_REASON="production 배포 설정 검증에 실패했습니다."
    elif [[ "${CHECKOUT_OUTCOME:-}" == "failure" ]]; then
      FAILURE_STAGE="Checkout"
      FAILURE_REASON="배포 대상 commit checkout에 실패했습니다."
    elif [[ "${SETUP_JAVA_OUTCOME:-}" == "failure" ]]; then
      FAILURE_STAGE="JDK setup"
      FAILURE_REASON="JDK 설정에 실패했습니다."
    elif [[ "${SETUP_NODE_OUTCOME:-}" == "failure" ]]; then
      FAILURE_STAGE="Node.js setup"
      FAILURE_REASON="Node.js 설정에 실패했습니다."
    elif [[ "${BUILD_ARTIFACT_OUTCOME:-}" == "failure" ]]; then
      FAILURE_STAGE="Gradle build / document generation"
      FAILURE_REASON="테스트·문서 생성·JAR 빌드에 실패했습니다."
    elif [[ "${AWS_AUTH_OUTCOME:-}" == "failure" ]]; then
      FAILURE_STAGE="AWS authentication"
      FAILURE_REASON="AWS OIDC 인증에 실패했습니다."
    elif [[ "${ECR_LOGIN_OUTCOME:-}" == "failure" ]]; then
      FAILURE_STAGE="ECR login"
      FAILURE_REASON="Amazon ECR 로그인에 실패했습니다."
    elif [[ "${VERIFY_IMAGE_OUTCOME:-}" == "failure" ]]; then
      FAILURE_STAGE="ECR image verification"
      FAILURE_REASON="deploy-only 대상 이미지 검증에 실패했습니다."
    elif [[ "${QEMU_OUTCOME:-}" == "failure" || "${BUILDX_OUTCOME:-}" == "failure" || "${BUILD_PUSH_OUTCOME:-}" == "failure" ]]; then
      FAILURE_STAGE="Docker Build / Push"
      FAILURE_REASON="Docker 이미지 Build / Push에 실패했습니다."
    elif [[ "${DEPLOY_OUTCOME:-}" == "failure" ]]; then
      FAILURE_STAGE="${DEPLOY_FAILURE_STAGE:-AWS SSM Deployment}"
      FAILURE_REASON="${DEPLOY_FAILURE_REASON:-SSM 배포 명령이 성공 상태로 완료되지 않았습니다. 상세 원인은 GitHub Actions 로그를 확인해주세요.}"
    fi

    if [[ "${DEPLOY_CLEANUP_STATUS:-}" == "success" ]]; then
      RECOVERY_LINE="Target 컨테이너 정리: 성공"
    elif [[ "${DEPLOY_CLEANUP_STATUS:-}" == "failed" ]]; then
      RECOVERY_LINE="Target 컨테이너 정리: 실패"
    fi

    if [[ "${DEPLOY_NGINX_RECOVERY_STATUS:-}" == "failed" ]]; then
      RECOVERY_LINE="${RECOVERY_LINE}
Nginx 설정 복구: 실패"
    fi

    CONTENT=$(cat <<EOF
🚨 IGMO 배포 실패

Production 배포 과정에서 문제가 발생했습니다.

환경: production
Commit: ${SHORT_SHA}
배포자: ${GITHUB_ACTOR}
실패 단계: ${FAILURE_STAGE}
원인: ${FAILURE_REASON}
${RECOVERY_LINE}

GitHub Actions: ${WORKFLOW_URL}
EOF
)
    ;;
  *)
    echo "Usage: notify-discord.sh <start|success|failure>" >&2
    exit 2
    ;;
esac

PAYLOAD=$(jq -n --arg content "$CONTENT" '{content: $content}')
HTTP_STATUS="000"
if ! HTTP_STATUS=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --max-time 15 \
  --header 'Content-Type: application/json' \
  --data "$PAYLOAD" \
  "$DISCORD_DEPLOY_WEBHOOK_URL" 2>/dev/null); then
  HTTP_STATUS="000"
fi

if [[ "$HTTP_STATUS" != 2* ]]; then
  echo "::warning::Discord deployment ${EVENT} notification failed (HTTP ${HTTP_STATUS})."
fi
