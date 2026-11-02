# 03 — Kubernetes: kubeadm, kube-vip and Cilium

kubeadm turns the prepared nodes into a highly available Kubernetes cluster: three control planes
with stacked etcd, reached through a virtual IP that kube-vip moves between them. Cilium gives it a
pod network and replaces kube-proxy. Code: the roles below in
[`infra/ansible/roles`](../../infra/ansible/roles), run by [`site.yml`](../../infra/ansible/site.yml).

| Role | Runs on | Does |
|---|---|---|
| `kubeadm_init` | first control plane | Writes the kubeadm configuration, runs `kubeadm init`, waits for the API behind the VIP |
| `kube_vip` | control planes | The kube-vip static pod that holds the API VIP |
| `kubeadm_join` | other control planes, then workers | Short-lived join credentials, `kubeadm join`, worker labels |
| `control_plane_tools` | control planes | `kubectl` access for the admin user, Helm, shell completion |
| `prometheus_operator_crds` | first control plane | The `monitoring.coreos.com` CRDs every later layer uses for its metrics |
| `cilium` | first control plane | Cilium and Hubble, installed with Helm; waits until every node is Ready |

---

## How the control plane stays available

```
               kubectl, kubelets, add-ons
                          │
                https://10.10.0.10:6443      ◀── the VIP, answered by the kube-vip leader
                          │
        ┌─────────────────┼─────────────────┐
     cp-01              cp-02             cp-03
  API server         API server        API server
  etcd ◀───────────▶ etcd ◀──────────▶ etcd          quorum: 2 of 3
  kube-vip           kube-vip          kube-vip      leader election: Lease kube-system/plndr-cp-lock
```

Every kubeconfig and every kubelet talks to `https://<VIP>:6443` (kubeadm's
`controlPlaneEndpoint`), never to a node. The kube-vip pods elect a leader through a Kubernetes
Lease; the leader adds the VIP to its interface and announces it with ARP. If it dies, another
control plane takes the lease and the VIP within 15 to 20 seconds. etcd tolerates the loss of one
of the three members.

kube-vip only provides the API address. Addresses for `LoadBalancer` Services come from MetalLB,
which cannot do this job: it runs inside the cluster, so it cannot exist before the API does.

---

## What the playbook does

```bash
cd infra/ansible
ansible-playbook site.yml --tags kubeadm,crds,cilium
```

1. **First control plane.** Writes and validates `/etc/kubernetes/kubeadm-config.yaml`, starts
   kube-vip and runs `kubeadm init`, then waits until the API answers on the VIP.
2. **Other control planes, one at a time.** For each, the first control plane creates a bootstrap
   token valid for 15 minutes and uploads the cluster certificates encrypted with a new key (kubeadm
   deletes them after two hours). The node joins with a config file that is deleted straight
   afterwards, then starts its own kube-vip.
3. **Workers, in parallel.** The same, without the certificates, plus the `node-role` label.
4. **Control planes.** `~/.kube/config` for the admin user, Helm, completion for `kubectl` and
   `helm`.
5. **CRDs.** The Prometheus Operator CRDs, so every chart from here on can ship its
   `ServiceMonitor` from the start.
6. **Cilium.** Installed with Helm on the first control plane; the play then waits until every node
   is Ready and CoreDNS is running.

Every step checks first whether it is needed, so re-running is safe: an initialised cluster is not
initialised again, and nodes that have already joined get no new credentials.

### Why kube-vip briefly uses `super-admin.conf`

Since Kubernetes 1.29, kubeadm writes two admin kubeconfigs. `admin.conf` only works once
`kubeadm init` has created its RBAC binding — which needs the API server, which (through the
`controlPlaneEndpoint`) needs the VIP, which needs kube-vip to win its leader election. To break the
loop, kube-vip on the first control plane starts with `super-admin.conf` and is switched to
`admin.conf` as soon as init completes. Joining control planes never need this: they start kube-vip
after joining, when `admin.conf` already works.

