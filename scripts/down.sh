#!/usr/bin/env bash
# Stop the local stack.
#
#   ./scripts/down.sh        -> stop containers, keep data volumes
#   ./scripts/down.sh -v     -> stop containers and delete MySQL/Redis/RabbitMQ data
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deploy/docker-compose.yml"

docker compose -f "${COMPOSE_FILE}" down "$@"
