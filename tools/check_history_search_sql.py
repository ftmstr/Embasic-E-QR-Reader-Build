#!/usr/bin/env python3
"""Execute the real Room history search SQL with stdlib SQLite (no Android SDK).

This verifies query semantics only; it is NOT a Room/Android instrumentation test.
"""
from __future__ import annotations

import re
from contextlib import closing
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DAO = ROOT / "app/src/main/java/com/embasic/eqr/data/history/ScanRecordDao.kt"


def main() -> None:
    source = DAO.read_text(encoding="utf-8")
    match = re.search(r'@Query\s*\(\s*"""(.*?)"""', source, re.DOTALL)
    if match is None or "SELECT * FROM scan_records" not in match.group(1):
        raise SystemExit("Cannot locate the Room history SELECT SQL")
    query_sql = match.group(1).strip()
    with closing(sqlite3.connect(":memory:")) as db:
        db.execute("""CREATE TABLE scan_records (
          id INTEGER PRIMARY KEY, timestamp INTEGER NOT NULL,
          displaySummary TEXT NOT NULL, rawPayload TEXT NOT NULL,
          payloadType TEXT NOT NULL
        )""")
        db.executemany(
            "INSERT INTO scan_records VALUES (?, ?, ?, ?, ?)",
            [
                (1, 100, "value 100% verified", "TEXT%DONE", "TEXT"),
                (2, 100, "value 1000 verified", "TEXTDONE", "TEXT"),
                (3, 200, "underscore_here", "literal_value", "WIFI"),
                (4, 200, "underscoreXhere", "ordinary", "WIFI"),
                (5, 300, "O'Brien", "contact", "EMAIL"),
                (6, 400, "Mixed Case", "another", "TEXT"),
            ],
        )

        def results(search: str, payload_type: str | None = None) -> list[int]:
            cursor = db.execute(query_sql, {"query": search, "payloadType": payload_type})
            return [row[0] for row in cursor.fetchall()]

        cases = [
            ("literal percent", "%", None, [1]),
            ("literal underscore", "_", None, [3]),
            ("apostrophe", "O'Brien", None, [5]),
            ("case insensitive ASCII", "mixed", None, [6]),
            ("payload type selection", "underscore", "WIFI", [4, 3]),
            ("payload type exclusion", "100%", "WIFI", []),
            ("empty search stable order", "", None, [6, 5, 4, 3, 2, 1]),
            ("no matches", "zzz-no-match", None, []),
        ]
        for label, search, payload_type, expected in cases:
            actual = results(search, payload_type)
            assert actual == expected, f"{label}: expected {expected}, got {actual}"
    print(f"PASS: {len(cases)} Room history SQL contract cases (stdlib SQLite)")
    print("Scope: SQLite query semantics; NOT Android build, Room DAO generation or device test")


if __name__ == "__main__":
    main()
