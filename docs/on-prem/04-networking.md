# 04 — Networking: MetalLB, Ingress-NGINX and DNS

How traffic from outside reaches the cluster. MetalLB gives `LoadBalancer` Services addresses on
the node network; Ingress-NGINX routes HTTP and HTTPS to Services by host name; DNS names point at
the ingress address. Code: roles `metallb`, `ingress_nginx` and `kubectl_apply` in
[`infra/ansible/roles`](../../infra/ansible/roles).

```
 client ──▶ *.rls.internal ──DNS──▶ 10.10.0.200 ──ARP (MetalLB)──▶ a worker
        ──▶ Ingress-NGINX (TLS, host routing) ──▶ Service ──▶ pods      (Kong for api.rls.internal)
```

---

## Addresses

Everything lives on the node network and is set in one place, Terraform's `cluster_addresses`.
Before writing the inventory it checks that no two nodes share an address and that the API VIP and
the ingress address are neither a node's address nor the same one. Keeping the node addresses
outside the MetalLB pool is up to you:

| Address | Used for |
|---|---|
| `10.10.0.11` – `10.10.0.13`, `10.10.0.21` – `10.10.0.23` | Nodes |
| `10.10.0.10` | Kubernetes API (kube-vip, see [03 — Kubernetes](03-kubernetes.md)) |
| `10.10.0.200` | Ingress — MetalLB pool `ingress`, handed out to nobody but the ingress controller |
| `10.10.0.201` – `10.10.0.230` | MetalLB pool `services`, assigned automatically to any other `LoadBalancer` Service |

Exclude the whole range `10.10.0.200` – `10.10.0.230` and the VIP from your DHCP server.

---

## MetalLB

MetalLB runs in **Layer 2 mode**: for each Service address, one node answers ARP requests, and the
traffic arriving there is forwarded by Cilium's eBPF datapath to the Service's pods. If that node
fails, another takes over within seconds. Layer 2 needs nothing from the network but a flat
segment; its limit is that each address is served by one node at a time, which is plenty for an
ingress controller. BGP mode would spread load across nodes but needs routers that speak BGP — the
chart's BGP components (FRR-K8s) are not installed.

| Choice | Why |
|---|---|
| Speakers only on workers | kubeadm labels control planes `node.kubernetes.io/exclude-from-external-load-balancers`, so they never announce |
| `ingress` pool with `autoAssign: false` | The ingress address cannot be taken by another Service before the ingress controller exists |
| Cilium's own LB IPAM off | One component hands out addresses, not two |
| ServiceMonitors and alerts | Pool usage at 75, 85 and 95 %, pool exhaustion, configuration not loaded or stale |

The pools are rendered from the inventory (`roles/metallb/templates/address-pools.yaml.j2`, with the
`ipv4_range_without` filter in [`filter_plugins/network.py`](../../infra/ansible/filter_plugins/network.py)
cutting the ingress address out of the range). The `kubectl_apply` role applies them: it runs
`kubectl diff` first and applies, server-side with field manager `ansible`, only when something
differs, so a re-run reports no change. A pool removed from the template is not deleted from the
cluster; delete it with `kubectl` as well.

**Asking for an address.** A Service gets the next free address from `services` without any
annotation. To pin one:

```yaml
metadata:
  annotations:
    metallb.io/address-pool: services
    metallb.io/loadBalancerIPs: 10.10.0.210
```

---

## Ingress-NGINX

The cluster's single HTTP and HTTPS entry point, at the reserved ingress address. It terminates
TLS (certificates come from cert-manager, next layer) and routes each host name to its Service;
for `api.rls.internal` that Service is Kong, which keeps applying the API's own authentication
and rate limits.

| Choice | Why |
|---|---|
| A DaemonSet on the workers, `externalTrafficPolicy: Local` | Any worker can take the ingress address, and backends — Kong in particular — see the real client address |
| Service pinned to `10.10.0.200` from the `ingress` pool | The address DNS points at never changes |
| Default IngressClass `nginx` | Ingresses without a class are served too |
| Snippet annotations off, version hidden | No raw NGINX configuration from Ingress objects; less to fingerprint |
| Access logs as JSON | Every field (status, latency, upstream, namespace, ingress, service) parsed by the log pipeline without a regex |
| Admission webhook kept, reachable from the API server only | It rejects an Ingress that would break NGINX's configuration. A CiliumNetworkPolicy lets only the API server reach it, closing the path the 2025 "IngressNightmare" attacks used |
| Metrics reachable from Prometheus only | Same policy; scraped through a `ServiceMonitor` |

