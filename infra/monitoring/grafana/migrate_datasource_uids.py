#!/usr/bin/env python3

import sqlite3
import sys
from pathlib import Path


UID_MIGRATIONS = {
    "Prometheus": ("PBFA97CFB590B2093", "prometheus"),
    "Loki": ("P8E80F9AEF21F6940", "loki"),
}
REFERENCE_COLUMNS = (
    ("dashboard", "data"),
    ("alert_rule", "data"),
    ("library_element", "model"),
)


def table_exists(connection: sqlite3.Connection, table: str) -> bool:
    row = connection.execute(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        (table,),
    ).fetchone()
    return row is not None


def find_references(connection: sqlite3.Connection, uid: str) -> list[str]:
    references = []
    for table, column in REFERENCE_COLUMNS:
        if not table_exists(connection, table):
            continue
        count = connection.execute(
            f"SELECT COUNT(*) FROM {table} WHERE {column} LIKE ?",
            (f"%{uid}%",),
        ).fetchone()[0]
        if count:
            references.append(f"{table}.{column}={count}")
    return references


def backup_database(connection: sqlite3.Connection, database_path: Path) -> Path:
    backup_path = database_path.with_name(
        f"{database_path.name}.before-datasource-uid-migration"
    )
    if backup_path.exists():
        return backup_path

    with sqlite3.connect(backup_path) as backup:
        connection.backup(backup)
    return backup_path


def migrate(database_path: Path) -> None:
    if not database_path.is_file():
        print(f"Grafana DB가 없어 datasource UID 마이그레이션을 건너뜁니다: {database_path}")
        return

    with sqlite3.connect(database_path) as connection:
        current_uids = dict(
            connection.execute(
                "SELECT name, uid FROM data_source WHERE name IN ('Prometheus', 'Loki')"
            )
        )
        migrations = []

        for name, (legacy_uid, target_uid) in UID_MIGRATIONS.items():
            current_uid = current_uids.get(name)
            if current_uid is None or current_uid == target_uid:
                continue
            if current_uid != legacy_uid:
                raise RuntimeError(
                    f"{name} datasource UID가 예상과 다릅니다: {current_uid}"
                )

            duplicate = connection.execute(
                "SELECT name FROM data_source WHERE uid = ? AND name <> ?",
                (target_uid, name),
            ).fetchone()
            if duplicate:
                raise RuntimeError(
                    f"변경할 UID가 다른 datasource에서 사용 중입니다: "
                    f"{target_uid} ({duplicate[0]})"
                )

            references = find_references(connection, legacy_uid)
            if references:
                raise RuntimeError(
                    f"{name} legacy UID를 참조하는 리소스가 있습니다: "
                    f"{', '.join(references)}"
                )
            migrations.append((name, legacy_uid, target_uid))

        if not migrations:
            print("Grafana datasource UID 마이그레이션이 필요하지 않습니다.")
            return

        backup_path = backup_database(connection, database_path)
        with connection:
            for name, legacy_uid, target_uid in migrations:
                connection.execute(
                    "UPDATE data_source SET uid = ? WHERE name = ? AND uid = ?",
                    (target_uid, name, legacy_uid),
                )

        migrated_uids = dict(
            connection.execute(
                "SELECT name, uid FROM data_source WHERE name IN ('Prometheus', 'Loki')"
            )
        )
        for name, _, target_uid in migrations:
            if migrated_uids.get(name) != target_uid:
                raise RuntimeError(f"{name} datasource UID 변경을 확인하지 못했습니다.")

        print(f"Grafana datasource UID 마이그레이션 완료. 백업: {backup_path}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(f"사용법: {sys.argv[0]} <grafana.db 경로>")

    try:
        migrate(Path(sys.argv[1]))
    except (RuntimeError, sqlite3.Error) as exception:
        raise SystemExit(f"Grafana datasource UID 마이그레이션 실패: {exception}")
