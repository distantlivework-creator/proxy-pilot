from __future__ import annotations

import asyncio
import contextlib
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from .config import get_settings
from .database import Database
from .parser import parse_proxy_url
from .service import PilotService
from .ui import PAGE


settings = get_settings()
database = Database(settings.database_path)
service = PilotService(settings, database)
static_dir = settings.database_path.parent.parent / "app" / "static"


async def scheduler() -> None:
    while True:
        try:
            await service.sync()
        except Exception as exc:
            print(f"Background sync failed: {type(exc).__name__}: {exc}")
        await asyncio.sleep(settings.scan_interval_seconds)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    task = asyncio.create_task(scheduler()) if (settings.telegram_configured or settings.cluster_configured) else None
    yield
    if task:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task


app = FastAPI(title="MTProxy Pilot", version="0.1.0", lifespan=lifespan)
app.mount("/static", StaticFiles(directory=static_dir), name="static")


class ProxyInput(BaseModel):
    url: str


@app.get("/", response_class=HTMLResponse)
async def index() -> str:
    return PAGE


@app.get("/manifest.webmanifest", include_in_schema=False)
async def manifest():
    return FileResponse(static_dir / "manifest.webmanifest", media_type="application/manifest+json")


@app.get("/sw.js", include_in_schema=False)
async def service_worker():
    return FileResponse(
        static_dir / "sw.js",
        media_type="application/javascript",
        headers={"Service-Worker-Allowed": "/", "Cache-Control": "no-cache"},
    )


@app.get("/api/status")
async def status():
    proxies = database.list_proxies()
    return {
        "telegram_configured": settings.telegram_configured,
        "channels": settings.channels,
        "scan_interval_seconds": settings.scan_interval_seconds,
        "proxy_count": len(proxies),
        "alive_count": sum(item["status"] == "alive" for item in proxies),
        "last_run": database.last_run(),
        "cluster_configured": settings.cluster_configured,
        "nodes": database.list_nodes() if settings.cluster_configured else [],
    }


@app.get("/api/connection")
async def connection():
    if settings.cluster_configured:
        nodes = database.list_nodes()
        healthy = [node for node in nodes if node["healthy"]]
        return {
            "ready": bool(healthy),
            "host": settings.stable_proxy_host,
            "port": settings.stable_proxy_port,
            "secret": settings.stable_proxy_secret,
            "latency_ms": min((node["latency_ms"] for node in healthy if node["latency_ms"] is not None), default=None),
            "healthy_nodes": len(healthy),
            "total_nodes": len(settings.cluster_nodes),
            "mode": "cluster",
        }
    best = database.best_proxy()
    return {
        "ready": best is not None,
        "host": best["host"] if best else None,
        "port": best["port"] if best else None,
        "secret": best["secret"] if best else None,
        "latency_ms": best["latency_ms"] if best else None,
        "healthy_nodes": 1 if best else 0,
        "total_nodes": len(database.list_proxies()),
        "mode": "sources",
    }


@app.get("/api/proxies")
async def proxies():
    return database.list_proxies()


@app.post("/api/proxies", status_code=201)
async def add_proxy(payload: ProxyInput):
    candidate = parse_proxy_url(payload.url)
    if not candidate:
        raise HTTPException(422, "Некорректная MTProto proxy-ссылка")
    service.add_manual(candidate)
    return {"ok": True, "proxy": candidate.share_url()}


@app.post("/api/sync")
async def sync():
    try:
        return await service.sync()
    except RuntimeError as exc:
        raise HTTPException(409, str(exc)) from exc


@app.post("/api/open-best")
async def open_best():
    url = service.open_best()
    if not url:
        raise HTTPException(404, "Рабочий прокси пока не найден")
    return {"ok": True, "url": url}