**Status.** The Ingress-NGINX project was archived in March 2026 and 1.15.1 is its final release:
there will be no more security fixes. The restrictions above shrink what is exposed; the way out is
described in [Moving off Ingress-NGINX](#moving-off-ingress-nginx).

---

## DNS

One wildcard record covers every UI and API; the Kubernetes API name is optional:

| Record | Address |
|---|---|
| `*.rls.internal` | `10.10.0.200` |
| `k8s-api.rls.internal` | `10.10.0.10` |

In a BIND zone for `rls.internal`:

```
*        IN A 10.10.0.200
k8s-api  IN A 10.10.0.10
```

Windows DNS, Pi-hole, a router or any other DNS server works the same way. For a single
workstation without access to the DNS server, list the names in its hosts file instead — hosts
files have no wildcards:

```
10.10.0.200  api.rls.internal argocd.rls.internal grafana.rls.internal prometheus.rls.internal
10.10.0.200  alertmanager.rls.internal hubble.rls.internal longhorn.rls.internal registry.rls.internal
```

---

## Run

```bash
cd infra/ansible
ansible-playbook site.yml --tags metallb,ingress
```

## Verify

```bash
kubectl -n metallb-system get pods -o wide                  # a controller, a speaker per worker
kubectl -n metallb-system get ipaddresspools,l2advertisements
kubectl -n ingress-nginx get pods -o wide                   # a controller per worker
kubectl -n ingress-nginx get service ingress-nginx-controller   # EXTERNAL-IP 10.10.0.200
curl -i http://10.10.0.200/                                  # 404 from NGINX: nothing routed yet
```

End to end, with a throwaway Service:

```bash
kubectl create deployment echo --image=registry.k8s.io/e2e-test-images/agnhost:2.66.1 -- /agnhost netexec --http-port=8080
kubectl expose deployment echo --type=LoadBalancer --port=80 --target-port=8080
kubectl get service echo                                    # EXTERNAL-IP from 10.10.0.201-230
curl http://<EXTERNAL-IP>/hostname                          # the pod's name
kubectl describe service echo | grep -i announcing          # which node answers ARP
kubectl create ingress echo --rule="echo.rls.internal/*=echo:80"
curl http://echo.rls.internal/hostname                      # the same, through Ingress-NGINX
kubectl delete ingress,service,deployment echo
```

---

## Moving off Ingress-NGINX

Cilium, already the CNI, implements the Kubernetes Gateway API — the successor to Ingress — and this
cluster already meets its requirements (kube-proxy replacement, Cilium's L7 proxy). A migration
that keeps the site up:

1. Install the Gateway API CRDs (standard channel, v1.6.1 for Cilium 1.20) and set
   `gatewayAPI.enabled: true` in `roles/cilium/templates/values.yaml.j2`.
2. Create a `Gateway` of class `cilium` with HTTP and HTTPS listeners on a second address from the
   `services` pool, its certificate from cert-manager.
3. Convert each Ingress to an `HTTPRoute` — `ingress2gateway` (kubernetes-sigs) does most of it —
   and test every host against the new address.
4. Move the reserved address to the Gateway (or the DNS record to the new address), then remove
   Ingress-NGINX.

Annotations without a Gateway API equivalent need a replacement first: basic authentication in
front of the operator UIs becomes an authenticating proxy such as oauth2-proxy.

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `EXTERNAL-IP` stays `<pending>` | No pool can serve the request. `kubectl describe service` shows why: the pool is exhausted, or the requested address is outside every pool |
| The address is assigned but does not answer | Check ARP from another machine on the network: `arping -I <interface> <address>`. A switch or hypervisor firewall that drops gratuitous ARP, or filters by MAC and IP, blocks Layer 2 mode |
| A Service with `externalTrafficPolicy: Local` answers on some nodes only | By design: MetalLB announces it only from nodes running one of its pods |
| Creating an Ingress fails with `failed calling webhook "validate.nginx.ingress.kubernetes.io"` | The API server cannot reach the admission webhook. `kubectl -n ingress-nginx get ciliumnetworkpolicy`, then `hubble observe --namespace ingress-nginx --verdict DROPPED` from a Cilium agent |
| `curl` to the ingress address times out | Check the Service has `10.10.0.200` (the `ingress` pool), that controller pods run on the workers, and ARP as above |
