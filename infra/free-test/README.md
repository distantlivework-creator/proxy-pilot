# Free test deployment for about 10 users

This profile runs the complete test stack on one Oracle Cloud Always Free VM:

- MTProxy on TCP `443`;
- installable PWA and API over HTTPS on `8443`;
- Caddy with automatic TLS;
- SQLite state in a Docker volume;
- one permanent free DuckDNS hostname.

It costs zero while every selected Oracle resource remains marked **Always Free Eligible**. It is a test profile, not high availability: one VM failure stops both the proxy and dashboard.

## 1. Create free accounts

1. Create an Oracle Cloud account.
2. Create a DuckDNS subdomain and point it to the Oracle VM public IPv4.

Oracle normally requires account verification. Choose the home region carefully because Always Free resources must be created there and free ARM capacity can be temporarily unavailable.

## 2. Create the VM

Recommended test shape:

- Ubuntu 24.04;
- `VM.Standard.A1.Flex`, 1 OCPU and 6 GB RAM;
- boot volume 50 GB, marked Always Free;
- reserved public IPv4.

Open inbound TCP ports `22`, `80`, `443`, and `8443` in both the OCI security list and Ubuntu firewall. Restrict SSH/22 to your own IP when possible.

## 3. Install Docker

Follow Docker's official Ubuntu installation instructions, then verify:

```bash
docker --version
docker compose version
```

## 4. Deploy

Copy the repository to the VM and run:

```bash
cd mtproxy-pilot/infra/free-test
cp .env.example .env
openssl rand -hex 16
```

Put the generated value into `MTPROXY_SECRET`, and the same value prefixed with `dd` into `MTPROXY_CLIENT_SECRET`. Set your DuckDNS hostname. Then:

```bash
docker compose up -d --build
docker compose ps
docker compose logs --tail=100
```

Caddy obtains a free TLS certificate automatically. Open:

```text
https://YOUR-SUBDOMAIN.duckdns.org:8443
```

Install the PWA from the browser and press **Открыть в Telegram**. The Telegram proxy itself uses the same hostname on port `443`.

## 5. Share with testers

Send the HTTPS dashboard link or a direct Telegram link:

```text
https://t.me/proxy?server=YOUR-SUBDOMAIN.duckdns.org&port=443&secret=ddYOUR_SECRET
```

No Proxy Pilot registration or Telegram login is required for testers.

## Limits of this free profile

- no geographic redundancy;
- Oracle can reclaim an idle Always Free VM under its documented idle-instance rules;
- free capacity may be unavailable during VM creation;
- the DuckDNS name and proxy secret can be shared by testers, so keep the group small;
- for real failover, add a second provider/VPS and enable the cluster controller.
