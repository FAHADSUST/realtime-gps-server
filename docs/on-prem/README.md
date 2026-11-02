# On-premises platform

How the Realtime GPS Server runs entirely on infrastructure you own: virtual machines on Proxmox VE,
turned into a highly available Kubernetes cluster, layered with the networking, storage, TLS,
GitOps and observability services the application needs.

Everything here is code. A cluster is rebuilt by running three commands, and every change after
that is a reviewed commit.

| Guide | Covers |
|---|---|
| [01 — Terraform](01-terraform.md) | Proxmox VMs, the Ubuntu template, the generated Ansible inventory |
| [02 — Ansible](02-ansible.md) | Inventory, variables, secrets, running the playbooks |
| [03 — Kubernetes](03-kubernetes.md) | kubeadm, kube-vip, Cilium |
| [04 — Networking](04-networking.md) | MetalLB, Ingress-NGINX, DNS records |
| [05 — Storage](05-storage.md) | Longhorn |
| [06 — TLS](06-tls.md) | cert-manager, the internal CA, trusting it on clients |
| [07 — GitOps](07-gitops.md) | Argo CD, the app-of-apps, releasing the application |
| [08 — Observability](08-observability.md) | Prometheus, Grafana, Loki, Alloy |
| [09 — Application](09-application.md) | The GPS services on Kubernetes |
| [10 — Operations](10-operations.md) | Upgrades, backups, recovery, troubleshooting |

Read them in order to build a cluster; [10 — Operations](10-operations.md) is the runbook once it
runs. `./scripts/validate-platform.sh` checks all of it without a cluster.

---

## The pipeline

```
 Terraform ──▶ Ansible ──▶ kubeadm ──▶ Cilium ──▶ MetalLB ──▶ Longhorn ──▶ Ingress-NGINX ──▶ cert-manager ──▶ Argo CD ──▶ Prometheus ──▶ Grafana ──▶ Loki
 └── VMs ──┘   └─────────────────────── installed by Ansible ─────────────────────────────────────────────────────┘   └──── deployed by Argo CD ────┘
```

| Layer | Tool | Delivers |
|---|---|---|
| Machines | **Terraform** | Proxmox VMs with fixed IPs, a Longhorn data disk on workers, and the Ansible inventory |
| Operating system | **Ansible** | Kernel settings, containerd, kubelet/kubeadm, storage prerequisites |
| Cluster | **kubeadm** + kube-vip | Three control planes behind a virtual IP, workers joined |
| Pod network | **Cilium** | CNI, kube-proxy replacement (eBPF), network policy, Hubble flow visibility |
| Service IPs | **MetalLB** | `LoadBalancer` services get real addresses on the node network (L2) |
| Storage | **Longhorn** | Replicated block storage for every `PersistentVolumeClaim` |
| HTTP entry | **Ingress-NGINX** | One public address, host-based routing to every UI and API |
| TLS | **cert-manager** | An internal certificate authority; every ingress gets a certificate automatically |
| Delivery | **Argo CD** | Everything above the platform is deployed from this repository |
| Metrics | **Prometheus** | Cluster, platform and application metrics, alerting |
| Dashboards | **Grafana** | Metrics and logs in one place |
| Logs | **Loki** + Alloy | Every container's logs, queryable next to its metrics |

### Who installs what

The line between Ansible and Argo CD is deliberate. **Ansible installs everything Argo CD needs in
order to work** — a network, storage, an ingress and TLS — because a tool cannot deploy its own
prerequisites. **Argo CD deploys everything that runs on top of the platform**: observability, the
container registry and the application. After bootstrap, changing those is a git commit, not an
Ansible run.

After bootstrap nothing is changed by hand in the cluster: platform layers change by re-running
Ansible, everything else by committing to this repository.

---

## Topology

