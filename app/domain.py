from __future__ import annotations

from dataclasses import dataclass
from urllib.parse import urlencode


@dataclass(frozen=True)
class ProxyCandidate:
    host: str
    port: int
    secret: str
    source: str = "manual"

    @property
    def key(self) -> str:
        return f"{self.host.lower()}:{self.port}:{self.secret.lower()}"

    def telegram_url(self) -> str:
        return "tg://proxy?" + urlencode(
            {"server": self.host, "port": self.port, "secret": self.secret}
        )

    def share_url(self) -> str:
        return "https://t.me/proxy?" + urlencode(
            {"server": self.host, "port": self.port, "secret": self.secret}
        )


@dataclass(frozen=True)
class CheckResult:
    ok: bool
    latency_ms: int | None
    method: str
    error: str | None = None

