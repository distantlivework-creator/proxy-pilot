from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.domain import ProxyCandidate
from app.ui import PAGE
from scripts.update_catalog import (
    build_health,
    collect_candidates,
    fake_tls_domain,
    previous_rows,
    rank_candidates,
    read_sources,
    transport_mode,
    write_catalog,
)


def test_read_sources_skips_comments(tmp_path: Path):
    source_file = tmp_path / "sources.txt"
    source_file.write_text("# note\n\nhttps://example.test/list\n", encoding="utf-8")
    assert read_sources(source_file) == ["https://example.test/list"]


def test_fake_tls_secret_requires_hex_core_and_valid_domain():
    valid = "ee" + "a" * 32 + "example.com".encode().hex()
    assert fake_tls_domain(valid) == "example.com"
    assert transport_mode(valid) == "fake-tls"
    assert fake_tls_domain("ee" + "a" * 32) is None
    assert fake_tls_domain("ee" + "a" * 32 + "not hex") is None
    assert fake_tls_domain("ee" + "a" * 32 + "localhost".encode().hex()) is None
    assert transport_mode("dd" + "a" * 32) == "random-padding"
    assert transport_mode("a" * 32) == "classic"


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
    assert payload["schema_version"] == 2
    assert payload["proxies"][0]["latency_ms"] == 42


def test_write_catalog_refuses_empty_result(tmp_path: Path):
    with pytest.raises(RuntimeError, match="No reachable proxies"):
        write_catalog(tmp_path / "proxies.json", [], 0, [])


def test_write_catalog_publishes_empty_critical_state(tmp_path: Path):
    output = tmp_path / "proxies.json"
    health = build_health(0, {"sources_total": 2, "sources_ok": 0, "sources_failed": 2}, 900)
    write_catalog(output, [], 0, [], health=health)
    payload = json.loads(output.read_text(encoding="utf-8"))
    assert payload["proxies"] == []
    assert payload["health"]["level"] == "critical"
    assert payload["health"]["protect_new_connections"] is True


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
    assert all(row["checks_passed"] == 2 for row in ranked)


@pytest.mark.asyncio
async def test_rank_candidates_labels_valid_fake_tls_without_skipping_checks(monkeypatch):
    fake_tls = "ee" + "a" * 32 + "example.com".encode().hex()
    candidates = [
        ProxyCandidate("classic.example", 443, "b" * 32),
        ProxyCandidate("tls.example", 443, fake_tls),
    ]

    async def fake_latency(candidate, _timeout):
        return 25 if candidate.host == "classic.example" else 50

    monkeypatch.setattr("scripts.update_catalog.mtproto_latency", fake_latency)
    ranked = await rank_candidates(candidates, timeout=1, concurrency=2, keep=2)
    assert [row["host"] for row in ranked] == ["classic.example", "tls.example"]
    assert ranked[1]["secret_format"] == "fake-tls"
    assert ranked[1]["fake_tls_domain"] == "example.com"
    assert all(row["checks_passed"] == 2 for row in ranked)


@pytest.mark.asyncio
async def test_rank_candidates_rejects_transient_success(monkeypatch):
    candidate = ProxyCandidate("flaky.example", 443, "d" * 32)
    attempts = 0

    async def flaky_latency(_candidate, _timeout):
        nonlocal attempts
        attempts += 1
        return 20 if attempts == 1 else None

    monkeypatch.setattr("scripts.update_catalog.mtproto_latency", flaky_latency)
    assert await rank_candidates([candidate], timeout=1, concurrency=1, keep=1) == []


@pytest.mark.asyncio
async def test_rank_candidates_backfills_after_early_transient_results(monkeypatch):
    candidates = [ProxyCandidate(f"p{i}.example", 443, f"{i:x}" * 32) for i in range(5)]
    attempts = {candidate.key: 0 for candidate in candidates}

    async def fake_latency(candidate, _timeout):
        attempts[candidate.key] += 1
        if candidate.host != "p4.example" and attempts[candidate.key] == 2:
            return None
        return 10

    monkeypatch.setattr("scripts.update_catalog.mtproto_latency", fake_latency)
    ranked = await rank_candidates(candidates, timeout=1, concurrency=5, keep=1)
    assert [row["host"] for row in ranked] == ["p4.example"]
    assert attempts[candidates[-1].key] == 2


def test_previous_rows_reads_local_history(tmp_path: Path):
    output = tmp_path / "proxies.json"
    output.write_text(
        json.dumps({"proxies": [{"host": "old.example", "port": 443, "secret": "a" * 32, "success_streak": 4}]}),
        encoding="utf-8",
    )
    history = previous_rows(output, None)
    assert next(iter(history.values()))["success_streak"] == 4


