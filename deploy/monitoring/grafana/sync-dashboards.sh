#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPOSITORY_ROOT=$(cd "$SCRIPT_DIR/../../.." && pwd)
DASHBOARD_DIR=${DASHBOARD_DIR:-"$REPOSITORY_ROOT/infra/monitoring/grafana/provisioning/dashboards"}
OUTPUT_DIR=${OUTPUT_DIR:-"$REPOSITORY_ROOT/build/grafana-cloud-dashboards"}
DRY_RUN=${DRY_RUN:-false}
CURL_BIN=${CURL_BIN:-curl}

GRAFANA_CLOUD_URL=${GRAFANA_CLOUD_URL:-}
GRAFANA_CLOUD_FOLDER_UID=${GRAFANA_CLOUD_FOLDER_UID:-}
GRAFANA_CLOUD_PROMETHEUS_DATASOURCE_UID=${GRAFANA_CLOUD_PROMETHEUS_DATASOURCE_UID:-}
GRAFANA_CLOUD_LOKI_DATASOURCE_UID=${GRAFANA_CLOUD_LOKI_DATASOURCE_UID:-}
GRAFANA_CLOUD_API_TOKEN=${GRAFANA_CLOUD_API_TOKEN:-}

WORK_DIR=$(mktemp -d)
UPDATED_UIDS=()
CURRENT_UID=""

cleanup() {
  rm -rf "$WORK_DIR"
}

report_partial_sync() {
  local exit_status=$?
  if [ "$exit_status" -ne 0 ] && [ "$DRY_RUN" != true ]; then
    printf 'sync_status=FAILED dashboard=%s\n' "${CURRENT_UID:-preflight}"
    if [ "${#UPDATED_UIDS[@]}" -gt 0 ]; then
      printf 'updated_dashboards=%s\n' "$(IFS=,; echo "${UPDATED_UIDS[*]}")"
    fi
  fi
  exit "$exit_status"
}

trap cleanup EXIT
trap report_partial_sync ERR

fail() {
  echo "$*" >&2
  return 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

require_value() {
  local name=$1
  local value=$2
  [ -n "$value" ] || fail "$name is required"
}

extract_datasource_uids() {
  jq -r '
    ..
    | objects
    | select((.datasource? | type) == "object")
    | .datasource.uid? // empty
  ' "$1" | sort -u
}

validate_dashboard_file() {
  local file=$1

  jq -e '
    type == "object"
    and (.uid | type == "string" and length > 0)
    and (.title | type == "string" and length > 0)
  ' "$file" >/dev/null || fail "dashboard uid and title are required: $file"

  while IFS= read -r datasource_uid; do
    case "$datasource_uid" in
      prometheus|loki) ;;
      *) fail "unknown local datasource UID '$datasource_uid' in $file" ;;
    esac
  done < <(extract_datasource_uids "$file")
}

render_dashboard() {
  local source_file=$1
  local output_file=$2

  jq \
    --arg prometheus_uid "$GRAFANA_CLOUD_PROMETHEUS_DATASOURCE_UID" \
    --arg loki_uid "$GRAFANA_CLOUD_LOKI_DATASOURCE_UID" \
    '
      def replace_datasource_uid:
        if type == "object" and ((.datasource? | type) == "object") then
          .datasource |= (
            if .uid == "prometheus" then .uid = $prometheus_uid
            elif .uid == "loki" then .uid = $loki_uid
            else . end
          )
        else . end;
      walk(replace_datasource_uid)
    ' "$source_file" > "$output_file"

  if extract_datasource_uids "$output_file" | grep -Fx -e prometheus -e loki >/dev/null; then
    fail "local datasource UID remains after rendering: $source_file"
  fi

  while IFS= read -r datasource_uid; do
    case "$datasource_uid" in
      "$GRAFANA_CLOUD_PROMETHEUS_DATASOURCE_UID"|"$GRAFANA_CLOUD_LOKI_DATASOURCE_UID") ;;
      *) fail "unknown rendered datasource UID '$datasource_uid' in $source_file" ;;
    esac
  done < <(extract_datasource_uids "$output_file")
}

api_get() {
  local path=$1
  local output_file=$2

  "$CURL_BIN" \
    --fail-with-body \
    --silent \
    --show-error \
    --retry 3 \
    --retry-delay 1 \
    --retry-connrefused \
    --connect-timeout 10 \
    --header "Authorization: Bearer $GRAFANA_CLOUD_API_TOKEN" \
    "$GRAFANA_CLOUD_URL$path" \
    --output "$output_file"
}

