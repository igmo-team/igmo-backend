import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]


class TracingConfigurationTest(unittest.TestCase):
    def test_application_enables_otlp_tracing_from_environment(self):
        application = (REPOSITORY_ROOT / "src/main/resources/application.yaml").read_text()

        self.assertIn("enabled: ${IGMO_TRACING_ENABLED:false}", application)
        self.assertIn("probability: ${IGMO_TRACING_SAMPLING_PROBABILITY:0.1}", application)
        self.assertIn("endpoint: ${IGMO_TRACING_OTLP_ENDPOINT:}", application)
        self.assertIn("Authorization: ${IGMO_TRACING_OTLP_AUTHORIZATION:}", application)

    def test_production_deployment_derives_otlp_authorization_from_cloud_token(self):
        deploy_script = (REPOSITORY_ROOT / ".github/scripts/deploy-via-ssm.sh").read_text()
        workflow = (REPOSITORY_ROOT / ".github/workflows/cd.yml").read_text()

        self.assertIn("GRAFANA_CLOUD_INGEST_TOKEN", deploy_script)
        self.assertIn("GRAFANA_CLOUD_TRACES_USERNAME", deploy_script)
        self.assertIn("IGMO_TRACING_OTLP_AUTHORIZATION=\"Basic $(printf", deploy_script)
        self.assertIn("GRAFANA_CLOUD_INGEST_TOKEN: ${{ secrets.GRAFANA_CLOUD_INGEST_TOKEN }}", workflow)
        self.assertIn("IGMO_TRACING_ENABLED: ${{ vars.IGMO_TRACING_ENABLED || 'true' }}", workflow)

    def test_otlp_dependencies_are_managed_by_spring_boot(self):
        build_file = (REPOSITORY_ROOT / "build.gradle.kts").read_text()

        self.assertIn('implementation("io.micrometer:micrometer-tracing-bridge-otel")', build_file)
        self.assertIn('implementation("io.opentelemetry:opentelemetry-exporter-otlp")', build_file)


if __name__ == "__main__":
    unittest.main()
