#!/usr/bin/env bash
# Start the local stack.
#
#   ./scripts/up.sh              -> start everything defined in deploy/docker-compose.yml
#   ./scripts/up.sh mysql        -> start a subset (plus its dependencies)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deploy/docker-compose.yml"

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is not running. Start Docker Desktop and try again." >&2
  exit 1
fi

echo "==> Starting containers (waiting for health checks)"
docker compose -f "${COMPOSE_FILE}" up -d --wait "$@"

# Consul runs in -dev mode, so its KV is empty after every restart.
if docker compose -f "${COMPOSE_FILE}" ps --services --filter status=running | grep -qx consul; then
  "${ROOT_DIR}/scripts/seed.sh"
fi

echo
docker compose -f "${COMPOSE_FILE}" ps --format "table {{.Service}}\t{{.Status}}\t{{.Ports}}"
