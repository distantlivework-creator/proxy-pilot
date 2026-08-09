from __future__ import annotations

import asyncio
import json
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any

from .checker import check_proxy
from .config import Settings
from .database import Database
from .domain import ProxyCandidate


@dataclass(frozen=True)
class DnsDecision:
    desired_addresses: tuple[str, ...]
    changed: bool
    reason: str


def choose_dns_addresses(
    nodes: list[dict[str, Any]], failure_threshold: int, current_addresses: set[str]
) -> DnsDecision:
    desired = tuple(
        sorted(
            node["address"]
            for node in nodes
            if node["healthy"] or node["consecutive_failures"] < failure_threshold
        )
    )
    if not desired:
        return DnsDecision(tuple(sorted(current_addresses)), False, "all_nodes_uncertain")
    desired_set = set(desired)
    return DnsDecision(desired, desired_set != current_addresses, "health_update")


class ClusterController:
    def __init__(self, settings: Settings, database: Database):
        self.settings = settings
        self.database = database

    async def check(self) -> dict[str, Any]:
        if not self.settings.cluster_configured:
            return {"configured": False, "nodes": []}

        async def one(name: str, address: str):
            host, separator, raw_port = address.rpartition(":")
            if separator and raw_port.isdigit():
                check_host, check_port = host, int(raw_port)
            else:
                check_host, check_port = address, self.settings.stable_proxy_port
            proxy = ProxyCandidate(
                check_host,
                check_port,
                self.settings.stable_proxy_secret or "",
                source=f"cluster:{name}",
            )
            result = await check_proxy(proxy, self.settings)
            self.database.record_node_result(name, address, result)

        await asyncio.gather(*(one(name, address) for name, address in self.settings.cluster_nodes))
        nodes = self.database.list_nodes()
        dns = {"configured": False, "changed": False}
        if self.settings.cloudflare_api_token and self.settings.cloudflare_zone_id:
            dns = await asyncio.to_thread(self._reconcile_cloudflare, nodes)
        return {"configured": True, "nodes": nodes, "dns": dns}

    def _reconcile_cloudflare(self, nodes: list[dict[str, Any]]) -> dict[str, Any]:
        records = self._cf_request(
            "GET",
            "/dns_records?" + urllib.parse.urlencode(
                {"type": "A", "name": self.settings.stable_proxy_host, "per_page": 100}
            ),
        )["result"]
        current = {record["content"] for record in records}
        decision = choose_dns_addresses(
            nodes, self.settings.node_failures_before_dns_removal, current
        )
        if not decision.changed:
            return {"configured": True, "changed": False, "reason": decision.reason}

        by_address = {record["content"]: record for record in records}
        desired = set(decision.desired_addresses)
        for address in desired - current:
            self._cf_request(
                "POST",
                "/dns_records",
                {
                    "type": "A",
                    "name": self.settings.stable_proxy_host,
                    "content": address,
                    "ttl": 60,
                    "proxied": False,
                    "comment": "Managed by Proxy Pilot",
                },
            )
        for address in current - desired:
            self._cf_request("DELETE", f"/dns_records/{by_address[address]['id']}")
        return {
            "configured": True,
            "changed": True,
            "addresses": decision.desired_addresses,
            "reason": decision.reason,
        }

    def _cf_request(self, method: str, path: str, body: dict | None = None) -> dict:
        url = f"https://api.cloudflare.com/client/v4/zones/{self.settings.cloudflare_zone_id}{path}"
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(
            url,
            method=method,
            data=data,
            headers={
                "Authorization": f"Bearer {self.settings.cloudflare_api_token}",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(request, timeout=15) as response:
            payload = json.load(response)
        if not payload.get("success"):
            raise RuntimeError(f"Cloudflare API error: {payload.get('errors')}")
        return payload
