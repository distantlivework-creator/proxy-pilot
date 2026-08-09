# Stable MTProxy cluster

For a zero-cost, single-node test deployment, start with [`free-test/README.md`](free-test/README.md).

The production layout uses one hostname, one client secret and at least two independent VPS nodes:

```
Telegram clients -> proxy.example.com:443 -> DNS A records -> healthy MTProxy nodes
                                             ^
                                             | health checks + failover
                                      Proxy Pilot backend
```

## Node requirements

- Ubuntu/Debian VPS with a public IPv4 address;
- TCP port 443 open;
- Docker Engine with Compose;
- preferably different hosting providers or regions.

Generate the shared server secret once with `openssl rand -hex 16`. Copy `infra/mtproxy` to each node, put that same value into `.env`, and run:

```bash
docker compose up -d --build
docker compose ps
docker compose logs --tail=100
```

Clients use the `dd` random-padding prefix, so if the server secret is `0123...` then `STABLE_PROXY_SECRET` in the backend must be `dd0123...`. The server itself receives the original 32 hexadecimal characters without `dd`.

## DNS failover

Create DNS-only `A` records for the stable hostname, one record per node. With Cloudflare, create a scoped API token with `Zone / DNS / Edit` for only this zone. Never enable the orange-cloud proxy: Cloudflare's HTTP proxy does not carry MTProto.

Configure the backend:

```env
STABLE_PROXY_HOST=proxy.example.com
STABLE_PROXY_PORT=443
STABLE_PROXY_SECRET=dd0123456789abcdef0123456789abcdef
CLUSTER_NODES=eu=203.0.113.10,us=203.0.113.20
NODE_FAILURES_BEFORE_DNS_REMOVAL=2
CLOUDFLARE_ZONE_ID=your-zone-id
CLOUDFLARE_API_TOKEN=your-scoped-token
SCAN_INTERVAL_SECONDS=60
```

The controller performs a real Telegram connection through every node. A node is removed from DNS after two consecutive failures and restored after a successful check. It never deletes the last DNS address when every node is uncertain.

## Operational notes

- Use at least two providers; two VPS in one provider are not independent failure domains.
- Keep DNS TTL at 60 seconds.
- Register the proxy through `@MTProxyBot` if statistics or a sponsored channel are required.
- Rotate secrets only as a planned migration: changing the secret requires users to add the proxy again.
- The original Telegram MTProxy image is outdated, so this project builds the official source at a pinned commit instead.
