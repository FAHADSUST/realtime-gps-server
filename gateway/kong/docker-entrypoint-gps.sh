#!/bin/sh
# Starts Kong with the gateway token the services expect.
#
# kong.yml carries the local development token. When GATEWAY_TOKEN is set - as in every real
# deployment - a copy with that token is written and Kong reads the copy. The token must be plain
# (letters, digits, '.', '_', '-'), and the line it replaces must exist, so a deployment can never
# silently run with the development token.
set -eu

source_config="${KONG_GPS_CONFIG:-/kong/declarative/kong.yml}"
dev_header='X-Gateway-Token:local-dev-gateway-token'

if [ -n "${GATEWAY_TOKEN:-}" ]; then
  case "$GATEWAY_TOKEN" in
    *[!A-Za-z0-9._-]*)
      echo "GATEWAY_TOKEN may only contain letters, digits, '.', '_' and '-'" >&2
      exit 1
      ;;
  esac
  if ! grep -q "$dev_header" "$source_config"; then
    echo "$source_config has no development gateway token to replace" >&2
    exit 1
  fi
  runtime_config="${TMPDIR:-/tmp}/kong.yml"
  sed "s/$dev_header/X-Gateway-Token:$GATEWAY_TOKEN/" "$source_config" > "$runtime_config"
  export KONG_DECLARATIVE_CONFIG="$runtime_config"
else
  echo "GATEWAY_TOKEN is not set: running with the development gateway token" >&2
  export KONG_DECLARATIVE_CONFIG="$source_config"
fi

exec "${KONG_ENTRYPOINT:-/docker-entrypoint.sh}" "$@"