def test_previous_rows_ignores_non_object_payload(tmp_path: Path):
    output = tmp_path / "proxies.json"
    output.write_text("[]", encoding="utf-8")
    assert previous_rows(output, None) == {}


def test_collect_candidates_reports_source_health(monkeypatch):
    stats = {}

    def fake_fetch(url, timeout=15):
        if "bad" in url:
            raise OSError("offline")
        return "https://t.me/proxy?server=ok.example&port=443&secret=" + "a" * 32

    monkeypatch.setattr("scripts.update_catalog.fetch_source", fake_fetch)
    candidates = collect_candidates(["https://good", "https://bad"], 10, stats)
    assert len(candidates) == 1
    assert stats == {"sources_total": 2, "sources_ok": 1, "sources_failed": 1}


def test_collect_candidates_checks_later_sources_after_limit(monkeypatch):
    stats = {}

    def fake_fetch(url, timeout=15):
        if "second" in url:
            raise OSError("offline")
        return "https://t.me/proxy?server=one.example&port=443&secret=" + "a" * 32

    monkeypatch.setattr("scripts.update_catalog.fetch_source", fake_fetch)
    candidates = collect_candidates(["https://first", "https://second"], 1, stats)
    assert len(candidates) == 1
    assert stats == {"sources_total": 2, "sources_ok": 1, "sources_failed": 1}


@pytest.mark.parametrize(
    ("count", "failed", "level", "protect"),
    [
        (2, 0, "critical", True),
        (7, 0, "degraded", False),
        (12, 1, "degraded", False),
        (12, 0, "healthy", False),
    ],
)
def test_build_health_protects_only_when_reserves_are_critical(count, failed, level, protect):
    health = build_health(
        count,
        {"sources_total": 2, "sources_ok": 2 - failed, "sources_failed": failed},
        1234,
    )
    assert health["level"] == level
    assert health["protect_new_connections"] is protect
    assert health["hosting_usage_visible"] is False
    assert health["collector_duration_ms"] == 1234


def test_ui_contains_resilience_flow():
    assert 'Да, работает' in PAGE
    assert 'Настроить 3 резерва' in PAGE
    assert 'Auto-Switch' in PAGE
    assert 'proxyPilotBlockedV1' in PAGE
    assert 'Сейчас нет ни одного доступного адреса' in PAGE
    assert 'Как пользоваться' in PAGE
    assert 'Частые вопросы' in PAGE
    assert 'Добавьте 3 прокси по одному' in PAGE
    assert "$('#power').onclick=handleDeckClick" in PAGE
    assert 'Добавить только один прокси' not in PAGE
    assert 'Что такое Auto-Switch и как его включить?' in PAGE
    assert 'for(const number of [3,2,1])' in PAGE
    assert "wizardCompleted>=3" in PAGE
    assert "wizardPhase!=='awaiting-confirmation'" in PAGE
    assert 'vinyl-disc' in PAGE
    assert 'tonearm' in PAGE
    assert 'Добавьте один прокси' in PAGE
    assert 'Подтвердите и повторите' in PAGE
    assert 'proxyPilotDeckV1' in PAGE
    assert 'Остановить пластинку' in PAGE
    assert 'Настройки Telegram не изменились' in PAGE
    assert "location.href='tg://settings'" in PAGE
    assert 'faq-rack' in PAGE
    assert 'faq-collapse' in PAGE
    assert 'Свернуть ответ ↑' in PAGE
    assert 'align-items:start' in PAGE
    assert "summary.scrollIntoView" in PAGE
    assert 'Заменить набор прокси' in PAGE
    assert 'перестали работать все три' in PAGE
    assert 'Почему сайт можно закрыть?' in PAGE
    assert 'Почему три кружка не становятся красными?' in PAGE
    assert "$('#deckReplace').onclick=replaceDeckSet" in PAGE
    assert 'Один переход вместо трёх' in PAGE
    assert 'Сначала отправьте сообщение себе' in PAGE
    assert 'bundleShareUrl' in PAGE
    assert 'Почему Telegram не возвращает меня на сайт?' in PAGE
    assert "$('#bundleOpen').onclick=openBundleInTelegram" in PAGE
    assert "$('#bundleConfirmed').onclick=confirmBundle" in PAGE
    assert 'Сначала отправьте сообщение себе' in PAGE
    assert 'Ссылки становятся нажимаемыми после отправки сообщения' in PAGE
    assert 'Выбрать «Избранное» и отправить' in PAGE
    assert 'До отправки ссылки не нажимаются' in PAGE
    assert 'Как работает прокси' in PAGE
    assert 'Прокси — как объездная дорога до Telegram' in PAGE
    assert 'Оператор' in PAGE
    assert 'Страна и регион' in PAGE
    assert 'Wi‑Fi или мобильная сеть' in PAGE
    assert 'Почему один прокси работает не у всех?' in PAGE
    assert 'не обязательно сломан навсегда' in PAGE
    assert 'Чем MTProto Proxy отличается от VPN?' in PAGE
    assert 'отдельная объездная полоса только для Telegram' in PAGE
    assert 'не защищает и не перенаправляет весь трафик' in PAGE
    assert 'Что означает ключ EE' in PAGE
    assert 'формат Fake‑TLS' in PAGE
    assert 'transportText' in PAGE
    assert 'Поделиться приложением' in PAGE
    assert 'Задонатить' in PAGE
    assert 'shareApplication' in PAGE
    assert 'id="androidDownload"' in PAGE
    assert 'Proxy-Pilot-Android.apk' in PAGE
    assert 'Скачать для Android' in PAGE
    assert 'class="android-download"' in PAGE
    assert 'download hidden' in PAGE
    assert 'ничего не списывает' in PAGE
    assert 'proxyPilotThemeV1' in PAGE
    assert 'themeToggle' in PAGE
    assert 'data-theme="dark"' in PAGE
    assert 'startWizard()' in PAGE
    assert 'proxyPilotPartialSetupV1' in PAGE
    assert 'savePartialProgress()' in PAGE
    assert 'Прогресс сохранён' in PAGE
    assert "async function startWizard(){if(wizardPhase==='count-in'" in PAGE
    assert "const protectedMode=!configured&&available===0" in PAGE
    assert 'Один активен' not in PAGE
    assert 'подключено автоматически' not in PAGE.lower()
    assert 'Прокси отключён' not in PAGE


