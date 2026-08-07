import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
SYNC_SCRIPT = REPOSITORY_ROOT / "deploy/monitoring/grafana/sync-dashboards.sh"


class GrafanaDashboardSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp_directory = tempfile.TemporaryDirectory()
        self.temp_path = Path(self.temp_directory.name)
        self.dashboard_directory = self.temp_path / "dashboards"
        self.output_directory = self.temp_path / "rendered"
        self.dashboard_directory.mkdir()

    def tearDown(self):
        self.temp_directory.cleanup()

    def write_dashboard(self, uid="dashboard-one", title="Dashboard One", datasource_uid="prometheus"):
        dashboard = {
            "uid": uid,
            "title": title,
            "panels": [{"datasource": {"type": "prometheus", "uid": datasource_uid}}],
            "templating": {"list": [{"datasource": {"type": "loki", "uid": "loki"}}]},
        }
        (self.dashboard_directory / f"{uid}.json").write_text(json.dumps(dashboard))

    def run_sync(self, **overrides):
        environment = {
            **os.environ,
            "DASHBOARD_DIR": str(self.dashboard_directory),
            "OUTPUT_DIR": str(self.output_directory),
            "GRAFANA_CLOUD_FOLDER_UID": "igmo-folder",
            "GRAFANA_CLOUD_PROMETHEUS_DATASOURCE_UID": "cloud-prometheus",
            "GRAFANA_CLOUD_LOKI_DATASOURCE_UID": "cloud-loki",
            **overrides,
        }
        return subprocess.run(
            [str(SYNC_SCRIPT)],
            cwd=REPOSITORY_ROOT,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_dry_run_renders_cloud_datasources_without_changing_source(self):
        self.write_dashboard()
        source_file = self.dashboard_directory / "dashboard-one.json"
        original = source_file.read_text()

        result = self.run_sync(DRY_RUN="true")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("dashboard=dashboard-one action=UPDATE", result.stdout)
        self.assertEqual(original, source_file.read_text())
        rendered = json.loads((self.output_directory / "dashboard-one.json").read_text())
        self.assertEqual("cloud-prometheus", rendered["panels"][0]["datasource"]["uid"])
        self.assertEqual("cloud-loki", rendered["templating"]["list"][0]["datasource"]["uid"])

    def test_missing_dashboard_uid_fails(self):
        self.write_dashboard()
        dashboard_file = self.dashboard_directory / "dashboard-one.json"
        dashboard = json.loads(dashboard_file.read_text())
        del dashboard["uid"]
        dashboard_file.write_text(json.dumps(dashboard))

        result = self.run_sync(DRY_RUN="true")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("dashboard uid and title are required", result.stderr)

    def test_duplicate_dashboard_uid_fails(self):
        self.write_dashboard()
        self.write_dashboard(uid="dashboard-two")
        second_file = self.dashboard_directory / "dashboard-two.json"
        second = json.loads(second_file.read_text())
        second["uid"] = "dashboard-one"
        second_file.write_text(json.dumps(second))

        result = self.run_sync(DRY_RUN="true")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("duplicate dashboard UID: dashboard-one", result.stderr)

    def test_unknown_datasource_uid_fails(self):
        self.write_dashboard(datasource_uid="unknown-datasource")

        result = self.run_sync(DRY_RUN="true")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("unknown local datasource UID", result.stderr)

    def test_rendering_fails_when_cloud_uid_matches_local_uid(self):
        self.write_dashboard()

        result = self.run_sync(
            DRY_RUN="true",
            GRAFANA_CLOUD_PROMETHEUS_DATASOURCE_UID="prometheus",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("local datasource UID remains after rendering", result.stderr)

    def test_dry_run_does_not_write_to_cloud_or_expose_token(self):
        self.write_dashboard()
        fake_curl = self.write_fake_curl("valid")

        result = self.run_sync(
            DRY_RUN="true",
            CURL_BIN=str(fake_curl),
            GRAFANA_CLOUD_URL="https://grafana.example.com",
            GRAFANA_CLOUD_API_TOKEN="secret-token",
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("secret-token", result.stdout + result.stderr)
        self.assertNotIn("POST", (self.temp_path / "curl.log").read_text())

    def test_cloud_dashboard_not_found_fails_before_update(self):
        self.write_dashboard()
        fake_curl = self.write_fake_curl("dashboard-not-found")

        result = self.run_sync(
            CURL_BIN=str(fake_curl),
            GRAFANA_CLOUD_URL="https://grafana.example.com",
            GRAFANA_CLOUD_API_TOKEN="secret-token",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("POST", (self.temp_path / "curl.log").read_text())
        self.assertNotIn("secret-token", result.stdout + result.stderr)

    def test_cloud_dashboard_metadata_mismatch_fails_before_update(self):
        self.write_dashboard()
        fake_curl = self.write_fake_curl("dashboard-mismatch")

        result = self.run_sync(
            CURL_BIN=str(fake_curl),
            GRAFANA_CLOUD_URL="https://grafana.example.com",
            GRAFANA_CLOUD_API_TOKEN="secret-token",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("metadata mismatch", result.stderr)
        self.assertNotIn("POST", (self.temp_path / "curl.log").read_text())

    def test_cloud_dashboard_same_title_with_another_uid_fails_before_update(self):
        self.write_dashboard()
        fake_curl = self.write_fake_curl("same-title-different-uid")

        result = self.run_sync(
            CURL_BIN=str(fake_curl),
            GRAFANA_CLOUD_URL="https://grafana.example.com",
            GRAFANA_CLOUD_API_TOKEN="secret-token",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("title is already assigned to another UID", result.stderr)
        self.assertNotIn("POST", (self.temp_path / "curl.log").read_text())

    def test_cloud_api_401_or_403_fails_before_update(self):
        self.write_dashboard()

        for status in ("api-401", "api-403"):
            with self.subTest(status=status):
                fake_curl = self.write_fake_curl(status)
                result = self.run_sync(
                    CURL_BIN=str(fake_curl),
                    GRAFANA_CLOUD_URL="https://grafana.example.com",
                    GRAFANA_CLOUD_API_TOKEN="secret-token",
                )

                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("POST", (self.temp_path / "curl.log").read_text())

    def test_sync_updates_existing_dashboard_after_preflight(self):
        self.write_dashboard()
        fake_curl = self.write_fake_curl("valid")

        result = self.run_sync(
            CURL_BIN=str(fake_curl),
            GRAFANA_CLOUD_URL="https://grafana.example.com",
            GRAFANA_CLOUD_API_TOKEN="secret-token",
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("dashboard=dashboard-one action=UPDATED", result.stdout)
        self.assertIn("POST https://grafana.example.com/api/dashboards/db", (self.temp_path / "curl.log").read_text())

    def test_partial_sync_fails_without_rollback(self):
        self.write_dashboard()
        self.write_dashboard(uid="dashboard-two", title="Dashboard Two")
        fake_curl = self.write_fake_curl("partial")

        result = self.run_sync(
            CURL_BIN=str(fake_curl),
            GRAFANA_CLOUD_URL="https://grafana.example.com",
            GRAFANA_CLOUD_API_TOKEN="secret-token",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("sync_status=FAILED dashboard=dashboard-two", result.stdout)
        self.assertIn("updated_dashboards=dashboard-one", result.stdout)
        log = (self.temp_path / "curl.log").read_text()
        self.assertEqual(2, log.count("POST https://grafana.example.com/api/dashboards/db"))
        self.assertNotIn("DELETE", log)

    def test_sync_requests_limited_retry_for_transient_failures(self):
        script = SYNC_SCRIPT.read_text()

        self.assertIn("--retry 3", script)
        self.assertIn("--retry-connrefused", script)
        self.assertNotIn("--retry-all-errors", script)

    def write_fake_curl(self, mode):
        script = self.temp_path / "fake-curl.sh"
        log_file = self.temp_path / "curl.log"
        script.write_text(
            "#!/usr/bin/env bash\n"
            "set -eu\n"
            f"MODE={mode!r}\n"
            f"LOG_FILE={str(log_file)!r}\n"
            "output=''\nmethod=GET\nurl=''\n"
            "while [ $# -gt 0 ]; do\n"
            "  case \"$1\" in\n"
            "    --output) output=$2; shift 2 ;;\n"
            "    --request) method=$2; shift 2 ;;\n"
            "    http*) url=$1; shift ;;\n"
            "    *) shift ;;\n"
            "  esac\n"
            "done\n"
            "printf '%s %s\\n' \"$method\" \"$url\" >> \"$LOG_FILE\"\n"
            "case \"$MODE\" in\n"
            "  api-401) echo 'HTTP 401' >&2; exit 22 ;;\n"
            "  api-403) echo 'HTTP 403' >&2; exit 22 ;;\n"
            "esac\n"
            "case \"$url\" in\n"
            "  */api/datasources/uid/cloud-prometheus) printf '%s' '{\"uid\":\"cloud-prometheus\"}' > \"$output\" ;;\n"
            "  */api/datasources/uid/cloud-loki) printf '%s' '{\"uid\":\"cloud-loki\"}' > \"$output\" ;;\n"
            "  */api/dashboards/db)\n"
            "    case \"$MODE\" in\n"
            "      valid) printf '%s' '{\"uid\":\"dashboard-one\",\"version\":2}' > \"$output\" ;;\n"
            "      partial)\n"
            "        post_count_file=\"${LOG_FILE}.post-count\"\n"
            "        post_count=$(cat \"$post_count_file\" 2>/dev/null || echo 0)\n"
            "        post_count=$((post_count + 1))\n"
            "        printf '%s' \"$post_count\" > \"$post_count_file\"\n"
            "        if [ \"$post_count\" -eq 1 ]; then printf '%s' '{\"uid\":\"dashboard-one\",\"version\":2}' > \"$output\"; else exit 22; fi ;;\n"
            "      *) echo 'unexpected dashboard update' >&2; exit 22 ;;\n"
            "    esac ;;\n"
            "  */api/dashboards/uid/*)\n"
            "    case \"$MODE\" in\n"
            "      dashboard-not-found) exit 22 ;;\n"
            "      dashboard-mismatch) printf '%s' '{\"dashboard\":{\"uid\":\"dashboard-one\",\"title\":\"Wrong\",\"version\":1},\"meta\":{\"folderUid\":\"igmo-folder\"}}' > \"$output\" ;;\n"
            "      valid|partial|same-title-different-uid)\n"
            "        dashboard_uid=${url##*/}\n"
            "        title=\"Dashboard One\"\n"
            "        if [ \"$dashboard_uid\" = dashboard-two ]; then title=\"Dashboard Two\"; fi\n"
            "        printf '{\"dashboard\":{\"uid\":\"%s\",\"title\":\"%s\",\"version\":2},\"meta\":{\"folderUid\":\"igmo-folder\"}}' \"$dashboard_uid\" \"$title\" > \"$output\" ;;\n"
            "      *) echo 'unexpected dashboard lookup' >&2; exit 22 ;;\n"
            "    esac ;;\n"
            "  */api/search?*)\n"
            "    case \"$MODE\" in\n"
            "      same-title-different-uid) printf '%s' '[{\"uid\":\"other-dashboard\",\"title\":\"Dashboard One\",\"type\":\"dash-db\",\"folderUid\":\"igmo-folder\"}]' > \"$output\" ;;\n"
            "      valid|partial) printf '%s' '[]' > \"$output\" ;;\n"
            "      *) echo 'unexpected dashboard search' >&2; exit 22 ;;\n"
            "    esac ;;\n"
            "  *) echo 'unexpected curl call' >&2; exit 22 ;;\n"
            "esac\n"
        )
        script.chmod(0o755)
        return script


if __name__ == "__main__":
    unittest.main()
