from pathlib import Path
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
GRAFANA_DIRECTORY = REPOSITORY_ROOT / "infra/monitoring/grafana"
PRODUCTION_DATASOURCE_DIRECTORY = GRAFANA_DIRECTORY / "provisioning/datasources"
LOCAL_DATASOURCE_FILE = GRAFANA_DIRECTORY / "datasources.local.yml"
LOCAL_COMPOSE_FILE = REPOSITORY_ROOT / "infra/monitoring/docker-compose.local.yml"


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


if __name__ == "__main__":
    unittest.main()
