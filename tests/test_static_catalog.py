from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.domain import ProxyCandidate
from scripts.update_catalog import rank_candidates, read_sources, write_catalog


def test_read_sources_skips_comments(tmp_path: Path):
    source_file = tmp_path / "sources.txt"
    source_file.write_text("# note\n\nhttps://example.test/list\n", encoding="utf-8")
    assert read_sources(source_file) == ["https://example.test/list"]


def test_write_catalog_uses_stable_schema(tmp_path: Path):
    output = tmp_path / "proxies.json"
    candidate = ProxyCandidate("proxy.example", 443, "a" * 32, "test")
    write_catalog(
        output,
        ["https://example.test/list"],
        1,
        [{"host": candidate.host, "port": 443, "secret": candidate.secret, "source": "test", "latency_ms": 42, "link": candidate.share_url()}],
    )
    payload = json.loads(output.read_text(encoding="utf-8"))
    assert payload["schema_version"] == 1
    assert payload["proxies"][0]["latency_ms"] == 42


def test_write_catalog_refuses_empty_result(tmp_path: Path):
    with pytest.raises(RuntimeError, match="No reachable proxies"):
        write_catalog(tmp_path / "proxies.json", [], 0, [])


@pytest.mark.asyncio
async def test_rank_candidates_prefers_distinct_hosts(monkeypatch):
    candidates = [
        ProxyCandidate("one.example", 443, "a" * 32),
        ProxyCandidate("one.example", 80, "b" * 32),
        ProxyCandidate("two.example", 443, "c" * 32),
    ]

    async def fake_latency(candidate, _timeout):
        return {443: 10, 80: 5}[candidate.port]

    monkeypatch.setattr("scripts.update_catalog.mtproto_latency", fake_latency)
    ranked = await rank_candidates(candidates, timeout=1, concurrency=2, keep=3)
    assert [row["host"] for row in ranked[:2]] == ["one.example", "two.example"]
    assert all(row["check_method"] == "mtproto" for row in ranked)
