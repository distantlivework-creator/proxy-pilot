from __future__ import annotations

import html
import ipaddress
import re
from urllib.parse import parse_qs, unquote, urlparse

from .domain import ProxyCandidate


LINK_RE = re.compile(
    r"(?:(?:https?://)?(?:t\.me|telegram\.me|telegram\.dog)/proxy\?[^\s<>\"']+|tg://proxy\?[^\s<>\"']+)",
    re.IGNORECASE,
)
HOST_RE = re.compile(r"^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)*[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")
SECRET_RE = re.compile(r"^[A-Za-z0-9_\-=]{32,512}$")


def _valid_host(host: str) -> bool:
    try:
        ipaddress.ip_address(host.strip("[]"))
        return True
    except ValueError:
        return bool(HOST_RE.fullmatch(host))


def parse_proxy_url(url: str, source: str = "manual") -> ProxyCandidate | None:
    cleaned = html.unescape(unquote(url.strip().rstrip(".,);]")))
    if not cleaned.lower().startswith(("http://", "https://", "tg://")):
        cleaned = "https://" + cleaned
    parsed = urlparse(cleaned)
    if parsed.scheme == "tg":
        is_proxy = parsed.netloc.lower() == "proxy" or parsed.path.strip("/").lower() == "proxy"
    else:
        is_proxy = parsed.netloc.lower() in {"t.me", "telegram.me", "telegram.dog"} and parsed.path.strip("/").lower() == "proxy"
    if not is_proxy:
        return None
    params = parse_qs(parsed.query)
    host = (params.get("server") or [""])[0].strip().lower()
    port_text = (params.get("port") or [""])[0].strip()
    secret = (params.get("secret") or [""])[0].strip()
    try:
        port = int(port_text)
    except ValueError:
        return None
    if not _valid_host(host) or not 1 <= port <= 65535 or not SECRET_RE.fullmatch(secret):
        return None
    return ProxyCandidate(host=host, port=port, secret=secret, source=source)


def extract_proxies(text: str, source: str = "message") -> list[ProxyCandidate]:
    unique: dict[str, ProxyCandidate] = {}
    for match in LINK_RE.finditer(html.unescape(text or "")):
        candidate = parse_proxy_url(match.group(0), source=source)
        if candidate:
            unique[candidate.key] = candidate
    return list(unique.values())

