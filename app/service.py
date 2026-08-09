from __future__ import annotations

import asyncio
import webbrowser
from typing import Any

from .checker import check_many
from .cluster import ClusterController
from .config import Settings
from .database import Database
from .domain import ProxyCandidate
from .telegram_source import collect_from_channels


class PilotService:
    def __init__(self, settings: Settings, database: Database):
        self.settings = settings
        self.database = database
        self._sync_lock = asyncio.Lock()
        self.cluster = ClusterController(settings, database)

    async def sync(self) -> dict[str, Any]:
        if self._sync_lock.locked():
            return {"state": "already_running"}
        async with self._sync_lock:
            run_id = self.database.start_run()
            stats: dict[str, Any] = {"discovered": 0, "checked": 0, "alive": 0, "removed": 0}
            try:
                if self.settings.cluster_configured:
                    cluster_stats = await self.cluster.check()
                    stats["cluster"] = cluster_stats
                    stats["checked"] = len(cluster_stats["nodes"])
                    stats["alive"] = sum(node["healthy"] for node in cluster_stats["nodes"])
                    self.database.finish_run(run_id, stats)
                    return {"state": "done", **stats}
                candidates = await collect_from_channels(self.settings)
                self.database.upsert_candidates(candidates)
                stats["discovered"] = len(candidates)
                rows = self.database.candidates_to_check()
                results = await check_many(rows, self.settings)
                stats["checked"] = len(results)
                for proxy_id, result in results:
                    self.database.record_result(
                        proxy_id, result, self.settings.failures_before_remove
                    )
                    stats["alive" if result.ok else "removed"] += 1
                best = self.database.best_proxy()
                if best and self.settings.open_telegram_automatically:
                    webbrowser.open(_telegram_url(best))
                self.database.finish_run(run_id, stats)
                return {"state": "done", **stats}
            except Exception as exc:
                self.database.finish_run(run_id, stats, f"{type(exc).__name__}: {exc}")
                raise

    def add_manual(self, proxy: ProxyCandidate) -> None:
        self.database.upsert_candidates([proxy])

    def open_best(self) -> str | None:
        best = self.database.best_proxy()
        if not best:
            return None
        url = _telegram_url(best)
        webbrowser.open(url)
        return url


def _telegram_url(row: dict[str, Any]) -> str:
    return ProxyCandidate(row["host"], row["port"], row["secret"], row["source"]).telegram_url()
