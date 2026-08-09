from __future__ import annotations

import json
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from .domain import CheckResult, ProxyCandidate


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class Database:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._init_schema()

    def _init_schema(self) -> None:
        with self._conn:
            self._conn.executescript(
                """
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS proxies (
                    id INTEGER PRIMARY KEY,
                    proxy_key TEXT UNIQUE NOT NULL,
                    host TEXT NOT NULL,
                    port INTEGER NOT NULL,
                    secret TEXT NOT NULL,
                    source TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'new',
                    latency_ms INTEGER,
                    successes INTEGER NOT NULL DEFAULT 0,
                    failures INTEGER NOT NULL DEFAULT 0,
                    consecutive_failures INTEGER NOT NULL DEFAULT 0,
                    first_seen TEXT NOT NULL,
                    last_seen TEXT NOT NULL,
                    last_checked TEXT,
                    last_error TEXT,
                    removed_at TEXT
                );
                CREATE TABLE IF NOT EXISTS runs (
                    id INTEGER PRIMARY KEY,
                    started_at TEXT NOT NULL,
                    finished_at TEXT,
                    state TEXT NOT NULL,
                    stats_json TEXT NOT NULL DEFAULT '{}',
                    error TEXT
                );
                CREATE TABLE IF NOT EXISTS node_health (
                    name TEXT PRIMARY KEY,
                    address TEXT NOT NULL,
                    healthy INTEGER NOT NULL DEFAULT 0,
                    latency_ms INTEGER,
                    successes INTEGER NOT NULL DEFAULT 0,
                    failures INTEGER NOT NULL DEFAULT 0,
                    consecutive_failures INTEGER NOT NULL DEFAULT 0,
                    last_checked TEXT,
                    last_error TEXT
                );
                """
            )

    def upsert_candidates(self, candidates: Iterable[ProxyCandidate]) -> int:
        count = 0
        now = utc_now()
        with self._lock, self._conn:
            for item in candidates:
                cursor = self._conn.execute(
                    """
                    INSERT INTO proxies(proxy_key, host, port, secret, source, first_seen, last_seen)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(proxy_key) DO UPDATE SET
                      source=excluded.source, last_seen=excluded.last_seen,
                      removed_at=NULL,
                      status=CASE WHEN proxies.status='removed' THEN 'new' ELSE proxies.status END
                    """,
                    (item.key, item.host, item.port, item.secret, item.source, now, now),
                )
                count += int(cursor.rowcount > 0)
        return count

    def list_proxies(self, include_removed: bool = False) -> list[dict[str, Any]]:
        where = "" if include_removed else "WHERE removed_at IS NULL"
        rows = self._conn.execute(
            f"""SELECT * FROM proxies {where}
                ORDER BY CASE status WHEN 'alive' THEN 0 WHEN 'new' THEN 1 ELSE 2 END,
                         latency_ms IS NULL, latency_ms, last_seen DESC"""
        ).fetchall()
        return [dict(row) for row in rows]

    def candidates_to_check(self) -> list[dict[str, Any]]:
        rows = self._conn.execute(
            "SELECT * FROM proxies WHERE removed_at IS NULL ORDER BY last_checked IS NOT NULL, last_checked"
        ).fetchall()
        return [dict(row) for row in rows]

    def record_result(self, proxy_id: int, result: CheckResult, failure_limit: int) -> None:
        now = utc_now()
        with self._lock, self._conn:
            if result.ok:
                self._conn.execute(
                    """UPDATE proxies SET status='alive', latency_ms=?, successes=successes+1,
                       consecutive_failures=0, last_checked=?, last_error=NULL, removed_at=NULL
                       WHERE id=?""",
                    (result.latency_ms, now, proxy_id),
                )
            else:
                self._conn.execute(
                    """UPDATE proxies SET status=CASE WHEN consecutive_failures+1 >= ? THEN 'removed' ELSE 'dead' END,
                       latency_ms=NULL, failures=failures+1, consecutive_failures=consecutive_failures+1,
                       last_checked=?, last_error=?,
                       removed_at=CASE WHEN consecutive_failures+1 >= ? THEN ? ELSE NULL END
                       WHERE id=?""",
                    (failure_limit, now, result.error, failure_limit, now, proxy_id),
                )

    def best_proxy(self) -> dict[str, Any] | None:
        row = self._conn.execute(
            """SELECT * FROM proxies WHERE status='alive' AND removed_at IS NULL
               ORDER BY latency_ms IS NULL, latency_ms, successes DESC LIMIT 1"""
        ).fetchone()
        return dict(row) if row else None

    def start_run(self) -> int:
        with self._lock, self._conn:
            cursor = self._conn.execute(
                "INSERT INTO runs(started_at, state) VALUES (?, 'running')", (utc_now(),)
            )
            return int(cursor.lastrowid)

    def finish_run(self, run_id: int, stats: dict[str, Any], error: str | None = None) -> None:
        with self._lock, self._conn:
            self._conn.execute(
                "UPDATE runs SET finished_at=?, state=?, stats_json=?, error=? WHERE id=?",
                (utc_now(), "failed" if error else "done", json.dumps(stats), error, run_id),
            )

    def last_run(self) -> dict[str, Any] | None:
        row = self._conn.execute("SELECT * FROM runs ORDER BY id DESC LIMIT 1").fetchone()
        if not row:
            return None
        result = dict(row)
        result["stats"] = json.loads(result.pop("stats_json"))
        return result

    def record_node_result(self, name: str, address: str, result: CheckResult) -> None:
        now = utc_now()
        with self._lock, self._conn:
            self._conn.execute(
                "INSERT INTO node_health(name, address) VALUES (?, ?) ON CONFLICT(name) DO UPDATE SET address=excluded.address",
                (name, address),
            )
            if result.ok:
                self._conn.execute(
                    """UPDATE node_health SET healthy=1, latency_ms=?, successes=successes+1,
                       consecutive_failures=0, last_checked=?, last_error=NULL WHERE name=?""",
                    (result.latency_ms, now, name),
                )
            else:
                self._conn.execute(
                    """UPDATE node_health SET healthy=0, latency_ms=NULL, failures=failures+1,
                       consecutive_failures=consecutive_failures+1, last_checked=?, last_error=? WHERE name=?""",
                    (now, result.error, name),
                )

    def list_nodes(self) -> list[dict[str, Any]]:
        rows = self._conn.execute(
            "SELECT * FROM node_health ORDER BY healthy DESC, latency_ms IS NULL, latency_ms, name"
        ).fetchall()
        return [dict(row) for row in rows]
