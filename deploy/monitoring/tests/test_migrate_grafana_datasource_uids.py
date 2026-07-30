import importlib.util
import sqlite3
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = (
    Path(__file__).parents[3]
    / "infra"
    / "monitoring"
    / "grafana"
    / "migrate_datasource_uids.py"
)
SPEC = importlib.util.spec_from_file_location("migrate_datasource_uids", MODULE_PATH)
MIGRATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MIGRATION)


class GrafanaDatasourceUidMigrationTest(unittest.TestCase):

    def setUp(self):
        self.temp_directory = tempfile.TemporaryDirectory()
        self.database_path = Path(self.temp_directory.name) / "grafana.db"
        with sqlite3.connect(self.database_path) as connection:
            connection.executescript(
                "CREATE TABLE data_source ("
                "id INTEGER PRIMARY KEY,"
                "name TEXT NOT NULL,"
                "uid TEXT NOT NULL,"
                "url TEXT NOT NULL"
                ");"
                "CREATE TABLE dashboard (data TEXT NOT NULL);"
                "CREATE TABLE alert_rule (data TEXT NOT NULL);"
                "CREATE TABLE library_element (model TEXT NOT NULL);"
            )

    def tearDown(self):
        self.temp_directory.cleanup()

    def insert_datasources(self, prometheus_uid, loki_uid):
        with sqlite3.connect(self.database_path) as connection:
            connection.executemany(
                "INSERT INTO data_source(id, name, uid, url) VALUES (?, ?, ?, ?)",
                (
                    (1, "Prometheus", prometheus_uid, "http://127.0.0.1:9090"),
                    (2, "Loki", loki_uid, "http://127.0.0.1:3100"),
                ),
            )

    def datasource_rows(self):
        with sqlite3.connect(self.database_path) as connection:
            return connection.execute(
                "SELECT id, name, uid, url FROM data_source ORDER BY id"
            ).fetchall()

    def test_migrates_legacy_uids_without_recreating_datasources(self):
        self.insert_datasources(
            "PBFA97CFB590B2093",
            "P8E80F9AEF21F6940",
        )

        MIGRATION.migrate(self.database_path)

        self.assertEqual(
            [
                (1, "Prometheus", "prometheus", "http://127.0.0.1:9090"),
                (2, "Loki", "loki", "http://127.0.0.1:3100"),
            ],
            self.datasource_rows(),
        )
        self.assertTrue(
            self.database_path.with_name(
                "grafana.db.before-datasource-uid-migration"
            ).is_file()
        )

    def test_skips_already_migrated_datasources(self):
        self.insert_datasources("prometheus", "loki")

        MIGRATION.migrate(self.database_path)

        self.assertEqual(
            [
                (1, "Prometheus", "prometheus", "http://127.0.0.1:9090"),
                (2, "Loki", "loki", "http://127.0.0.1:3100"),
            ],
            self.datasource_rows(),
        )
        self.assertFalse(
            self.database_path.with_name(
                "grafana.db.before-datasource-uid-migration"
            ).exists()
        )

    def test_rejects_unexpected_uid(self):
        self.insert_datasources("unexpected", "P8E80F9AEF21F6940")

        with self.assertRaisesRegex(RuntimeError, "UID가 예상과 다릅니다"):
            MIGRATION.migrate(self.database_path)

    def test_rejects_legacy_uid_referenced_by_dashboard(self):
        self.insert_datasources(
            "PBFA97CFB590B2093",
            "P8E80F9AEF21F6940",
        )
        with sqlite3.connect(self.database_path) as connection:
            connection.execute(
                "INSERT INTO dashboard(data) VALUES (?)",
                ('{"datasource":{"uid":"PBFA97CFB590B2093"}}',),
            )

        with self.assertRaisesRegex(RuntimeError, "참조하는 리소스"):
            MIGRATION.migrate(self.database_path)


if __name__ == "__main__":
    unittest.main()
