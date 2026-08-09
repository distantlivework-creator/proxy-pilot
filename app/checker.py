from __future__ import annotations

import asyncio
import time

from .config import Settings
from .domain import CheckResult, ProxyCandidate


async def _tcp_check(proxy: ProxyCandidate, timeout: float) -> CheckResult:
    started = time.perf_counter()
    try:
        _reader, writer = await asyncio.wait_for(
            asyncio.open_connection(proxy.host, proxy.port), timeout=timeout
        )
        writer.close()
        await writer.wait_closed()
        return CheckResult(True, round((time.perf_counter() - started) * 1000), "tcp")
    except Exception as exc:
        return CheckResult(False, None, "tcp", f"{type(exc).__name__}: {exc}")


async def check_proxy(proxy: ProxyCandidate, settings: Settings) -> CheckResult:
    """Verify an actual Telegram handshake when credentials exist, otherwise test TCP reachability."""
    if not settings.telegram_configured:
        return await _tcp_check(proxy, settings.check_timeout_seconds)

    from telethon import TelegramClient
    from telethon.network.connection import ConnectionTcpMTProxyRandomizedIntermediate
    from telethon.sessions import MemorySession

    started = time.perf_counter()
    client = TelegramClient(
        MemorySession(),
        settings.api_id,
        settings.api_hash,
        connection=ConnectionTcpMTProxyRandomizedIntermediate,
        proxy=(proxy.host, proxy.port, proxy.secret),
        timeout=settings.check_timeout_seconds,
        connection_retries=0,
        request_retries=0,
    )
    try:
        await asyncio.wait_for(client.connect(), timeout=settings.check_timeout_seconds)
        await asyncio.wait_for(client.is_user_authorized(), timeout=settings.check_timeout_seconds)
        return CheckResult(True, round((time.perf_counter() - started) * 1000), "mtproto")
    except Exception as exc:
        return CheckResult(False, None, "mtproto", f"{type(exc).__name__}: {exc}")
    finally:
        await client.disconnect()


async def check_many(
    rows: list[dict], settings: Settings, concurrency: int = 8
) -> list[tuple[int, CheckResult]]:
    semaphore = asyncio.Semaphore(concurrency)

    async def one(row: dict) -> tuple[int, CheckResult]:
        async with semaphore:
            proxy = ProxyCandidate(row["host"], row["port"], row["secret"], row["source"])
            return row["id"], await check_proxy(proxy, settings)

    return await asyncio.gather(*(one(row) for row in rows))