```
                         ┌──────────────────── node network 10.10.0.0/24 ────────────────────┐
  operators / clients ──▶│  10.10.0.10   kube-vip  ──▶ Kubernetes API (6443) on cp-01..03     │
                         │  10.10.0.200  MetalLB   ──▶ Ingress-NGINX (80/443)                 │
                         │                                                                    │
                         │  cp-01 .11   cp-02 .12   cp-03 .13     control plane + etcd        │
                         │  wk-01 .21   wk-02 .22   wk-03 .23     workloads + Longhorn disks  │
                         └────────────────────────────────────────────────────────────────────┘
                              pods 10.244.0.0/16 (Cilium)      services 10.96.0.0/12
```

Every address and size below is a default in `infra/`, not a constant.

### Nodes

| Role | Count | vCPU | Memory | OS disk | Data disk |
|---|---|---|---|---|---|
| Control plane | 3 | 4 | 8 GiB | 60 GiB | — |
| Worker | 3 | 8 | 16 GiB | 80 GiB | 200 GiB (Longhorn) |

Three control planes keep etcd quorum through the loss of one node. Three workers let Longhorn keep
three replicas of every volume on three different machines.

### Network plan

| Range / address | Purpose |
|---|---|
| `10.10.0.0/24` | Node network (Proxmox bridge `vmbr0`) |
| `10.10.0.10` | Kubernetes API virtual IP (kube-vip) |
| `10.10.0.11–13` | Control plane nodes |
| `10.10.0.21–23` | Worker nodes |
| `10.10.0.200–230` | MetalLB pool for `LoadBalancer` services |
| `10.10.0.200` | Ingress-NGINX (fixed) |
| `10.244.0.0/16` | Pod CIDR (Cilium cluster-pool IPAM) |
| `10.96.0.0/12` | Service CIDR |

### DNS records

All user-facing names live under one base domain, `rls.internal` by default (`.internal` is
reserved by ICANN for private use, so it can never collide with a public name). Create these in
your DNS server — a single wildcard is enough:

| Record | Points to | Serves |
|---|---|---|
| `*.rls.internal` | `10.10.0.200` | everything below |
| `api.rls.internal` | ingress | the GPS API (Kong) |
| `argocd.rls.internal` | ingress | Argo CD |
| `grafana.rls.internal` | ingress | Grafana |
| `prometheus.rls.internal`, `alertmanager.rls.internal` | ingress | Prometheus, Alertmanager |
| `hubble.rls.internal`, `longhorn.rls.internal` | ingress | Cilium Hubble UI, Longhorn UI |
| `registry.rls.internal` | ingress | container registry |
| `k8s-api.rls.internal` | `10.10.0.10` | Kubernetes API (optional; the VIP works directly) |

---

## Versions

Pinned everywhere, verified against upstream when this platform was built (November 2026).

| Component | Version | Why this version |
|---|---|---|
| Ubuntu | 24.04 LTS | Supported to 2029; the most widely tested base for Longhorn and Cilium |
| Kubernetes | **1.36.6** | Newest release every component supports: Cilium 1.20 is tested on 1.33–1.36, Longhorn 1.13 needs ≥ 1.34. 1.37 is out but Cilium does not cover it yet |
| containerd / runc | 2.4.2 / 1.5.2 | Current stable, installed from upstream releases so the version is pinned rather than whatever the distro ships |
| kube-vip | 1.3.1 | Control-plane virtual IP |
| Cilium | 1.20.4 | |
| MetalLB | 0.16.1 | |
| Longhorn | 1.13.0 | |
| Ingress-NGINX | 1.15.1 (chart 4.15.1) | **Final release — see risks** |
| cert-manager | 1.21.3 | |
| Argo CD | 3.5.6 (chart 10.11.6) | |
| Prometheus Operator | 0.94.2 (kube-prometheus-stack 92.17.0) | |
| Grafana | 13.3.0 (grafana-community chart 14.3.0) | |
| Loki | 3.7.9 (grafana-community chart 20.2.1) | |
| Alloy | 1.21.3 (chart 1.15.0) | Log collector |
| Container registry | CNCF Distribution 3.1.2 | Holds the GPS images inside the cluster |
| Kong | 3.9.4 | Last open-source line — see risks |
| MySQL / Redis / RabbitMQ | 8.4.13 / 7.4.12 / 4.4.0 | The application's data stores |
| Spring Boot / Spring Cloud | 3.5.16 / 2025.0.3 | The services |
| Helm (on nodes) | 4.3.1 | kubernetes.core 6.x supports Helm 4 |
| Terraform / bpg/proxmox | ≥ 1.9 (tested 1.17.1) / 0.119.0 | |
| ansible-core / kubernetes.core | ≥ 2.18 / 6.6.0 | |

