import json
from pathlib import Path
import shutil
import subprocess
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
REDIS_COMPOSE_FILE = REPOSITORY_ROOT / "docker-compose.redis.yml"
PRODUCTION_COMPOSE_FILE = REPOSITORY_ROOT / "docker-compose.prod.yml"
REDIS_RUNBOOK_FILE = REPOSITORY_ROOT / "deploy/redis/redis.md"


class RedisComposeTest(unittest.TestCase):
    def test_redis_compose_is_independent_and_persistent(self):
        if shutil.which("docker") is None:
            self.skipTest("Docker Compose is unavailable")

        result = subprocess.run(
            [
                "docker",
                "compose",
                "-f",
                str(REDIS_COMPOSE_FILE),
                "config",
                "--format",
                "json",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        compose = json.loads(result.stdout)
        redis = compose["services"]["redis"]

        self.assertEqual("igmo-redis", compose["name"])
        self.assertEqual("igmo-redis", redis["container_name"])
        self.assertEqual("always", redis["restart"])
        self.assertEqual(str(512 * 1024 * 1024), redis["mem_limit"])
        self.assertEqual("30s", redis["stop_grace_period"])
        self.assertNotIn("ports", redis)
        self.assertEqual(
            [
                {
                    "type": "volume",
                    "source": "redis-data",
                    "target": "/data",
                    "volume": {},
                }
            ],
            redis["volumes"],
        )
        self.assertEqual("igmo-redis-data", compose["volumes"]["redis-data"]["name"])
        self.assertEqual("igmo-runtime", compose["networks"]["igmo-runtime"]["name"])
        self.assertTrue(compose["networks"]["igmo-runtime"]["external"])
        self.assertIn("redis", redis["networks"]["igmo-runtime"]["aliases"])
        self.assertIsNone(redis["command"])
        self.assertEqual(
            ["CMD", "redis-cli", "ping"],
            redis["healthcheck"]["test"],
        )

    def test_production_blue_green_services_share_external_redis_network(self):
        compose_file = PRODUCTION_COMPOSE_FILE.read_text()
        deploy_script = (REPOSITORY_ROOT / ".github/scripts/deploy-via-ssm.sh").read_text()

        self.assertEqual(2, compose_file.count("<<: *app-common"))
        self.assertIn("networks:\n    - igmo-runtime", compose_file)
        self.assertIn(
            "igmo-runtime:\n    external: true\n    name: igmo-runtime",
            compose_file,
        )
        self.assertIn("REDIS_CONTAINER_NAME=\"igmo-redis\"", deploy_script)
        self.assertIn("docker network inspect igmo-runtime", deploy_script)
        self.assertIn("docker inspect --format", deploy_script)
        self.assertIn(
            "if [ \"\\$(docker inspect --format '{{.State.Running}}' \"\\$REDIS_CONTAINER_NAME\" 2>/dev/null || true)\" != 'true' ]; then",
            deploy_script,
        )
        self.assertIn("grep -Fxq \"\\$REDIS_CONTAINER_NAME\"", deploy_script)

    def test_redis_runbook_documents_manual_ec2_provisioning(self):
        runbook = REDIS_RUNBOOK_FILE.read_text()

        self.assertIn("docker network create --driver bridge igmo-runtime", runbook)
        self.assertIn("/opt/igmo/docker-compose.redis.yml", runbook)
        self.assertIn("docker compose -f docker-compose.redis.yml config -q", runbook)
        self.assertIn("docker compose -f docker-compose.redis.yml up -d", runbook)
        self.assertIn("docker inspect --format '{{.State.Health.Status}}' igmo-redis", runbook)
        self.assertIn("docker exec igmo-redis redis-cli ping", runbook)
        self.assertIn("down -v", runbook)


if __name__ == "__main__":
    unittest.main()
