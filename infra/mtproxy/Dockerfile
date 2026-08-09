FROM debian:bookworm AS build
ARG MTPROXY_COMMIT=f36d8af769ffaeac36978d38c2c0f6d1104c2137
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates git build-essential libssl-dev zlib1g-dev && \
    git clone https://github.com/TelegramMessenger/MTProxy.git /src && \
    cd /src && git checkout "$MTPROXY_COMMIT" && make

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl tini zlib1g libssl3 && \
    rm -rf /var/lib/apt/lists/* && mkdir -p /data && chown nobody:nogroup /data
COPY --from=build /src/objs/bin/mtproto-proxy /usr/local/bin/mtproto-proxy
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh
USER nobody
EXPOSE 8443
HEALTHCHECK --interval=30s --timeout=5s --retries=2 CMD curl -fsS http://127.0.0.1:8888/stats | grep -q '^ready_targets' || exit 1
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint.sh"]