api_update_dashboard() {
  local dashboard_file=$1
  local payload_file=$2
  local output_file=$3

  jq \
    --arg folder_uid "$GRAFANA_CLOUD_FOLDER_UID" \
    '{dashboard: ., folderUid: $folder_uid, overwrite: true, message: "Synced from Git"}' \
    "$dashboard_file" > "$payload_file"

  "$CURL_BIN" \
    --fail-with-body \
    --silent \
    --show-error \
    --retry 3 \
    --retry-delay 1 \
    --retry-connrefused \
    --connect-timeout 10 \
    --header "Authorization: Bearer $GRAFANA_CLOUD_API_TOKEN" \
    --header "Content-Type: application/json" \
    --request POST \
    --data-binary "@$payload_file" \
    "$GRAFANA_CLOUD_URL/api/dashboards/db" \
    --output "$output_file"
}

validate_cloud_datasource() {
  local datasource_uid=$1
  local response_file="$WORK_DIR/datasource-$datasource_uid.json"

  api_get "/api/datasources/uid/$datasource_uid" "$response_file"
  jq -e --arg uid "$datasource_uid" '.uid == $uid' "$response_file" >/dev/null \
    || fail "Grafana Cloud datasource UID mismatch: $datasource_uid"
}

validate_cloud_dashboard() {
  local dashboard_file=$1
  local response_file=$2
  local uid title

  uid=$(jq -r '.uid' "$dashboard_file")
  title=$(jq -r '.title' "$dashboard_file")
  jq -e \
    --arg uid "$uid" \
    --arg title "$title" \
    --arg folder_uid "$GRAFANA_CLOUD_FOLDER_UID" \
    '(.uid == $uid) and (.title == $title) and (.folderUid == $folder_uid) and (.version | type == "number")' \
    "$response_file" >/dev/null \
    || fail "Grafana Cloud dashboard metadata mismatch: $uid"
}

validate_dashboard_title_uid() {
  local dashboard_file=$1
  local uid title encoded_title response_file

  uid=$(jq -r '.uid' "$dashboard_file")
  title=$(jq -r '.title' "$dashboard_file")
  encoded_title=$(jq -nr --arg value "$title" '$value | @uri')
  response_file="$WORK_DIR/search-$uid.json"

  api_get "/api/search?query=$encoded_title&type=dash-db&limit=5000" "$response_file"
  jq -e \
    --arg uid "$uid" \
    --arg title "$title" \
    '[.[] | select(.type == "dash-db" and .title == $title and .uid != $uid)] | length == 0' \
    "$response_file" >/dev/null \
    || fail "Grafana Cloud dashboard title is already assigned to another UID: $title"
}

validate_update_response() {
  local uid=$1
  local response_file=$2

  jq -e --arg uid "$uid" '(.uid == $uid) and (.version | type == "number")' "$response_file" >/dev/null \
    || fail "Grafana Cloud dashboard update response mismatch: $uid"
}

validate_configuration() {
  require_command jq
  require_command shasum
  [ -d "$DASHBOARD_DIR" ] || fail "dashboard directory not found: $DASHBOARD_DIR"
  require_value GRAFANA_CLOUD_FOLDER_UID "$GRAFANA_CLOUD_FOLDER_UID"
  require_value GRAFANA_CLOUD_PROMETHEUS_DATASOURCE_UID "$GRAFANA_CLOUD_PROMETHEUS_DATASOURCE_UID"
  require_value GRAFANA_CLOUD_LOKI_DATASOURCE_UID "$GRAFANA_CLOUD_LOKI_DATASOURCE_UID"
  [ "$GRAFANA_CLOUD_PROMETHEUS_DATASOURCE_UID" != "$GRAFANA_CLOUD_LOKI_DATASOURCE_UID" ] \
    || fail "Grafana Cloud datasource UIDs must differ"

  if [ "$DRY_RUN" != true ]; then
    require_command "$CURL_BIN"
    require_value GRAFANA_CLOUD_URL "$GRAFANA_CLOUD_URL"
    require_value GRAFANA_CLOUD_API_TOKEN "$GRAFANA_CLOUD_API_TOKEN"
  elif { [ -n "$GRAFANA_CLOUD_URL" ] || [ -n "$GRAFANA_CLOUD_API_TOKEN" ]; } \
      && { [ -z "$GRAFANA_CLOUD_URL" ] || [ -z "$GRAFANA_CLOUD_API_TOKEN" ]; }; then
    fail "GRAFANA_CLOUD_URL and GRAFANA_CLOUD_API_TOKEN must be set together in dry-run"
  fi
}

