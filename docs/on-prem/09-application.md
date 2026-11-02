# 09 — Application: the GPS services on Kubernetes

The Realtime GPS Server running on the cluster: the id, ping and history services, Kong as the API
gateway at `https://api.rls.internal`, and MySQL, Redis and RabbitMQ beside them. Argo CD deploys it
from [`deploy/kubernetes/gps`](../../deploy/kubernetes/gps) in the last sync wave; its images come
from the cluster's own registry.

```
 client ─HTTPS─▶ api.rls.internal ─▶ Ingress-NGINX ─▶ Kong ─┬─▶ id-service ───────────────▶ MySQL gps_id
                                                             └─▶ ping-service ─┬─▶ Redis
                                                                               └─▶ RabbitMQ ─▶ history-service ─▶ MySQL gps_history
```

| Code | What it is |
|---|---|
| [`deploy/kubernetes/gps`](../../deploy/kubernetes/gps) | The chart: services, Kong, data stores, network policies, monitoring |
| [`deploy/kubernetes/registry`](../../deploy/kubernetes/registry) | The image registry at `registry.rls.internal` |
| [`gateway/kong/Dockerfile`](../../gateway/kong/Dockerfile) | Kong's production image, with the reviewed `kong.yml` and `rls_auth` baked in |
| [`scripts/publish-images.sh`](../../scripts/publish-images.sh) | Builds and pushes every image of a release |

---

## What runs

| Component | Image | Replicas | Volume |
|---|---|---|---|
| id-service, ping-service, history-service | `registry.rls.internal/gps/<service>:<tag>` | 2 each, spread over workers | — |
| Kong | `registry.rls.internal/gps/kong:<tag>` (Kong 3.9.4) | 2 | — |
| MySQL | `mysql:8.4.13` | 1 | 20 Gi |
| Redis | `redis:7.4.12-alpine` | 1 | 5 Gi |
| RabbitMQ | `rabbitmq:4.4.0-management` | 1 | 8 Gi |

The services and Kong run two replicas with a disruption budget, so a node drain or a rolling update
never takes them down. The data stores run one replica each on a Longhorn volume replicated three
times: if their node fails they restart elsewhere with their data, after a short interruption.

---

## The local stack and the cluster

The services are the same artifacts; only their surroundings change.

| Concern | Docker Compose | Kubernetes |
|---|---|---|
| Finding each other | Consul and Docker DNS | Kubernetes DNS. Service names are unchanged, so `kong.yml` is identical |
| Configuration | Consul KV (`deploy/consul/kv`) | ConfigMap `gps-config`, mounted as `/app/config/application.yml`; Consul is switched off (`SPRING_CLOUD_CONSUL_ENABLED=false`) |
| Secrets | Development values | Vault → Ansible → Secret `gps-secrets` → environment variables |
| Gateway token | `local-dev-gateway-token` in `kong.yml` | Kong's entrypoint swaps in the real token and refuses to start without the line it replaces |
| Kong | The official image with mounted files; Admin API on loopback | The production image; Admin API off, health and metrics on `:8100` |
| Logs | Plain text | One ECS JSON object per line, with the request's correlation, company and user ids |
| MySQL | Development users from `deploy/mysql/init` | Schemas and users created on first start with the passwords from the vault |
| Redis | No password | Password; `maxmemory` below the container limit, so with `noeviction` it refuses writes instead of being killed |

---

## The image registry

CNCF Distribution 3.1.2 at `https://registry.rls.internal` (sync wave 2), on a 20 Gi Longhorn volume
that Argo CD never deletes. Every request needs the account `gps`, whose password is
`vault_registry_password`; Ansible derives the registry's bcrypt entry and the cluster's pull Secret
(`registry-pull`) from it, so the two always match. Only Ingress-NGINX and Prometheus can reach the
registry pod. Nodes pull through the same address — they trust the cluster CA since the TLS layer.

Deleting a tag frees no space by itself. Once in a while, with no push in progress:

```bash
kubectl -n registry exec deploy/registry -- registry garbage-collect --delete-untagged /etc/distribution/config.yml
```

---

## Releasing

1. Once per workstation: trust the cluster CA (see [06 — TLS](06-tls.md)) and
   `docker login registry.rls.internal -u gps`.
2. From a clean checkout of the commit to release:

   ```bash
   ./scripts/publish-images.sh
   ```

   It builds and unit-tests the services, builds the four images for `linux/amd64`, pushes them
   tagged with the commit, and prints the tag.
3. In `deploy/kubernetes/gitops/values.yaml`, set `apps.gps.imageTag` to that tag — and, for the
   very first release, `apps.gps.enabled: true`. Commit and merge.
