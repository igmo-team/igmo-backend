import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
LOCAL_DATASOURCE_FILE = REPOSITORY_ROOT / "infra/monitoring/grafana/datasources.local.yml"
LOCAL_COMPOSE_FILE = REPOSITORY_ROOT / "infra/monitoring/docker-compose.local.yml"
PRODUCTION_COMPOSE_FILE = REPOSITORY_ROOT / "infra/monitoring/docker-compose.yml"
PRODUCTION_ALLOY_FILE = REPOSITORY_ROOT / "infra/monitoring/alloy/config.alloy"
SPRING_DASHBOARD_FILE = REPOSITORY_ROOT / "infra/monitoring/grafana/provisioning/dashboards/spring-application.json"
MONITORING_DEPLOY_SCRIPT = REPOSITORY_ROOT / "deploy/monitoring/apply.sh"
MONITORING_DEPLOY_WORKFLOW = REPOSITORY_ROOT / ".github/workflows/deploy-monitoring.yml"
MONITORING_NGINX_CONFIG = REPOSITORY_ROOT / "deploy/nginx/monitoring.conf"


class MonitoringDeploymentTest(unittest.TestCase):
    def test_local_compose_mounts_local_datasource(self):
        self.assertTrue(LOCAL_DATASOURCE_FILE.is_file())
        self.assertIn(
            "./grafana/datasources.local.yml:/etc/grafana/provisioning/datasources/datasources.yml:ro",
            LOCAL_COMPOSE_FILE.read_text(),
        )

    def test_local_app_compose_passes_deployment_slot_and_port(self):
        compose_file = (REPOSITORY_ROOT / "docker-compose.local.yml").read_text()

        self.assertIn("IGMO_DEPLOYMENT_SLOT: ${IGMO_DEPLOYMENT_SLOT:-blue}", compose_file)
        self.assertIn("IGMO_DEPLOYMENT_PORT: ${IGMO_DEPLOYMENT_PORT:-8080}", compose_file)

    def test_production_compose_defines_blue_green_services(self):
        compose_file = (REPOSITORY_ROOT / "docker-compose.prod.yml").read_text()

        self.assertIn("app-blue:", compose_file)
        self.assertIn("app-green:", compose_file)
        self.assertIn('container_name: igmo-backend-blue', compose_file)
        self.assertIn('container_name: igmo-backend-green', compose_file)
        self.assertIn('127.0.0.1:8080:8080', compose_file)
        self.assertIn('127.0.0.1:8081:8080', compose_file)
        self.assertIn('env_file:\n    - /run/igmo/prod.env', compose_file)
        self.assertIn('stop_grace_period: ${STOP_GRACE_PERIOD:-30s}', compose_file)
        self.assertIn('mem_limit: 1280m', compose_file)

        deploy_script = (REPOSITORY_ROOT / ".github/scripts/deploy-via-ssm.sh").read_text()
        self.assertIn('PRODUCTION_COMPOSE_FILE="docker-compose.prod.yml"', deploy_script)
        self.assertIn("PRODUCTION_COMPOSE_FILE_B64=$(base64", deploy_script)
        self.assertIn('mkdir -p /run/igmo', deploy_script)
        self.assertIn("RUNTIME_ENV_FILE='/run/igmo/prod.env'", deploy_script)
        self.assertIn('chmod 600 /run/igmo/prod.env', deploy_script)
        self.assertIn('export IMAGE_URI STOP_GRACE_PERIOD', deploy_script)
        self.assertNotIn('cat /run/igmo/prod.env', deploy_script)
        self.assertIn('dotenv_quote()', deploy_script)
        self.assertIn('dotenv_line IGMO_ADMIN_IMAGE_GENERATION_PASSWORD', deploy_script)
        self.assertIn('printf \'%s\' \'${PRODUCTION_COMPOSE_FILE_B64}\' | base64 -d', deploy_script)
        self.assertIn('docker compose --project-name "\\$COMPOSE_PROJECT_NAME" --file "\\$COMPOSE_FILE" config -q', deploy_script)

    def test_deploy_script_preserves_special_characters_in_runtime_env(self):
        if shutil.which("docker") is None:
            self.skipTest("Docker Compose integration dependencies are unavailable")
        if subprocess.run(
            ["docker", "info"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        ).returncode != 0:
            self.skipTest("Docker daemon is unavailable")

        password = 'abc$TOKEN # "double" \'single\' \\ backslash\nsecond'
        deployment_environment = os.environ.copy()
        deployment_environment.update(
            {
                "AWS_REGION": "ap-northeast-2",
                "EC2_INSTANCE_ID": "i-0123456789abcdef0",
                "IMAGE_URI": "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/igmo:abc123",
                "GEMINI_API_KEY": "gemini-key",
                "IGMO_AI_GEMINI_MODEL": "gemini-2.5-flash",
                "IGMO_AI_GEMINI_IMAGE_SIZE": "1K",
                "IGMO_GAME_DISCONNECT_GRACE": "30s",
                "IGMO_GAME_PROMPT_DURATION": "60s",
                "IGMO_GAME_GUESS_DURATION": "60s",
                "IGMO_GAME_VOTE_DURATION": "60s",
                "IGMO_GAME_RESULT_DURATION": "60s",
                "IGMO_GAME_IMAGE_GENERATION_COMPLETION_DELAY": "5s",
                "IGMO_GAME_DRAIN_TIMEOUT": "300s",
                "IGMO_IMAGE_STORAGE_S3_BUCKET": "igmo-images",
                "IGMO_IMAGE_STORAGE_S3_REGION": "ap-northeast-2",
                "IGMO_IMAGE_STORAGE_S3_KEY_PREFIX": "images/",
                "IGMO_ADMIN_IMAGE_STORAGE_S3_BUCKET": "igmo-admin-images",
                "IGMO_ADMIN_IMAGE_STORAGE_S3_KEY_PREFIX": "admin/",
                "IGMO_ADMIN_IMAGE_GENERATION_USERNAME": "admin",
                "IGMO_ADMIN_IMAGE_GENERATION_PASSWORD": password,
            }
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            deploy_script = (REPOSITORY_ROOT / ".github/scripts/deploy-via-ssm.sh").read_text()
            functions_start = deploy_script.index("dotenv_quote()")
            functions_end = deploy_script.index("\nRUNTIME_ENV_FILE_B64=", functions_start)
            runtime_functions = deploy_script[functions_start:functions_end]
            runtime_environment = subprocess.run(
                ["bash", "-c", f"{runtime_functions}\nruntime_environment"],
                env=deployment_environment,
                check=True,
                capture_output=True,
                timeout=10,
            )
            env_file = temporary_path / "prod.env"
            env_file.write_bytes(runtime_environment.stdout)
            compose_file = temporary_path / "compose.yml"
            compose_file.write_text(
                "services:\n"
                "  app:\n"
                "    image: eclipse-temurin:25-jre-alpine\n"
                "    entrypoint: [\"/bin/sh\"]\n"
                "    env_file:\n"
                f"      - \"{env_file}\"\n",
                encoding="utf-8",
            )

            try:
                result = subprocess.run(
                    [
                        "docker",
                        "compose",
                        "-f",
                        str(compose_file),
                        "run",
                        "--rm",
                        "--no-deps",
                        "app",
                        "-c",
                        "printf %s \"$IGMO_ADMIN_IMAGE_GENERATION_PASSWORD\" | base64",
                    ],
                    env={**os.environ, "COMPOSE_DISABLE_ENV_FILE": "1"},
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=30,
                )
            finally:
                subprocess.run(
                    [
                        "docker",
                        "compose",
                        "-f",
                        str(compose_file),
                        "down",
                        "--volumes",
                        "--remove-orphans",
                    ],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    check=False,
                    timeout=30,
                )

            self.assertEqual(password, base64.b64decode(result.stdout).decode())

    def test_production_alloy_sends_metrics_and_logs_with_a_secret_file(self):
        alloy_configuration = PRODUCTION_ALLOY_FILE.read_text()
        production_compose = PRODUCTION_COMPOSE_FILE.read_text()

        self.assertIn(
            'prometheus.remote_write "grafana_cloud"',
            alloy_configuration,
        )
        self.assertIn(
            'prometheus.scrape "igmo_app"',
            alloy_configuration,
        )
        self.assertIn(
            'prometheus.scrape "ec2_host"',
            alloy_configuration,
        )
        self.assertIn('job_name       = "igmo-app"', alloy_configuration)
        self.assertIn('job_name        = "ec2-host"', alloy_configuration)
        self.assertIn('loki.write "grafana_cloud"', alloy_configuration)
        self.assertNotIn('loki.write "local"', alloy_configuration)
        self.assertNotIn("prometheus:\n", production_compose)
        self.assertNotIn("grafana:\n", production_compose)
        self.assertNotIn("loki:\n", production_compose)
        self.assertIn("mem_limit: 256m", production_compose)
        self.assertIn("cadvisor:", production_compose)
        self.assertIn("ghcr.io/google/cadvisor:0.55.1", production_compose)
        self.assertIn("--docker_only=true", production_compose)
        self.assertIn("--port=18081", production_compose)
        self.assertIn("mem_limit: 128m", production_compose)
        self.assertIn(
            "/opt/igmo/monitoring-secrets/grafana-cloud-ingest-token:/run/secrets/grafana-cloud-ingest-token:ro",
            production_compose,
        )
        self.assertIn(
            'GRAFANA_CLOUD_INGEST_TOKEN="${GRAFANA_CLOUD_INGEST_TOKEN:?GRAFANA_CLOUD_INGEST_TOKEN is required}"',
            MONITORING_DEPLOY_SCRIPT.read_text(),
        )
        self.assertIn(
            "GRAFANA_CLOUD_INGEST_TOKEN: ${{ secrets.GRAFANA_CLOUD_INGEST_TOKEN }}",
            MONITORING_DEPLOY_WORKFLOW.read_text(),
        )
        self.assertNotIn("GRAFANA_ADMIN_PASSWORD", MONITORING_DEPLOY_SCRIPT.read_text())
        self.assertNotIn("GRAFANA_ADMIN_PASSWORD", MONITORING_DEPLOY_WORKFLOW.read_text())
        self.assertIn("for SERVICE in alloy node-exporter cadvisor; do", MONITORING_DEPLOY_SCRIPT.read_text())
        self.assertIn("127.0.0.1:18081/metrics", MONITORING_DEPLOY_SCRIPT.read_text())

    def test_production_alloy_tracks_active_backend_and_blue_green_logs(self):
        alloy_configuration = PRODUCTION_ALLOY_FILE.read_text()
        nginx_configuration = (REPOSITORY_ROOT / "deploy/nginx/igmo.conf").read_text()

        self.assertIn('"__address__" = "127.0.0.1:18080"', alloy_configuration)
        self.assertIn('metrics_path   = "/metrics"', alloy_configuration)
        self.assertIn('regex         = "/igmo-backend(-blue|-green)?$"', alloy_configuration)
        self.assertIn("listen 127.0.0.1:18080;", nginx_configuration)
        self.assertIn("proxy_pass http://igmo_backend/actuator/prometheus;", nginx_configuration)

    def test_monitoring_workflow_uses_monitoring_stack_input(self):
        workflow = MONITORING_DEPLOY_WORKFLOW.read_text()

        self.assertIn("deploy_monitoring_stack:", workflow)
        self.assertIn('description: "모니터링 스택 배포"', workflow)
        self.assertIn("inputs.deploy_monitoring_stack", workflow)
        self.assertIn("deploy-monitoring-stack:", workflow)
        self.assertNotIn("deploy_alloy", workflow)
        self.assertNotIn("deploy-alloy:", workflow)

    def test_instance_dashboard_shows_container_memory_usage_and_limits(self):
        dashboard = json.loads((REPOSITORY_ROOT / "infra/monitoring/grafana/provisioning/dashboards/instance.json").read_text())
        panels_by_title = {panel["title"]: panel for panel in dashboard["panels"]}

        memory_table = panels_by_title["컨테이너 메모리 현황"]
        memory_timeseries = panels_by_title["컨테이너 메모리 추이"]
        memory_ratio = panels_by_title["메모리 리밋 대비 사용률"]

        table_expressions = [target["expr"] for target in memory_table["targets"]]
        self.assertTrue(any("container_memory_working_set_bytes" in expr for expr in table_expressions))
        self.assertTrue(any("container_spec_memory_limit_bytes" in expr for expr in table_expressions))
        self.assertIn("container_memory_working_set_bytes", memory_timeseries["targets"][0]["expr"])
        self.assertIn("container_spec_memory_limit_bytes", memory_timeseries["targets"][1]["expr"])
        self.assertIn("container_memory_working_set_bytes", memory_ratio["targets"][0]["expr"])
        self.assertIn("container_spec_memory_limit_bytes", memory_ratio["targets"][0]["expr"])

    def test_spring_dashboard_shows_health_and_active_slot_port(self):
        dashboard = json.loads(SPRING_DASHBOARD_FILE.read_text())
        panel = dashboard["panels"][0]
        active_target = panel["targets"][0]
        local_target = panel["targets"][1]

        self.assertEqual("앱 인스턴스 상태·활성 슬롯", panel["title"])
        self.assertIn(
            'up{job="igmo-app", environment="production"} '
            '* on (instance, job) group_left(slot, port) '
            'igmo_deployment_slot_active{job="igmo-app", environment="production"}',
            active_target["expr"],
        )
        self.assertIn('up{job="igmo-app", environment="production"} == 0', active_target["expr"])
        self.assertIn("or on (instance, job, slot, port)", active_target["expr"])
        self.assertIn("topk by (instance, job)", active_target["expr"])
        self.assertIn("timestamp(last_over_time", active_target["expr"])
        self.assertIn("> bool 0", active_target["expr"])
        self.assertTrue(active_target["instant"])
        self.assertEqual("{{slot}} :{{port}}", active_target["legendFormat"])
        self.assertEqual('up{job="igmo-app-slot-health", environment="local"}', local_target["expr"])
        self.assertNotIn("instant", local_target)
        self.assertEqual("{{slot}} :{{port}}", local_target["legendFormat"])
        self.assertEqual(["lastNotNull"], panel["options"]["reduceOptions"]["calcs"])
        self.assertEqual("value_and_name", panel["options"]["textMode"])
        self.assertTrue(panel["options"]["wideLayout"])
        self.assertEqual("horizontal", panel["options"]["orientation"])
        self.assertEqual("center", panel["options"]["justifyMode"])

    def test_local_prometheus_scrapes_both_deployment_slots(self):
        prometheus_config = (REPOSITORY_ROOT / "infra/monitoring/prometheus/prometheus.local.yml").read_text()

        self.assertIn("job_name: igmo-app-slot-health", prometheus_config)
        self.assertIn("host.docker.internal:8080", prometheus_config)
        self.assertIn("host.docker.internal:8081", prometheus_config)
        self.assertIn("slot: blue", prometheus_config)
        self.assertIn("slot: green", prometheus_config)

    def test_application_deployment_script_passes_slot_and_host_port(self):
        deploy_script = (REPOSITORY_ROOT / ".github/scripts/deploy-via-ssm.sh").read_text()

        self.assertIn("TARGET_SERVICE='app-green'", deploy_script)
        self.assertIn("TARGET_SERVICE='app-blue'", deploy_script)
        self.assertIn("ACTIVE_SERVICE='app-blue'", deploy_script)
        self.assertIn("ACTIVE_SERVICE='app-green'", deploy_script)
        self.assertIn(
            'docker compose --project-name "\\$COMPOSE_PROJECT_NAME" --file "\\$COMPOSE_FILE" up -d --no-deps "\\$1"',
            deploy_script,
        )
        self.assertIn(
            'docker compose --project-name "\\$COMPOSE_PROJECT_NAME" --file "\\$COMPOSE_FILE" stop --timeout "\\$STOP_TIMEOUT_SECONDS" "\\$ACTIVE_SERVICE"',
            deploy_script,
        )
        self.assertIn(
            'docker compose --project-name "\\$COMPOSE_PROJECT_NAME" --file "\\$COMPOSE_FILE" rm --force "\\$ACTIVE_SERVICE"',
            deploy_script,
        )
        self.assertNotIn("docker run --detach", deploy_script)
        self.assertNotIn(
            'docker compose --project-name "\\$COMPOSE_PROJECT_NAME" --file "\\$COMPOSE_FILE" down',
            deploy_script,
        )

    def test_deployment_collects_alloy_diagnostics_before_rollback(self):
        deploy_script = MONITORING_DEPLOY_SCRIPT.read_text()

        self.assertIn("diagnose_alloy()", deploy_script)
        self.assertIn("docker inspect --format", deploy_script)
        self.assertIn("docker top", deploy_script)
        self.assertIn("docker logs --tail 200", deploy_script)
        self.assertIn("ss -ltnp 'sport = :12345'", deploy_script)
        self.assertLess(
            deploy_script.index("diagnose_alloy"),
            deploy_script.index("down --remove-orphans"),
        )

    def test_monitoring_domain_redirects_to_grafana_cloud(self):
        nginx_configuration = MONITORING_NGINX_CONFIG.read_text()

        self.assertEqual(
            2,
            nginx_configuration.count(
                "return 302 https://largeivy1754.grafana.net$request_uri;"
            ),
        )
        self.assertNotIn("127.0.0.1:3000", nginx_configuration)


if __name__ == "__main__":
    unittest.main()
