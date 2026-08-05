from pathlib import Path
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
GRAFANA_DIRECTORY = REPOSITORY_ROOT / "infra/monitoring/grafana"
PRODUCTION_DATASOURCE_DIRECTORY = GRAFANA_DIRECTORY / "provisioning/datasources"
LOCAL_DATASOURCE_FILE = GRAFANA_DIRECTORY / "datasources.local.yml"
LOCAL_COMPOSE_FILE = REPOSITORY_ROOT / "infra/monitoring/docker-compose.local.yml"
PRODUCTION_COMPOSE_FILE = REPOSITORY_ROOT / "infra/monitoring/docker-compose.yml"
PRODUCTION_ALLOY_FILE = REPOSITORY_ROOT / "infra/monitoring/alloy/config.alloy"
MONITORING_DEPLOY_SCRIPT = REPOSITORY_ROOT / "deploy/monitoring/apply.sh"
MONITORING_DEPLOY_WORKFLOW = REPOSITORY_ROOT / ".github/workflows/deploy-monitoring.yml"


class GrafanaProvisioningTest(unittest.TestCase):
    def test_production_provisioning_has_only_one_default_datasource(self):
        datasource_files = sorted(PRODUCTION_DATASOURCE_DIRECTORY.glob("*.yml"))

        self.assertEqual(["datasources.yml"], [file.name for file in datasource_files])
        default_datasource_count = sum(
            file.read_text().count("isDefault: true") for file in datasource_files
        )
        self.assertEqual(1, default_datasource_count)

    def test_local_compose_mounts_datasource_outside_production_provisioning(self):
        self.assertTrue(LOCAL_DATASOURCE_FILE.is_file())
        self.assertFalse(
            (PRODUCTION_DATASOURCE_DIRECTORY / "datasources.local.yml").exists()
        )
        self.assertIn(
            "./grafana/datasources.local.yml:/etc/grafana/provisioning/datasources/datasources.yml:ro",
            LOCAL_COMPOSE_FILE.read_text(),
        )

    def test_production_alloy_sends_metrics_and_logs_with_a_secret_file(self):
        alloy_configuration = PRODUCTION_ALLOY_FILE.read_text()
        production_compose = PRODUCTION_COMPOSE_FILE.read_text()

        self.assertNotIn("remote_write:", (REPOSITORY_ROOT / "infra/monitoring/prometheus/prometheus.yml").read_text())
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
        self.assertIn('loki.write "local"', alloy_configuration)
        self.assertIn('loki.write "grafana_cloud"', alloy_configuration)
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


if __name__ == "__main__":
    unittest.main()
