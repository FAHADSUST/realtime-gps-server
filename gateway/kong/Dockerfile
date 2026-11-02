# The gateway as one artifact: Kong with this directory's declarative configuration and the rls_auth
# plugin baked in, so what runs is exactly what was reviewed. Kong's open-source images end at 3.9.
#
#   docker build -t registry.rls.internal/gps/kong:<tag> gateway/kong
FROM kong:3.9.4

COPY kong.yml /kong/declarative/kong.yml
# Kong resolves kong.plugins.rls_auth against KONG_LUA_PACKAGE_PATH, hence the kong/plugins tail.
COPY plugins/ /opt/kong-plugins/kong/plugins/
COPY --chmod=0755 docker-entrypoint-gps.sh /usr/local/bin/docker-entrypoint-gps.sh

ENV KONG_DATABASE=off \
    KONG_PLUGINS=bundled,rls_auth \
    KONG_LUA_PACKAGE_PATH=/opt/kong-plugins/?.lua;; \
    KONG_PROXY_LISTEN=0.0.0.0:8000 \
    # Health (/status) and Prometheus metrics (/metrics) without the Admin API.
    KONG_STATUS_LISTEN=0.0.0.0:8100 \
    # The configuration is declarative and baked in: nothing to administer at runtime.
    KONG_ADMIN_LISTEN=off \
    KONG_PROXY_ACCESS_LOG=/dev/stdout \
    KONG_PROXY_ERROR_LOG=/dev/stderr

EXPOSE 8000 8100

ENTRYPOINT ["/usr/local/bin/docker-entrypoint-gps.sh"]
CMD ["kong", "docker-start"]
