from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "site"
sys.path.insert(0, str(ROOT))

from app.ui import PAGE  # noqa: E402


def build() -> None:
    SITE.mkdir(exist_ok=True)
    html = PAGE.replace('href="/manifest.webmanifest"', 'href="./manifest.webmanifest"')
    html = html.replace('href="/static/', 'href="./static/')
    html = html.replace("register('/sw.js')", "register('./sw.js')")
    (SITE / "index.html").write_text(html, encoding="utf-8")

    static_target = SITE / "static"
    static_target.mkdir(exist_ok=True)
    for name in ("icon-192.png", "icon-512.png", "icon.svg"):
        shutil.copy2(ROOT / "app/static" / name, static_target / name)

    manifest = json.loads((ROOT / "app/static/manifest.webmanifest").read_text(encoding="utf-8"))
    manifest.update({"start_url": "./", "scope": "./"})
    for icon in manifest["icons"]:
        icon["src"] = "." + icon["src"]
    (SITE / "manifest.webmanifest").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    shutil.copy2(ROOT / "app/static/sw.js", SITE / "sw.js")
    (SITE / ".nojekyll").touch()


if __name__ == "__main__":
    build()