def test_vinyl_is_visual_only_without_music_artifacts():
    assert "AudioContext" not in PAGE
    assert "proxyPilotSoundV1" not in PAGE
    assert "proxyPilotVolumeV1" not in PAGE
    assert "proxyPilotTrackV1" not in PAGE
    assert "soundToggle" not in PAGE
    assert "volumeSlider" not in PAGE
    assert "scheduleMusic" not in PAGE
    assert "🔊" not in PAGE and "🔇" not in PAGE
    assert "Визуальная анимация остановлена" in PAGE
    assert "classList.toggle('persistent'" in PAGE


def test_service_worker_forces_fresh_navigation_and_updates_immediately():
    worker = (Path(__file__).resolve().parents[1] / "app" / "static" / "sw.js").read_text()
    assert 'proxy-pilot-v10' in worker
    assert 'event.request.mode === "navigate" ? "no-store"' in worker
    assert "updateViaCache:'none'" in PAGE
    assert "controllerchange" in PAGE


def test_faq_is_a_responsive_three_two_one_slider():
    assert "className='faq-slider'" in PAGE
    assert "flex:0 0 calc((100% - 28px)/3)" in PAGE
    assert "flex-basis:calc((100% - 14px)/2)" in PAGE
    assert "flex-basis:100%" in PAGE
    assert "faqPrev.onclick=()=>moveFaq(-1)" in PAGE
    assert "faqNext.onclick=()=>moveFaq(1)" in PAGE
    assert "faqItems.forEach(other=>{if(other!==item)" in PAGE
    assert "aspect-ratio:1/1" in PAGE
    assert ".faq-rack details[open] p{position:absolute;inset:84px 0 58px" in PAGE
    assert ".faq-collapse{display:none;position:absolute" in PAGE


def test_language_toggle_translates_the_whole_interface():
    assert "proxyPilotLanguageV1" in PAGE
    assert "langToggle.className='lang-btn'" in PAGE
    assert '.lang-btn[data-language="en"]' in PAGE
    assert "langToggle.dataset.language=language" in PAGE
    assert "if(langToggle.textContent)langToggle.textContent=''" in PAGE
    assert "document.documentElement.lang=language" in PAGE
    assert "const TRANSLATIONS=" in PAGE
    assert "Questions in record sleeves" in PAGE
    assert "How is MTProto Proxy different from a VPN?" in PAGE
    assert "Collapse answer ↑" in PAGE
    assert "languageObserver.observe" in PAGE
