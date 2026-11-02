# 04 — Networking: MetalLB, Ingress-NGINX and DNS

How traffic from outside reaches the cluster. MetalLB gives `LoadBalancer` Services addresses on
the node network; Ingress-NGINX (next layer) routes HTTP and HTTPS to Services by host name; DNS
names point at the ingress address. Code: roles `metallb` and `kubectl_apply` in
[`infra/ansible/roles`](../../infra/ansible/roles).

---

## Addresses

Everything lives on the node network and is set in one place, Terraform's `cluster_addresses`,
which checks that nothing collides before writing the inventory:

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

## Run

```bash
cd infra/ansible
ansible-playbook site.yml --tags metallb
```

## Verify

```bash
kubectl -n metallb-system get pods -o wide                  # a controller, a speaker per worker
kubectl -n metallb-system get ipaddresspools,l2advertisements
```

End to end, with a throwaway Service:

```bash
kubectl create deployment echo --image=registry.k8s.io/e2e-test-images/agnhost:2.66.1 -- /agnhost netexec --http-port=8080
kubectl expose deployment echo --type=LoadBalancer --port=80 --target-port=8080
kubectl get service echo                                    # EXTERNAL-IP from 10.10.0.201-230
curl http://<EXTERNAL-IP>/hostname                          # the pod's name
kubectl describe service echo | grep -i announcing          # which node answers ARP
kubectl delete service,deployment echo
```

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `EXTERNAL-IP` stays `<pending>` | No pool can serve the request. `kubectl describe service` shows why: the pool is exhausted, or the requested address is outside every pool |
| The address is assigned but does not answer | Check ARP from another machine on the network: `arping -I <interface> <address>`. A switch or hypervisor firewall that drops gratuitous ARP, or filters by MAC and IP, blocks Layer 2 mode |
| A Service with `externalTrafficPolicy: Local` answers on some nodes only | By design: MetalLB announces it only from nodes running one of its pods |
