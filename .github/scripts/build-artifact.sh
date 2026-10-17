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

DIAGNOSTIC_DIRECTORY="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/igmo-cd/raw"

run_logged() {
  local stage="$1"
  shift
  local command_status
  local tee_status
  local statuses

  if ! mkdir -p "$DIAGNOSTIC_DIRECTORY"; then
    echo '::warning::Cannot create CD diagnostic directory; running without file logging.'
    "$@"
    return
  fi

  set +e
  "$@" 2>&1 | tee "$DIAGNOSTIC_DIRECTORY/$stage.log"
  statuses=("${PIPESTATUS[@]}")
  set -e
  command_status="${statuses[0]}"
  tee_status="${statuses[1]}"
  if [[ "$tee_status" != 0 ]]; then
    echo '::warning::CD log recording failed; preserving the build command result.'
  fi
  printf '{"stage":"%s","exit_code":%s}\n' "$stage" "$command_status" \
    > "$DIAGNOSTIC_DIRECTORY/result.json" || echo '::warning::Cannot record build exit code.'
  return "$command_status"
}

run_logged npm env -i "${BUILD_ENV[@]}" PUPPETEER_SKIP_DOWNLOAD=true npm ci
run_logged gradle env -i "${BUILD_ENV[@]}" ./gradlew clean bootJar --no-daemon \
  -Popenapi.server-url="$OPENAPI_SERVER_URL" \
  -Pwebsocket-docs.server-url="$WEBSOCKET_DOCS_SERVER_URL"
