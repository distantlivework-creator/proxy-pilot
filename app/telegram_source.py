from __future__ import annotations

from .config import Settings
from .domain import ProxyCandidate
from .parser import extract_proxies


async def collect_from_channels(settings: Settings) -> list[ProxyCandidate]:
    if not settings.telegram_configured or not settings.channels:
        return []
    from telethon import TelegramClient

    settings.telegram_session.parent.mkdir(parents=True, exist_ok=True)
    client = TelegramClient(
        str(settings.telegram_session), settings.api_id, settings.api_hash
    )
    await client.connect()
    try:
        if not await client.is_user_authorized():
            raise RuntimeError(
                "Telegram session is not authorized. Run: python -m app.telegram_login"
            )
        found: dict[str, ProxyCandidate] = {}
        for channel in settings.channels:
            async for message in client.iter_messages(
                channel, limit=settings.max_messages_per_channel
            ):
                source = f"@{channel}/{message.id}"
                for proxy in extract_proxies(message.raw_text or "", source=source):
                    found[proxy.key] = proxy
        return list(found.values())
    finally:
        await client.disconnect()

