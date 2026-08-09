#!/bin/sh
set -eu

: "${MTPROXY_SECRET:?MTPROXY_SECRET must be a 32-character hexadecimal value without dd prefix}"
MTPROXY_PORT="${MTPROXY_PORT:-443}"
MTPROXY_WORKERS="${MTPROXY_WORKERS:-1}"

case "$MTPROXY_SECRET" in
  *[!0-9a-fA-F]*|'') echo "MTPROXY_SECRET must be hexadecimal" >&2; exit 2 ;;
esac
if [ "${#MTPROXY_SECRET}" -ne 32 ]; then
  echo "MTPROXY_SECRET must contain exactly 32 hexadecimal characters" >&2
  exit 2
fi

curl --fail --silent --show-error --retry 5 https://core.telegram.org/getProxySecret -o /data/proxy-secret
curl --fail --silent --show-error --retry 5 https://core.telegram.org/getProxyConfig -o /data/proxy-multi.conf

set -- /usr/local/bin/mtproto-proxy -u nobody -p 8888 -H "$MTPROXY_PORT" \
  -S "$MTPROXY_SECRET" --aes-pwd /data/proxy-secret /data/proxy-multi.conf -M "$MTPROXY_WORKERS"
if [ -n "${MTPROXY_TAG:-}" ]; then
  set -- "$@" -P "$MTPROXY_TAG"
fi
exec "$@"