4. Argo CD rolls the services and Kong over, one pod at a time: a new pod receives traffic only once
   it is ready, and an old one stops receiving it before it shuts down.

Rolling back is reverting that commit. The data stores' images change only with this chart.

**Secrets.** All of them are in the vault (see `inventory/vault.example.yml` for what each is and how
to generate it). After changing one, re-run `ansible-playbook site.yml --tags secrets --ask-vault-pass`
and restart what reads it — pods read Secrets at start-up:
`kubectl -n gps rollout restart deployment` (and `statefulset/redis` or `statefulset/rabbitmq` for
their passwords). MySQL user passwords are only set on the first start of an empty volume; changing
them later is an `ALTER USER` in MySQL as well.

---

## Security

- **One way in.** Only Kong's routes are public. A default-deny network policy admits exactly the
  connections the system makes — Ingress-NGINX to Kong; Kong to the id service's public and internal
  ports and to the ping service; the services to their data stores; Prometheus to metrics ports.
- **Defence in depth.** Even a request that reached a service directly would fail without the
  gateway token, which only Kong presents. Kong's Admin API is switched off.
- **Real client addresses.** Kong trusts `X-Forwarded-For` only from the pod network, where
  Ingress-NGINX runs, so the per-IP limit on `/api/v1/auth/token` applies to each client rather
  than to the ingress controller.
- **Containers.** The services run as a non-root user on a read-only root filesystem, without
  capabilities or privilege escalation. Kong runs without capabilities or privilege escalation as
  its image's non-root `kong` user — not enforced with `runAsNonRoot` — on a writable filesystem.

---

## Observability

| Signal | Where |
|---|---|
| Metrics | Each service's `/actuator/prometheus` (with latency histograms), Kong's `/metrics` on `:8100`, RabbitMQ's on `:15692` — all scraped through ServiceMonitors |
| Dashboard | Grafana → **GPS services**: request rate, 5xx share and p95 latency per service, Kong by route and status, queue depth, JVM heap, database connections |
| Alerts | `GpsComponentDown`, `GpsHighErrorRate` (over 5 % for 10 minutes), `GpsSlowRequests` (p95 over 1 s for 15 minutes), `GpsLocationQueueBacklog` (history service falling behind) |
| Logs | Loki: `{namespace="gps", app="ping-service"} \| json \| log_level="ERROR"`, or one request across every service: `{namespace="gps"} \| json \| correlationId="<id>"` |

The alert rules and dashboard queries are checked with `promtool` from Prometheus 3.15.1.

---

## Verify

```bash
kubectl -n argocd get application gps                     # Synced, Healthy
kubectl -n gps get pods
curl -s https://api.rls.internal/api/v1/ping              # 404 from Kong: not a public route
```

The end-to-end gateway check that runs against the local stack runs against the cluster too. It
registers a company through the id service's restricted port, which only Kong may reach — a
port-forward reaches it from inside the pod instead:

```bash
kubectl -n gps port-forward svc/id-service 9081:9081 &
KONG_URL=https://api.rls.internal ID_INTERNAL_URL=http://localhost:9081 \
  SERVER_SECRET='<vault_server_secret>' ./scripts/smoke-gateway.sh
```

---

## Storage

| Volume | Size |
|---|---|
| MySQL, Redis, RabbitMQ | 20 + 5 + 8 Gi |
| Registry | 20 Gi |
| Prometheus, Loki, Alertmanager, Grafana | 30 + 30 + 2 + 2 Gi |
| **Total** | **117 Gi** of the roughly 150 Gi the default topology offers (see [05 — Storage](05-storage.md#disks)) |

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `ImagePullBackOff`, `401 Unauthorized` | The `registry-pull` Secret is missing or stale: re-run `--tags secrets` |
| `ImagePullBackOff`, `x509: certificate signed by unknown authority` | The node does not trust the cluster CA: re-run `--tags tls` |
| `ImagePullBackOff`, `not found` | `apps.gps.imageTag` names a tag that was never pushed |
| The application shows `images.tag must name a published release` | `apps.gps.enabled` is on but `apps.gps.imageTag` is empty |
| Every API call answers `401 gateway_token_invalid` | Kong and the services hold different tokens — usually the Secret changed and only some pods restarted: `kubectl -n gps rollout restart deployment` |
| Kong exits with `has no development gateway token to replace` | `kong.yml` lost the `X-Gateway-Token:local-dev-gateway-token` line the entrypoint replaces |
| A service restarts during the first start | It started before MySQL or RabbitMQ was ready; it settles once they are |
