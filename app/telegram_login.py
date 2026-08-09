from __future__ import annotations

import asyncio

from .config import get_settings


async def main() -> None:
    settings = get_settings()
    if not settings.telegram_configured:
        raise SystemExit("Fill TELEGRAM_API_ID and TELEGRAM_API_HASH in .env first")
    from telethon import TelegramClient

    settings.telegram_session.parent.mkdir(parents=True, exist_ok=True)
    client = TelegramClient(
        str(settings.telegram_session), settings.api_id, settings.api_hash
    )
    await client.start()
    me = await client.get_me()
    print(f"Authorized as {me.first_name} (id={me.id})")
    await client.disconnect()


if __name__ == "__main__":
    asyncio.run(main())

