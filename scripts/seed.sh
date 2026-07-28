#!/usr/bin/env bash
# Push every deploy/consul/kv/<name>.yml into Consul KV at config/<name>/data,
# which is where Spring Cloud Consul Config expects to find it.
#
# Consul runs in -dev mode (in-memory), so this is re-run on every `scripts/up.sh`.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KV_DIR="${ROOT_DIR}/deploy/consul/kv"
CONSUL_ADDR="${CONSUL_HTTP_ADDR:-http://localhost:8500}"

echo "==> Waiting for Consul at ${CONSUL_ADDR}"
for attempt in $(seq 1 30); do
  if curl -sf "${CONSUL_ADDR}/v1/status/leader" | grep -q ':'; then
    break
  fi
  if [ "${attempt}" -eq 30 ]; then
    echo "ERROR: Consul did not become ready at ${CONSUL_ADDR}" >&2
    exit 1
  fi
  sleep 2
done

echo "==> Seeding Consul KV from ${KV_DIR}"
shopt -s nullglob
seeded=0
for file in "${KV_DIR}"/*.yml; do
  name="$(basename "${file}" .yml)"
  key="config/${name}/data"
  curl -sf -X PUT --data-binary "@${file}" "${CONSUL_ADDR}/v1/kv/${key}" >/dev/null
  echo "    ${key}"
  seeded=$((seeded + 1))
done

if [ "${seeded}" -eq 0 ]; then
  echo "    (no KV files found)"
fi
echo "==> Seeded ${seeded} key(s)"
