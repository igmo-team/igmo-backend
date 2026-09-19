#!/usr/bin/env bash
set -Eeuo pipefail

: "${OPENAPI_SERVER_URL:?Set OPENAPI_SERVER_URL in the production environment}"
: "${WEBSOCKET_DOCS_SERVER_URL:?Set WEBSOCKET_DOCS_SERVER_URL in the production environment}"

BUILD_ENV=(
  "HOME=${HOME}"
  "PATH=${PATH}"
  "CI=${CI:-}"
  "LANG=C.UTF-8"
  "LC_ALL=C.UTF-8"
)

if [[ -n "${JAVA_HOME:-}" ]]; then
  BUILD_ENV+=("JAVA_HOME=${JAVA_HOME}")
fi

env -i "${BUILD_ENV[@]}" PUPPETEER_SKIP_DOWNLOAD=true npm ci
env -i "${BUILD_ENV[@]}" ./gradlew clean bootJar --no-daemon \
  -Popenapi.server-url="$OPENAPI_SERVER_URL" \
  -Pwebsocket-docs.server-url="$WEBSOCKET_DOCS_SERVER_URL"
