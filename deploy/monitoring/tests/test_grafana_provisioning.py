import json
from pathlib import Path
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
        compose_file = (REPOSITORY_ROOT / "docker-compose.yml").read_text()

        self.assertIn("IGMO_DEPLOYMENT_SLOT: ${IGMO_DEPLOYMENT_SLOT:-blue}", compose_file)
        self.assertIn("IGMO_DEPLOYMENT_PORT: ${IGMO_DEPLOYMENT_PORT:-8080}", compose_file)

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

    def test_production_alloy_tracks_active_backend_and_blue_green_logs(self):
        alloy_configuration = PRODUCTION_ALLOY_FILE.read_text()
        nginx_configuration = (REPOSITORY_ROOT / "deploy/nginx/igmo.conf").read_text()

        self.assertIn('"__address__" = "127.0.0.1:18080"', alloy_configuration)
        self.assertIn('metrics_path   = "/metrics"', alloy_configuration)
        self.assertIn('regex         = "/igmo-backend(-blue|-green)?$"', alloy_configuration)
        self.assertIn("listen 127.0.0.1:18080;", nginx_configuration)
        self.assertIn("proxy_pass http://igmo_backend/actuator/prometheus;", nginx_configuration)

    def test_spring_dashboard_shows_health_and_active_slot_port(self):
        dashboard = json.loads(SPRING_DASHBOARD_FILE.read_text())
        panel = dashboard["panels"][0]
        active_target = panel["targets"][0]
        local_target = panel["targets"][1]

        self.assertEqual("앱 인스턴스 상태·활성 슬롯", panel["title"])
        self.assertIn('up{job="igmo-app", environment="production"}', active_target["expr"])
        self.assertIn("igmo_deployment_slot_active", active_target["expr"])
        self.assertIn("topk by (instance, job)", active_target["expr"])
        self.assertIn("timestamp(last_over_time", active_target["expr"])
        self.assertIn("> bool 0", active_target["expr"])
        self.assertEqual("{{slot}} :{{port}}", active_target["legendFormat"])
        self.assertEqual('up{job="igmo-app-slot-health", environment="local"}', local_target["expr"])
        self.assertEqual("{{slot}} :{{port}}", local_target["legendFormat"])
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

        self.assertIn("TARGET_SLOT='green'", deploy_script)
        self.assertIn("TARGET_SLOT='blue'", deploy_script)
        self.assertIn('--env IGMO_DEPLOYMENT_SLOT="\\$TARGET_SLOT"', deploy_script)
        self.assertIn('--env IGMO_DEPLOYMENT_PORT="\\$3"', deploy_script)

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
