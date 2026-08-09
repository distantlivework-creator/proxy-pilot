from app.parser import extract_proxies, parse_proxy_url


SECRET = "dd" + "a" * 32


def test_parse_https_proxy_link():
    proxy = parse_proxy_url(
        f"https://t.me/proxy?server=proxy.example.com&port=443&secret={SECRET}"
    )
    assert proxy is not None
    assert proxy.host == "proxy.example.com"
    assert proxy.port == 443
    assert proxy.secret == SECRET


def test_parse_tg_link_and_html_ampersands():
    proxies = extract_proxies(
        f"Попробуй tg://proxy?server=1.2.3.4&amp;port=8443&amp;secret={SECRET}"
    )
    assert len(proxies) == 1
    assert proxies[0].host == "1.2.3.4"


def test_deduplicates_links():
    link = f"https://t.me/proxy?server=p.example.org&port=443&secret={SECRET}"
    assert len(extract_proxies(f"{link}\n{link}")) == 1


def test_rejects_invalid_values():
    assert parse_proxy_url("https://t.me/proxy?server=x&port=99999&secret=short") is None
    assert parse_proxy_url(f"https://example.com/proxy?server=x&port=443&secret={SECRET}") is None

