from pathlib import Path

from app.database import Database
from app.domain import CheckResult, ProxyCandidate


def test_failed_proxy_is_soft_removed_after_threshold(tmp_path: Path):
    db = Database(tmp_path / "test.db")
    proxy = ProxyCandidate("1.2.3.4", 443, "dd" + "a" * 32)
    db.upsert_candidates([proxy])
    proxy_id = db.list_proxies()[0]["id"]
    db.record_result(proxy_id, CheckResult(False, None, "tcp", "down"), 2)
    assert db.list_proxies()[0]["status"] == "dead"
    db.record_result(proxy_id, CheckResult(False, None, "tcp", "down"), 2)
    assert db.list_proxies() == []
    assert db.list_proxies(include_removed=True)[0]["status"] == "removed"


def test_best_proxy_prefers_lower_latency(tmp_path: Path):
    db = Database(tmp_path / "test.db")
    items = [
        ProxyCandidate("one.example", 443, "dd" + "1" * 32),
        ProxyCandidate("two.example", 443, "dd" + "2" * 32),
    ]
    db.upsert_candidates(items)
    rows = db.list_proxies()
    for row, latency in zip(rows, (150, 40)):
        db.record_result(row["id"], CheckResult(True, latency, "tcp"), 3)
    assert db.best_proxy()["latency_ms"] == 40