---

## Cluster configuration

Set in `roles/kubeadm_init/templates/kubeadm-config.yaml.j2` from the variables in
`group_vars/all`. kubeadm reads it once; afterwards the cluster keeps it in the `kubeadm-config`
ConfigMap, and changing it is a kubeadm reconfiguration rather than a re-run.

| Setting | Value | Why |
|---|---|---|
| Version | 1.36.6 | `versions.yml` |
| API endpoint | `10.10.0.10:6443` | The VIP; certificate also valid for `k8s-api.rls.internal` |
| Pod / Service networks | `10.244.0.0/16` / `10.96.0.0/12` | `group_vars/all/main.yml` |
| kube-proxy | Not installed (`proxy.disabled`) | Cilium replaces it. Stored in `kubeadm-config`, so `kubeadm upgrade` keeps it off |
| Component metrics | controller-manager and scheduler listen on all addresses; etcd metrics on `:2381` | Prometheus scrapes them from other nodes. The first two still require authorisation |
| Graceful node shutdown | 60 s, the last 20 s for critical pods | Pods stop and volumes detach cleanly on a reboot |
| Certificates | kubeadm defaults: 1 year, 10 years for the CAs | Renewed by every `kubeadm upgrade`; check with `sudo kubeadm certs check-expiration` |

The playbook runs `kubeadm config validate` on the rendered file before `kubeadm init`, so a typo
or an invalid value fails the run before anything changes on the node.

---

## Cilium: the pod network

Cilium is the CNI, implements Services in eBPF in place of kube-proxy, enforces network policies,
and records every flow in Hubble. Values: `roles/cilium/templates/values.yaml.j2`.

| Setting | Value | Why |
|---|---|---|
| kube-proxy replacement | on; API reached at the VIP | No kube-proxy means no ClusterIP for the API server until Cilium runs, so the agents use the VIP |
| IPAM | `kubernetes` | Pods get addresses from each node's range of `pod_cidr`, assigned by kube-controller-manager: one source of truth |
| Routing | `native`, with routes between nodes | No encapsulation: lower overhead, full MTU. Requires the nodes to share an L2 segment, which the Terraform address plan guarantees. Set `cilium_routing_mode: tunnel` (VXLAN) for nodes in routed subnets — decide before production, switching later briefly interrupts pod traffic |
| Masquerading | eBPF | Pod traffic leaving the cluster takes the node's address, without iptables rules |
| LoadBalancer IPAM | off (`defaultLBServiceIPAM: none`) | MetalLB owns LoadBalancer addresses; Cilium's own LB IPAM would otherwise compete for them |
| Config changes | `rollOutCiliumPods` | The agents restart when their configuration changes, so a change actually applies |
| Metrics | agent, operator, Envoy and Hubble `ServiceMonitor`s; Grafana dashboards as ConfigMaps | Picked up automatically once Prometheus and Grafana are deployed |
| Hubble | relay and UI; certificates rotated by a CronJob | Flow visibility for debugging and network policy work |

The values are validated against the chart's own schema by `helm template`, and the rendered
manifests with kubeconform.

