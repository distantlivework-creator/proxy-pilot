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


def collect_candidates(sources: list[str], limit: int) -> list[ProxyCandidate]:
    unique: dict[str, ProxyCandidate] = {}
    for source in sources:
        try:
            body = fetch_source(source)
        except Exception as exc:
            print(f"source failed: {source}: {type(exc).__name__}", file=sys.stderr)
            continue
        for candidate in extract_proxies(body, source=source):
            unique[candidate.key] = candidate
            if len(unique) >= limit:
                return list(unique.values())
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
    candidates: list[ProxyCandidate], timeout: float, concurrency: int, keep: int
) -> list[dict]:
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
        return row

    checked = await asyncio.gather(*(check(candidate) for candidate in candidates))
    alive = [row for row in checked if row is not None]
    alive.sort(key=lambda row: (row["latency_ms"], row["host"], row["port"]))

    # Prefer different hosts so one failed operator cannot remove every reserve at once.
    selected: list[dict] = []
    deferred: list[dict] = []
    seen_hosts: set[str] = set()
    for row in alive:
        if row["host"] in seen_hosts:
            deferred.append(row)
        else:
            selected.append(row)
            seen_hosts.add(row["host"])
    return (selected + deferred)[:keep]


def write_catalog(output: Path, sources: list[str], tested: int, proxies: list[dict]) -> None:
    if not proxies:
        raise RuntimeError("No reachable proxies; keeping the previous deployment")
    payload = {
        "schema_version": 1,
        "updated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "sources": sources,
        "tested": tested,
        "proxies": proxies,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


async def run(args: argparse.Namespace) -> None:
    sources = read_sources(args.sources)
    candidates = collect_candidates(sources, args.candidate_limit)
    print(f"collected {len(candidates)} candidates from {len(sources)} sources")
    proxies = await rank_candidates(candidates, args.timeout, args.concurrency, args.keep)
    write_catalog(args.output, sources, len(candidates), proxies)
    print(f"published {len(proxies)} reachable proxies to {args.output}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build the static Proxy Pilot catalog")
    parser.add_argument("--sources", type=Path, default=PROJECT_ROOT / "sources.txt")
    parser.add_argument("--output", type=Path, default=PROJECT_ROOT / "site/data/proxies.json")
    parser.add_argument("--timeout", type=float, default=10)
    parser.add_argument("--concurrency", type=int, default=20)
    parser.add_argument("--candidate-limit", type=int, default=300)
    parser.add_argument("--keep", type=int, default=12)
    return parser.parse_args()


if __name__ == "__main__":
    asyncio.run(run(parse_args()))
