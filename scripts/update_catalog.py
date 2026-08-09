from __future__ import annotations

import argparse
import asyncio
import json
import logging
import socket
import sys
import time
import urllib.request
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

from app.domain import ProxyCandidate  # noqa: E402
from app.parser import extract_proxies  # noqa: E402


USER_AGENT = "ProxyPilot/0.2 (+https://github.com/)"
DEFAULT_PREVIOUS_URL = "https://distantlivework-creator.github.io/proxy-pilot/data/proxies.json"


def read_sources(path: Path) -> list[str]:
    return [
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def fetch_source(url: str, timeout: float = 15) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read(5_000_000).decode("utf-8", errors="replace")


def collect_candidates(
    sources: list[str], limit: int, stats: dict[str, int] | None = None
) -> list[ProxyCandidate]:
    unique: dict[str, ProxyCandidate] = {}
    stats = stats if stats is not None else {}
    stats.update({"sources_total": len(sources), "sources_ok": 0, "sources_failed": 0})
    for source in sources:
        try:
            body = fetch_source(source)
        except Exception as exc:
            stats["sources_failed"] += 1
            print(f"source failed: {source}: {type(exc).__name__}", file=sys.stderr)
            continue
        stats["sources_ok"] += 1
        for candidate in extract_proxies(body, source=source):
            unique[candidate.key] = candidate
            if len(unique) >= limit:
                break
    return list(unique.values())


async def tcp_latency(candidate: ProxyCandidate, timeout: float) -> int | None:
    started = time.perf_counter()
    try:
        _reader, writer = await asyncio.wait_for(
            asyncio.open_connection(candidate.host, candidate.port), timeout=timeout
        )
        writer.close()
        await writer.wait_closed()
    except (OSError, TimeoutError, socket.gaierror):
        return None
    return round((time.perf_counter() - started) * 1000)


async def mtproto_latency(candidate: ProxyCandidate, timeout: float) -> int | None:
    """Return latency only after Telegram answers through the MTProto proxy.

    An open TCP port is not enough: dead and unrelated HTTPS services often accept
    a connection on port 443. ``GetConfigRequest`` proves that the candidate can
    actually carry Telegram MTProto traffic. No Telegram user login is involved.
    """
    from telethon import TelegramClient, functions
    from telethon.network.connection import ConnectionTcpMTProxyRandomizedIntermediate
    from telethon.sessions import MemorySession

    logging.getLogger("telethon").setLevel(logging.CRITICAL)
    started = time.perf_counter()
    client = TelegramClient(
        MemorySession(),
        1,
        "0" * 32,
        connection=ConnectionTcpMTProxyRandomizedIntermediate,
        proxy=(candidate.host, candidate.port, candidate.secret),
        timeout=timeout,
        connection_retries=0,
        request_retries=0,
        auto_reconnect=False,
    )
    try:
        await asyncio.wait_for(client.connect(), timeout=timeout)
        await asyncio.wait_for(client(functions.help.GetConfigRequest()), timeout=timeout)
    except Exception:
        return None
    finally:
        await client.disconnect()
    return round((time.perf_counter() - started) * 1000)


async def rank_candidates(
    candidates: list[ProxyCandidate],
    timeout: float,
    concurrency: int,
    keep: int,
    previous: dict[str, dict] | None = None,
) -> list[dict]:
    previous = previous or {}
    semaphore = asyncio.Semaphore(concurrency)

    async def check(candidate: ProxyCandidate) -> dict | None:
        async with semaphore:
            latency = await mtproto_latency(candidate, timeout)
        if latency is None:
            return None
        row = asdict(candidate)
        row["latency_ms"] = latency
        row["check_method"] = "mtproto"
        row["link"] = candidate.share_url()
        row["key"] = candidate.key
        return row

    checked = await asyncio.gather(*(check(candidate) for candidate in candidates))
    alive = [row for row in checked if row is not None]
    def safe_streak(row: dict) -> int:
        try:
            return max(0, int(previous.get(row["key"], {}).get("success_streak", 0)))
        except (TypeError, ValueError, AttributeError):
            return 0

    alive.sort(
        key=lambda row: (
            -safe_streak(row),
            row["latency_ms"],
            row["host"],
            row["port"],
        )
    )

    # Prefer different hosts and secrets so one operator cannot remove every reserve.
    selected: list[dict] = []
    deferred: list[dict] = []
    seen_hosts: set[str] = set()
    seen_secrets: set[str] = set()
    for row in alive:
        if row["host"] in seen_hosts or row["secret"].lower() in seen_secrets:
            deferred.append(row)
        else:
            row["_diversity"] = 0
            selected.append(row)
            seen_hosts.add(row["host"])
            seen_secrets.add(row["secret"].lower())

    # Recheck every first-pass success. This lets later stable candidates replace
    # a fast-looking batch that fails its second check.
    for row in deferred:
        row["_diversity"] = 1
    shortlist = selected + deferred

    async def confirm(row: dict) -> dict | None:
        candidate = ProxyCandidate(row["host"], row["port"], row["secret"], row["source"])
        async with semaphore:
            second_latency = await mtproto_latency(candidate, timeout)
        if second_latency is None:
            return None
        now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        old = previous.get(candidate.key, {})
        if not isinstance(old, dict):
            old = {}
        row["latency_ms"] = round((row["latency_ms"] + second_latency) / 2)
        row["checks_passed"] = 2
        try:
            old_streak = max(0, int(old.get("success_streak", 0)))
        except (TypeError, ValueError, AttributeError):
            old_streak = 0
        row["success_streak"] = old_streak + 1
        row["first_verified_at"] = old.get("first_verified_at", now)
        row["last_verified_at"] = now
        row["stability_score"] = max(
            1, min(99, 65 + row["success_streak"] * 5 - row["latency_ms"] // 1000)
        )
        row.pop("key", None)
        return row

    confirmed = await asyncio.gather(*(confirm(row) for row in shortlist))
    result = [row for row in confirmed if row is not None]
    result.sort(
        key=lambda row: (
            -row["success_streak"], row.get("_diversity", 1), row["latency_ms"], row["host"]
        )
    )
    for row in result:
        row.pop("_diversity", None)
    return result[:keep]


def previous_rows(output: Path, previous_url: str | None) -> dict[str, dict]:
    """Load stability history from the last deployment, falling back to the local file."""
    payload: dict = {}
    if previous_url:
        try:
            payload = json.loads(fetch_source(previous_url, timeout=10))
        except Exception as exc:
            print(f"previous catalog failed: {type(exc).__name__}", file=sys.stderr)
    if not payload and output.exists():
        try:
            payload = json.loads(output.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            payload = {}
    if not isinstance(payload, dict):
        payload = {}
    rows: dict[str, dict] = {}
    for row in payload.get("proxies", []):
        try:
            candidate = ProxyCandidate(row["host"], int(row["port"]), row["secret"], row.get("source", "history"))
        except (KeyError, TypeError, ValueError):
            continue
        rows[candidate.key] = row
    return rows


def write_catalog(
    output: Path,
    sources: list[str],
    tested: int,
    proxies: list[dict],
    health: dict | None = None,
) -> None:
    if not proxies and not (health or {}).get("protect_new_connections"):
        raise RuntimeError("No reachable proxies; keeping the previous deployment")
    payload = {
        "schema_version": 2,
        "updated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "sources": sources,
        "tested": tested,
        "health": health or {},
        "proxies": proxies,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def build_health(proxy_count: int, source_stats: dict[str, int], duration_ms: int) -> dict:
    """Describe only capacity signals a static, free-hosted service can measure.

    GitHub Pages does not expose a live concurrent-user counter to page JavaScript,
    so protection is based on confirmed reserve depth and source availability. This
    avoids pretending that Proxy Pilot can identify an arbitrary "1000th user".
    """
    failed_sources = source_stats.get("sources_failed", 0)
    if proxy_count < 3:
        level, reason = "critical", "Too few confirmed reserves"
    elif proxy_count < 8 or failed_sources:
        level, reason = "degraded", "Reserve pool or source availability is reduced"
    else:
        level, reason = "healthy", "Catalog and sources are operating normally"
    return {
        "level": level,
        "reason": reason,
        "protect_new_connections": proxy_count < 3,
        "confirmed_proxies": proxy_count,
        "sources_total": source_stats.get("sources_total", 0),
        "sources_ok": source_stats.get("sources_ok", 0),
        "sources_failed": failed_sources,
        "collector_duration_ms": duration_ms,
        "hosting_usage_visible": False,
    }


async def run(args: argparse.Namespace) -> None:
    started = time.perf_counter()
    sources = read_sources(args.sources)
    source_stats: dict[str, int] = {}
    candidates = collect_candidates(sources, args.candidate_limit, source_stats)
    history = previous_rows(args.output, args.previous_url)
    print(f"collected {len(candidates)} candidates from {len(sources)} sources")
    proxies = await rank_candidates(
        candidates, args.timeout, args.concurrency, args.keep, previous=history
    )
    health = build_health(
        len(proxies),
        source_stats,
        round((time.perf_counter() - started) * 1000),
    )
    write_catalog(args.output, sources, len(candidates), proxies, health=health)
    print(f"published {len(proxies)} reachable proxies to {args.output}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build the static Proxy Pilot catalog")
    parser.add_argument("--sources", type=Path, default=PROJECT_ROOT / "sources.txt")
    parser.add_argument("--output", type=Path, default=PROJECT_ROOT / "site/data/proxies.json")
    parser.add_argument("--timeout", type=float, default=10)
    parser.add_argument("--concurrency", type=int, default=20)
    parser.add_argument("--candidate-limit", type=int, default=300)
    parser.add_argument("--keep", type=int, default=12)
    parser.add_argument("--previous-url", default=DEFAULT_PREVIOUS_URL)
    return parser.parse_args()


if __name__ == "__main__":
    asyncio.run(run(parse_args()))