**Images and downloads.** These layers pull the control plane images from `registry.k8s.io`,
kube-vip from `ghcr.io` and Cilium from `quay.io`, download Helm from `get.helm.sh`, and fetch charts
from `prometheus-community.github.io` and `helm.cilium.io`. Without internet access: mirror the
registries with `containerd_registry_mirrors` and set `control_plane_tools_helm_download_base_url`,
`prometheus_operator_crds_chart_repo` and `cilium_chart_repo` (see
[02 — Ansible](02-ansible.md#mirrors-and-networks-without-internet-access)).

---

## Verify

On any control plane, as the admin user:

```bash
kubectl get nodes -o wide
kubectl -n kube-system get pods -o wide
kubectl -n kube-system get lease plndr-cp-lock -o jsonpath='{.spec.holderIdentity}{"\n"}'
kubectl get crd | grep monitoring.coreos.com
sudo kubeadm certs check-expiration
```

All six nodes are **Ready**. In `kube-system`, the control plane pods (including one
`kube-vip-<node>` per control plane), CoreDNS, a `cilium` and a `cilium-envoy` pod per node, the
Cilium operator and Hubble are Running. The lease holder is the node that currently owns the VIP; on
that node, `ip -brief address` shows `10.10.0.10/32` next to its own address.

Cilium's own view, from any agent:

```bash
kubectl -n kube-system exec ds/cilium -c cilium-agent -- cilium-dbg status --brief    # OK
kubectl -n kube-system exec ds/cilium -c cilium-agent -- cilium-dbg status | grep KubeProxyReplacement
kubectl -n kube-system exec ds/cilium -c cilium-agent -- hubble observe --last 20
kubectl -n kube-system port-forward svc/hubble-ui 12000:80                             # http://localhost:12000
```

For a thorough check, run `cilium connectivity test` from a workstation with the
[Cilium CLI](https://github.com/cilium/cilium-cli) and this cluster's kubeconfig; it deploys test
workloads into `cilium-test-*` namespaces, which you delete afterwards.

**Failover test.** On the lease holder, stop kube-vip by moving its manifest away:

```bash
sudo mv /etc/kubernetes/manifests/kube-vip.yaml /root/
```

From another machine, `kubectl get nodes` keeps answering after a short pause, and the lease names
another control plane. Move the file back afterwards.

**From your workstation:**

```bash
scp ubuntu@10.10.0.11:.kube/config ~/.kube/rls-onprem
KUBECONFIG=~/.kube/rls-onprem kubectl get nodes
```

That file holds cluster-admin credentials; keep it like a root password.

---

## Day 2

| Task | How |
|---|---|
| Add a worker | Add it to `terraform.tfvars`, `terraform apply`, then `ansible-playbook site.yml --limit wk-04` |
| Add control planes | Keep the count odd (3 → 5), then the same as a worker. They join one at a time |
| Remove a worker | `kubectl drain wk-03 --ignore-daemonsets --delete-emptydir-data`, `kubectl delete node wk-03`, then remove it from Terraform |
| Remove a control plane | Drain it, run `sudo kubeadm reset` on it (this also removes its etcd member), `kubectl delete node`, then Terraform |
| Rebuild a node under the same name | Delete the old Node object first, or the join is refused |
| Upgrade Kubernetes | A kubeadm upgrade, control planes first — not a re-run with a new version (the [version guard](02-ansible.md#kubernetes_packages) stops that). See [10 — Operations](10-operations.md) |

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `kubeadm init` stops waiting for the control plane | kube-vip cannot hold the VIP. `sudo crictl ps -a --name kube-vip`, then `sudo crictl logs <id>`. Usual causes: the VIP is in use by another machine, or the wrong interface (the role checks `kube_vip_interface` carries the node's address) |
| A failed `kubeadm init` is not retried | `admin.conf` already exists from the half-finished run. `sudo kubeadm reset -f` on the first control plane, then re-run |
| A control plane join fails to download certificates | The uploaded certificates expired. Re-run: every run uploads them again for nodes that still need them |
| `a Node with name … already exists` | The node was rebuilt. `kubectl delete node <name>`, then re-run |
| Nodes stay `NotReady` | Expected until Cilium is installed. Afterwards: `kubectl -n kube-system logs ds/cilium -c cilium-agent`. Typical causes are an agent that cannot reach the VIP, or a node without a pod CIDR (`kubectl get node <name> -o jsonpath='{.spec.podCIDR}'`) |
| Pods on different nodes cannot reach each other | With native routing the network between nodes must forward pod addresses. A firewall filtering by IP on the hypervisor bridge drops them: allow `pod_cidr`, or switch to `cilium_routing_mode: tunnel` |