main() {
  local source_file rendered_file uid source_checksums rendered_manifest_file

  validate_configuration
  mkdir -p "$OUTPUT_DIR"
  source_checksums=$(find "$DASHBOARD_DIR" -maxdepth 1 -type f -name '*.json' -print0 | sort -z | xargs -0 shasum -a 256)
  [ -n "$source_checksums" ] || fail "no dashboard JSON files found: $DASHBOARD_DIR"
  rendered_manifest_file="$WORK_DIR/rendered-files"

  while IFS= read -r -d '' source_file; do
    validate_dashboard_file "$source_file"
    rendered_file="$OUTPUT_DIR/$(basename "$source_file")"
    render_dashboard "$source_file" "$rendered_file"
    uid=$(jq -r '.uid' "$rendered_file")
    printf '%s\t%s\n' "$uid" "$rendered_file" >> "$rendered_manifest_file"
  done < <(find "$DASHBOARD_DIR" -maxdepth 1 -type f -name '*.json' -print0 | sort -z)

  cut -f1 "$rendered_manifest_file" | sort | uniq -d | while IFS= read -r uid; do
    fail "duplicate dashboard UID: $uid"
  done

  [ "$source_checksums" = "$(find "$DASHBOARD_DIR" -maxdepth 1 -type f -name '*.json' -print0 | sort -z | xargs -0 shasum -a 256)" ] \
    || fail "source dashboard JSON changed during rendering"

  if [ "$DRY_RUN" = true ] && [ -z "$GRAFANA_CLOUD_API_TOKEN" ]; then
    while IFS=$'\t' read -r uid rendered_file; do
      printf 'dashboard=%s action=UPDATE folderUid=%s datasources=%s cloud_lookup=SKIPPED\n' \
        "$uid" "$GRAFANA_CLOUD_FOLDER_UID" "$(extract_datasource_uids "$rendered_file" | paste -sd, -)"
    done < "$rendered_manifest_file"
    return
  fi

  require_command "$CURL_BIN"
  require_value GRAFANA_CLOUD_URL "$GRAFANA_CLOUD_URL"
  require_value GRAFANA_CLOUD_API_TOKEN "$GRAFANA_CLOUD_API_TOKEN"
  validate_cloud_datasource "$GRAFANA_CLOUD_PROMETHEUS_DATASOURCE_UID"
  validate_cloud_datasource "$GRAFANA_CLOUD_LOKI_DATASOURCE_UID"

  while IFS=$'\t' read -r uid rendered_file; do
    CURRENT_UID=$uid
    api_get "/api/dashboards/uid/$uid" "$WORK_DIR/before-$uid.json"
    validate_cloud_dashboard "$rendered_file" "$WORK_DIR/before-$uid.json"
    validate_dashboard_title_uid "$rendered_file"
  done < "$rendered_manifest_file"

  while IFS=$'\t' read -r uid rendered_file; do
    CURRENT_UID=$uid
    if [ "$DRY_RUN" = true ]; then
      printf 'dashboard=%s action=UPDATE folderUid=%s datasources=%s cloud_lookup=OK\n' \
        "$uid" "$GRAFANA_CLOUD_FOLDER_UID" "$(extract_datasource_uids "$rendered_file" | paste -sd, -)"
      continue
    fi

    api_update_dashboard "$rendered_file" "$WORK_DIR/payload-$uid.json" "$WORK_DIR/update-$uid.json"
    validate_update_response "$uid" "$WORK_DIR/update-$uid.json"
    api_get "/api/dashboards/uid/$uid" "$WORK_DIR/after-$uid.json"
    validate_cloud_dashboard "$rendered_file" "$WORK_DIR/after-$uid.json"
    UPDATED_UIDS+=("$uid")
    printf 'dashboard=%s action=UPDATED folderUid=%s\n' "$uid" "$GRAFANA_CLOUD_FOLDER_UID"
  done < "$rendered_manifest_file"
}

main "$@"
