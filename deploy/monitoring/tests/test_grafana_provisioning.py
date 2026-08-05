from pathlib import Path
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
LOCAL_DATASOURCE_FILE = REPOSITORY_ROOT / "infra/monitoring/grafana/datasources.local.yml"
LOCAL_COMPOSE_FILE = REPOSITORY_ROOT / "infra/monitoring/docker-compose.local.yml"
PRODUCTION_COMPOSE_FILE = REPOSITORY_ROOT / "infra/monitoring/docker-compose.yml"
PRODUCTION_ALLOY_FILE = REPOSITORY_ROOT / "infra/monitoring/alloy/config.alloy"
MONITORING_DEPLOY_SCRIPT = REPOSITORY_ROOT / "deploy/monitoring/apply.sh"
MONITORING_DEPLOY_WORKFLOW = REPOSITORY_ROOT / ".github/workflows/deploy-monitoring.yml"


class MonitoringDeploymentTest(unittest.TestCase):
    def test_local_compose_mounts_local_datasource(self):
        self.assertTrue(LOCAL_DATASOURCE_FILE.is_file())
        self.assertIn(
            "./grafana/datasources.local.yml:/etc/grafana/provisioning/datasources/datasources.yml:ro",
            LOCAL_COMPOSE_FILE.read_text(),
        )

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


if __name__ == "__main__":
    unittest.main()