---

## Decisions

**Proxmox VE as the hypervisor.** It is the most common open-source on-prem hypervisor for exactly
this stack. Terraform's only hypervisor-specific part is one module (`modules/proxmox-vm`); its
output — names, roles, addresses — is hypervisor-neutral, so moving to vSphere means replacing that
module, not the pipeline.

**kube-vip for the API, MetalLB for services.** MetalLB runs *inside* the cluster, so it cannot
provide the address the cluster's own API server needs before the cluster exists. kube-vip runs as
a static pod on the control planes and solves exactly that bootstrapping problem.

**Cilium replaces kube-proxy.** kubeadm skips the kube-proxy add-on and Cilium handles service
routing in eBPF: fewer moving parts, better performance, and one place to debug networking.

**An internal certificate authority.** cert-manager issues certificates from a CA it creates
in-cluster. A fully on-prem system cannot assume the internet can reach it for ACME challenges.
Organisations with an existing enterprise CA can import it instead (see [06 — TLS](06-tls.md)).

**Consul is not used on Kubernetes.** Kubernetes already provides service discovery (DNS) and
configuration (ConfigMaps and Secrets). The services already treat Consul as optional, so they run
on Kubernetes with Consul switched off and no code change. Docker Compose keeps using it for local
development.

**Kong stays as the API gateway, behind Ingress-NGINX.** Ingress-NGINX terminates TLS for every
host; Kong keeps doing what only it does here — the `rls_auth` plugin, header stripping and
per-route rate limits. The application's security model does not change between environments.

**Data stores run in the cluster on Longhorn.** MySQL, Redis and RabbitMQ run as StatefulSets with
replicated volumes. This keeps the platform self-contained; moving a store to dedicated VMs later
only changes a host name.

**Secrets never enter git.** Bootstrap secrets live in Ansible Vault and are written to the cluster
by Ansible; Argo CD applications reference them by name and never contain them.

**One Argo CD application per concern, ordered by sync waves.** Observability first, then the
registry, then the application, so the application is monitored from its first pod.

---

## Risks and notes

These were found while pinning versions and are worth knowing before going to production:

- **Ingress-NGINX is retired.** Its repository was archived in March 2026 and 1.15.1 is the last
  release: no further security fixes. It is used here because it is part of the requested stack.
  Cilium — already installed — implements the Gateway API and is the natural replacement; the
  migration is described in [04 — Networking](04-networking.md).
- **Kong's open-source images stop at 3.9.** 3.9.4 is current and remains usable; newer Kong
  versions are enterprise-only.
- **Promtail is end-of-life.** Logs are shipped with Grafana Alloy, its successor.
- **Grafana's open-source charts moved** to the `grafana-community` repository; the old
  `grafana/grafana` chart is deprecated and `grafana/loki` now targets Grafana Enterprise Logs.
- **MySQL 8.0 reached end of life in April 2026.** The platform uses MySQL 8.4 LTS.
- **Spring Boot 3.5.16 is the final 3.5 release.** The services are moved to that patch; migrating
  them to Spring Boot 4 is an application project of its own, not part of this platform.
