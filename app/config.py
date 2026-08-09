from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv


ROOT = Path(__file__).resolve().parent.parent
load_dotenv(ROOT / ".env")


def _as_bool(value: str | None, default: bool = False) -> bool:
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    api_id: int | None
    api_hash: str | None
    telegram_session: Path
    database_path: Path
    channels: tuple[str, ...]
    scan_interval_seconds: int
    check_timeout_seconds: float
    failures_before_remove: int
    max_messages_per_channel: int
    open_telegram_automatically: bool
    stable_proxy_host: str | None
    stable_proxy_port: int
    stable_proxy_secret: str | None
    cluster_nodes: tuple[tuple[str, str], ...]
    node_failures_before_dns_removal: int
    cloudflare_api_token: str | None
    cloudflare_zone_id: str | None

    @property
    def telegram_configured(self) -> bool:
        return bool(self.api_id and self.api_hash)

    @property
    def cluster_configured(self) -> bool:
        return bool(self.stable_proxy_host and self.stable_proxy_secret and self.cluster_nodes)


def get_settings() -> Settings:
    raw_channels = os.getenv("CHANNELS", "")
    channels = tuple(
        item.strip().removeprefix("https://t.me/").removeprefix("@")
        for item in raw_channels.split(",")
        if item.strip()
    )
    api_id = os.getenv("TELEGRAM_API_ID", "").strip()
    session = Path(os.getenv("TELEGRAM_SESSION", "data/telegram"))
    if not session.is_absolute():
        session = ROOT / session
    db_path = ROOT / "data" / "pilot.db"
    cluster_nodes: list[tuple[str, str]] = []
    for raw_node in os.getenv("CLUSTER_NODES", "").split(","):
        if not raw_node.strip() or "=" not in raw_node:
            continue
        name, address = raw_node.split("=", 1)
        if name.strip() and address.strip():
            cluster_nodes.append((name.strip(), address.strip()))
    return Settings(
        api_id=int(api_id) if api_id else None,
        api_hash=os.getenv("TELEGRAM_API_HASH", "").strip() or None,
        telegram_session=session,
        database_path=db_path,
        channels=channels,
        scan_interval_seconds=max(30, int(os.getenv("SCAN_INTERVAL_SECONDS", "300"))),
        check_timeout_seconds=max(1.0, float(os.getenv("CHECK_TIMEOUT_SECONDS", "8"))),
        failures_before_remove=max(1, int(os.getenv("FAILURES_BEFORE_REMOVE", "3"))),
        max_messages_per_channel=max(1, int(os.getenv("MAX_MESSAGES_PER_CHANNEL", "100"))),
        open_telegram_automatically=_as_bool(os.getenv("OPEN_TELEGRAM_AUTOMATICALLY")),
        stable_proxy_host=os.getenv("STABLE_PROXY_HOST", "").strip() or None,
        stable_proxy_port=max(1, min(65535, int(os.getenv("STABLE_PROXY_PORT", "443")))),
        stable_proxy_secret=os.getenv("STABLE_PROXY_SECRET", "").strip() or None,
        cluster_nodes=tuple(cluster_nodes),
        node_failures_before_dns_removal=max(1, int(os.getenv("NODE_FAILURES_BEFORE_DNS_REMOVAL", "2"))),
        cloudflare_api_token=os.getenv("CLOUDFLARE_API_TOKEN", "").strip() or None,
        cloudflare_zone_id=os.getenv("CLOUDFLARE_ZONE_ID", "").strip() or None,
    )
